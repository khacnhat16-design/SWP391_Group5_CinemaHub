package com.cinema.service.concession;

import com.cinema.dao.concession.ConcessionOrderDAO;
import com.cinema.dao.concession.InventoryDAO;
import com.cinema.dao.concession.ProductDAO;
import com.cinema.dao.payment.PaymentDAO;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.branch.Branch;
import com.cinema.model.concession.ConcessionOrder;
import com.cinema.model.concession.Product;
import com.cinema.model.payment.Payment;
import com.cinema.service.audit.AuditService;
import com.cinema.service.booking.BookingService;

import com.cinema.dao.branch.BranchDAO;
import com.cinema.common.ServiceException;
import com.cinema.model.showtime.Showtime;
import com.cinema.service.showtime.ShowtimeService;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Service danh mục/combo + bán offline + pre-order online + pickup (Req 19–21).
 *
 * <p>Chiều phụ thuộc bắt buộc (design.md): Controller → Service → DAO; không cho
 * Service gọi ngược DAO miền khác — Inventory/branch/showtime kiểm qua Service/DAO
 * tương ứng.
 */
public class ConcessionService {
    private static final Logger logger = Logger.getLogger(ConcessionService.class.getName());
    /** Req 15.3/11.2 — audit thay đổi trạng thái đơn bắp nước. */
    private final com.cinema.service.audit.AuditService audit = new com.cinema.service.audit.AuditService();
    private static final Set<String> VALID_TYPES = Set.of(
            Product.TYPE_POPCORN, Product.TYPE_DRINK, Product.TYPE_COMBO, Product.TYPE_OTHER);
    /** Thời gian chờ thanh toán offline (Req 20.6): 30 phút. */
    public static final int OFFLINE_PENDING_MINUTES = 30;
    /** Khoảng chốt trước giờ chiếu cho pre-order (Req 21.2): 30 phút. */
    public static final int PREORDER_CUTOFF_MINUTES = 30;

    private final ProductDAO productDao;
    private final InventoryDAO inventoryDao;
    private final ConcessionOrderDAO orderDao;
    private final PaymentDAO paymentDao = new PaymentDAO();
    private final BranchDAO branchDao;
    private final ShowtimeService showtimeService;
    private final TransactionTemplate tx = new TransactionTemplate();
    private final com.cinema.service.notification.NotificationService notificationService =
            new com.cinema.service.notification.NotificationService(new com.cinema.dao.notification.NotificationDAO());

    public ConcessionService(ProductDAO productDao, InventoryDAO inventoryDao,
                             ConcessionOrderDAO orderDao, BranchDAO branchDao,
                             ShowtimeService showtimeService) {
        this.productDao = productDao;
        this.inventoryDao = inventoryDao;
        this.orderDao = orderDao;
        this.branchDao = branchDao;
        this.showtimeService = showtimeService;
    }

    // ========== 19.1–19.3 — Product & Combo (Admin) ==========

    /**
     * Admin tạo sản phẩm (Req 19.1): tên bắt buộc + unique, giá dương.
     */
    public Product createProduct(String name, String description, long price, String unit,
                                 String type, long actorId) throws Exception {
        validateProductCreate(name, price, type);
        // Check trùng tên TRƯỚC khi insert để trả 409 rõ ràng; UNIQUE constraint ở DB
        // vẫn là lớp bảo vệ cuối cho race condition giữa 2 request tạo cùng tên.
        if (productDao.findByName(name.trim()).isPresent()) {
            throw new ServiceException.Conflict("Tên sản phẩm đã tồn tại");
        }

        Product product = new Product();
        product.setName(name.trim());
        product.setDescription(description);
        product.setPrice(price);
        product.setUnit(unit == null || unit.isBlank() ? "PHẦN" : unit.trim());
        product.setType(type.trim().toUpperCase());
        product.setStatus(Product.STATUS_ACTIVE);
        productDao.insert(product);

        logger.info("Created product " + product.id() + " (" + product.name() + ")");
        return product;
    }

    /**
     * Admin tạo combo (Req 19.2): các sản phẩm thành phần tồn tại + ACTIVE, qty > 0.
     */
    public Product createCombo(String name, String description, long price, String unit,
                               List<Map<String, Object>> components, long actorId) throws Exception {
        validateProductCreate(name, price, Product.TYPE_COMBO);
        if (productDao.findByName(name.trim()).isPresent()) {
            throw new ServiceException.Conflict("Tên sản phẩm đã tồn tại");
        }
        if (components == null || components.isEmpty()) {
            throw new ServiceException.Validation("Combo phải có ít nhất một sản phẩm thành phần");
        }

        Product combo = new Product();
        combo.setName(name.trim());
        combo.setDescription(description);
        combo.setPrice(price);
        combo.setUnit(unit == null || unit.isBlank() ? "COMBO" : unit.trim());
        combo.setType(Product.TYPE_COMBO);
        combo.setStatus(Product.STATUS_ACTIVE);
        productDao.insert(combo);

        for (Map<String, Object> comp : components) {
            long productId = ((Number) comp.get("productId")).longValue();
            int qty = ((Number) comp.get("qty")).intValue();
            if (qty <= 0) {
                throw new ServiceException.Validation("Số lượng thành phần combo phải là số nguyên dương");
            }
            Product ingredient = productDao.findById(productId)
                    .orElseThrow(() -> new ServiceException.NotFound(
                            "Sản phẩm thành phần " + productId + " không tồn tại"));
            if (!ingredient.isActive()) {
                throw new ServiceException.BusinessRule("COMBO_INGREDIENT_INACTIVE",
                        "Sản phẩm thành phần " + ingredient.name() + " đã ngừng kinh doanh");
            }
            if (ingredient.isCombo()) {
                throw new ServiceException.Validation("Combo không được chứa combo lồng nhau");
            }
            if (productId == combo.id()) {
                throw new ServiceException.Validation("Combo không được chứa chính nó");
            }
            productDao.insertComboItem(combo.id(), productId, qty);
        }

        logger.info("Created combo " + combo.id() + " (" + name.trim() + ")");
        return combo;
    }

    /**
     * Admin vô hiệu hóa sản phẩm (Req 19.3): chặn khi còn nằm trong combo ACTIVE.
     * Trả về danh sách combo bị ảnh hưởng trong lỗi để Admin biết phải cập nhật combo.
     */
    public Product deactivateProduct(long productId) throws Exception {
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));

        if (!product.isCombo()) {
            List<Product> combos = productDao.findActiveCombosContaining(productId);
            if (!combos.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Product combo : combos) names.append(combo.name()).append(", ");
                throw new ServiceException.BusinessRule("PRODUCT_IN_COMBO",
                        "Không thể vô hiệu hóa sản phẩm vì còn nằm trong combo hiệu lực: "
                                + names + "— hãy cập nhật combo liên quan trước");
            }
        }

        productDao.updateStatus(productId, Product.STATUS_INACTIVE);
        product.setStatus(Product.STATUS_INACTIVE);
        return product;
    }

    /**
     * Mở lại kinh doanh sản phẩm (Req 19.3 ngược): set status = ACTIVE.
     * Khi mở lại, các combo đã bị chặn trước đó có thể chứa sản phẩm này — DAO
     * sẽ tự chấp nhận (combo quản lý riêng qua trang combo).
     */
    public Product activateProduct(long productId) throws Exception {
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));
        productDao.updateStatus(productId, Product.STATUS_ACTIVE);
        product.setStatus(Product.STATUS_ACTIVE);
        return product;
    }

    /** Cập nhật giá bán (Req 19.8): áp cho đơn tạo sau, đơn cũ giữ snapshot. */
    public Product updatePrice(long productId, long newPrice) throws Exception {
        if (newPrice < 0) {
            throw new ServiceException.Validation("Giá bán không được âm");
        }
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));

        product.setPrice(newPrice);
        productDao.update(product);
        return product;
    }

    /** Admin cập nhật toàn bộ thông tin sản phẩm, giữ nguyên combo_item hiện có. */
    public Product updateProduct(long productId, String name, String description,
                                 long price, String unit, String type) throws Exception {
        if (name == null || name.isBlank()) {
            throw new ServiceException.Validation("Tên sản phẩm là bắt buộc");
        }
        if (price < 0) {
            throw new ServiceException.Validation("Giá bán không được âm");
        }
        if (!InventoryService.isValidType(type)) {
            throw new ServiceException.Validation("Loại sản phẩm không hợp lệ");
        }
        Product product = productDao.findById(productId)
                .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm không tồn tại"));
        product.setName(name.trim());
        product.setDescription(description);
        product.setPrice(price);
        product.setUnit(unit == null || unit.isBlank() ? "phần" : unit.trim());
        product.setType(type.trim().toUpperCase());
        productDao.update(product);
        return product;
    }

    // ========== 20 — Offline POS sales (Req 20.1–20.7) ==========

    /**
     * Tạo bản nháp đơn bắp nước tại quầy (Req 20.1): check sản phẩm ACTIVE tại chi
     * nhánh, tồn kho đủ, giá hiện hành; tạo đơn PENDING chờ thanh toán.
     * Có thể gắn vé hợp lệ cho thống kê theo suất chiếu (Req 20.7).
     */
    public ConcessionOrder createOfflineOrder(Long actorBranchScope, long branchId,
                                              List<OrderLineInput> lines, String payMethod,
                                              Long customerId, Long ticketId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        requireActiveBranch(branchId);
        validateOrderLines(lines);
        requireBranchActiveProduct(lines, branchId);
        // Req 20.7 — vé gắn đơn phải thuộc branch và suất chưa kết thúc
        Long showtimeId = null;
        if (ticketId != null) {
            showtimeId = requireTicketShowtime(ticketId, branchId);
        }

        List<ConcessionOrder.OrderLine> priced = priceLines(lines);
        requireAvailableInventory(branchId, priced);
        long total = totalAmount(priced);
        ConcessionOrder order = new ConcessionOrder();
        order.setOrderCode(generateOrderCode());
        order.setBranchId(branchId);
        order.setTicketId(ticketId);
        order.setShowtimeId(showtimeId);
        order.setUserId(customerId);
        order.setStatus(ConcessionOrder.STATUS_PENDING);
        order.setTotalAmount(total);
        order.setPickupCode(generatePickupCode());

        return tx.execute(conn -> {
            long orderId = orderDao.insert(conn, order);
            orderDao.insertLines(conn, orderId, priced);
            logger.info("Created offline concession order " + order.orderCode() + " (" + total + "đ)");
            return loadOrderWithLines(conn, orderId);
        });
    }

    /**
     * Tạo + xác nhận thanh toán đơn F&B offline trong transaction do caller quản lý
     * (dùng cho POS counter-sale bundle — đơn được gắn {@code ticketId}, staff trả
     * tiền mặt toàn bộ, atomic với tạo vé). Trừ kho, cộng điểm nếu gắn khách,
     * chuyển trạng thái READY_FOR_PICKUP.
     *
     * @return order sau khi confirm
     */
    public ConcessionOrder createAndConfirmOfflineInTx(java.sql.Connection conn,
                                                       long branchId,
                                                       java.util.List<OrderLineInput> lines,
                                                       Long customerId,
                                                       Long ticketId,
                                                       long staffId) throws Exception {
        return createAndConfirmOfflineInTx(conn, branchId, lines, customerId, ticketId,
                staffId, Payment.METHOD_CASH);
    }

    public ConcessionOrder createAndConfirmOfflineInTx(java.sql.Connection conn,
                                                       long branchId,
                                                       java.util.List<OrderLineInput> lines,
                                                       Long customerId,
                                                       Long ticketId,
                                                       long staffId,
                                                       String payMethod) throws Exception {
        return createAndConfirmOfflineInTx(conn, branchId, lines, customerId, ticketId,
                staffId, payMethod, null);
    }

    public ConcessionOrder createAndConfirmOfflineInTx(java.sql.Connection conn,
                                                       long branchId,
                                                       java.util.List<OrderLineInput> lines,
                                                       Long customerId,
                                                       Long ticketId,
                                                       long staffId,
                                                       String payMethod,
                                                       String paymentReference) throws Exception {
        String method = normalizeCounterPaymentMethod(payMethod);
        String reference = requireCounterPaymentReference(method, paymentReference);
        requireActiveBranch(branchId);
        validateOrderLines(lines);
        requireBranchActiveProduct(lines, branchId);
        // Req 20.7 — đơn gắn vé phải cùng chi nhánh + suất chiếu còn hiệu lực
        Long showtimeId = null;
        if (ticketId != null) {
            showtimeId = requireTicketShowtime(conn, ticketId, branchId);
        }

        List<ConcessionOrder.OrderLine> priced = priceLines(lines);
        requireAvailableInventory(conn, branchId, priced);
        long total = totalAmount(priced);
        ConcessionOrder order = new ConcessionOrder();
        order.setOrderCode(generateOrderCode());
        order.setBranchId(branchId);
        order.setTicketId(ticketId);
        order.setShowtimeId(showtimeId);
        order.setUserId(customerId);
        order.setStatus(ConcessionOrder.STATUS_PENDING);
        order.setTotalAmount(total);
        order.setPickupCode(generatePickupCode());

        long orderId = orderDao.insert(conn, order);
        orderDao.insertLines(conn, orderId, priced);

        deductOrderInventory(conn, branchId, orderDao.findLines(conn, orderId), staffId, order.orderCode());
        if (!orderDao.updateStatusGuarded(conn, orderId,
                ConcessionOrder.STATUS_READY_FOR_PICKUP,
                ConcessionOrder.STATUS_PENDING, null, null)) {
            throw new ServiceException.Conflict("Đơn vừa được xử lý bởi một yêu cầu khác");
        }
        recordCounterPayment(conn, orderId, method, total, reference);
        logger.info("Counter-sale bundle: created + confirmed concession order "
                + order.orderCode() + " (" + total + "đ) for ticket " + ticketId);
        audit.record(conn, staffId, "CONFIRM_CONCESSION_ORDER", "CONCESSION_ORDER", orderId,
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"READY_FOR_PICKUP\",\"method\":\"" + method + "\",\"total\":" + total
                        + ",\"bundle\":\"counter-sale\"}",
                com.cinema.service.audit.AuditService.SUCCESS);
        return loadOrderWithLines(conn, orderId);
    }

    /**
     * Xác nhận thanh toán offline (Req 20.3): guarded trừ tồn kho (hai Staff bán
     * cùng sản phẩm cuối tồn kho → đúng một thắng, Req 20.4), chuyển READY_FOR_PICKUP,
     * cộng điểm nếu gắn khách (Req 20.3 + Req 17). Trừ kho thất bại → rollback toàn bộ.
     */
    public ConcessionOrder confirmOfflinePayment(long orderId, String payMethod,
                                                 Long customerId, Long actorBranchScope,
                                                 long staffId) throws Exception {
        return confirmOfflinePayment(orderId, payMethod, customerId, actorBranchScope, staffId, null);
    }

    public ConcessionOrder confirmOfflinePayment(long orderId, String payMethod,
                                                 Long customerId, Long actorBranchScope,
                                                 long staffId, String paymentReference) throws Exception {
        String method = normalizeCounterPaymentMethod(payMethod);
        String reference = requireCounterPaymentReference(method, paymentReference);

        return tx.execute(conn -> {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn bắp nước không tồn tại"));
            if (!ConcessionOrder.STATUS_PENDING.equals(order.status())) {
                throw new ServiceException.Conflict("Đơn không ở trạng thái chờ thanh toán");
            }
            // Req 19.6/20.2 — trừ tồn kho guarded; hết hàng → rollback, không trừ âm kho
            requireBranchScope(actorBranchScope, order.branchId());
            deductOrderInventory(conn, order.branchId(), orderDao.findLines(conn, orderId), staffId, order.orderCode());

            if (!orderDao.updateStatusGuarded(conn, orderId, ConcessionOrder.STATUS_READY_FOR_PICKUP,
                    ConcessionOrder.STATUS_PENDING, null, null)) {
                throw new ServiceException.Conflict("Đơn vừa được xử lý bởi một yêu cầu khác");
            }

            recordCounterPayment(conn, orderId, method, order.totalAmount(), reference);
            if (effectiveUserId(order, customerId) != null) {
                long buyerId = effectiveUserId(order, customerId);
                try {
                    notificationService.notifyFnbOrderConfirmed(buyerId, order.id(),
                            order.orderCode(), order.pickupCode(), order.totalAmount(), null);
                } catch (Exception ignored) {}
            }

            logger.info("Confirmed offline order " + order.orderCode() + " (" + method + ")");
            audit.record(conn, staffId, "CONFIRM_CONCESSION_ORDER", "CONCESSION_ORDER", orderId,
                    "{\"status\":\"PENDING\"}",
                    "{\"status\":\"READY_FOR_PICKUP\",\"method\":\"" + method
                            + "\",\"total\":" + order.totalAmount() + "}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return loadOrderWithLines(conn, orderId);
        });
    }

    private String normalizeCounterPaymentMethod(String payMethod) {
        String method = payMethod == null || payMethod.isBlank()
                ? Payment.METHOD_CASH : payMethod.trim().toUpperCase();
        if (!Payment.METHOD_CASH.equals(method) && !Payment.METHOD_BANK_TRANSFER.equals(method)) {
            throw new ServiceException.Validation(
                    "Phương thức thanh toán tại quầy phải là CASH hoặc BANK_TRANSFER");
        }
        return method;
    }

    private String requireCounterPaymentReference(String method, String reference) {
        if (Payment.METHOD_BANK_TRANSFER.equals(method)
                && (reference == null || !reference.matches("(?i)[A-Z0-9]{1,25}"))) {
            throw new ServiceException.Validation(
                    "Cần mã tham chiếu QR hợp lệ cho thanh toán chuyển khoản");
        }
        return reference == null ? null : reference.toUpperCase(java.util.Locale.ROOT);
    }

    private void recordCounterPayment(Connection conn, long orderId, String method,
                                      long amount, String reference) throws Exception {
        Payment payment = new Payment();
        payment.setConcessionOrderId(orderId);
        payment.setMethod(method);
        payment.setAmount(amount);
        payment.setStatus(Payment.STATUS_SUCCESS);
        payment.setIdempotencyKey("POS-FNB-" + orderId + "-" + UUID.randomUUID());
        payment.setProviderReference(reference);
        paymentDao.insertConfirmed(conn, payment);
    }

    /**
     * Hủy/hoàn đơn offline đã confirmed (Req 20.5): yêu cầu lý do, kiểm quyền
     * Branch Manager trở lên, hoàn tiền theo phương thức CASH, hoàn kho
     * và trừ lại điểm đã cộng (Req 10.5/17.4/18.5).
     */
    public ConcessionOrder cancelConfirmedOffline(long orderId, String cancelReason,
                                                  long actorStaffBranchId, long actorUserId,
                                                  boolean isManager) throws Exception {
        if (cancelReason == null || cancelReason.isBlank()) {
            throw new ServiceException.Validation(
                    "Hủy đơn bắp nước đã xác nhận phải có lý do (hoàn tiền)");
        }
        if (!isManager) {
            throw new ServiceException.Forbidden(
                    "Chỉ Branch Manager trở lên mới được hoàn tiền đơn bắp nước đã xác nhận");
        }

        return tx.execute(conn -> {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn bắp nước không tồn tại"));
            if (!ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())
                    && !ConcessionOrder.STATUS_FULFILLED.equals(order.status())) {
                throw new ServiceException.Conflict("Đơn không ở trạng thái có thể hủy/hoàn");
            }
            if (order.branchId() != actorStaffBranchId) {
                throw new ServiceException.Forbidden(
                        "Vi phạm phạm vi chi nhánh: đơn thuộc chi nhánh khác");
            }

            restoreOrderInventory(conn, order.branchId(), orderDao.findLines(conn, orderId), actorUserId, order.orderCode());

            if (!orderDao.updateStatusGuarded(conn, orderId, ConcessionOrder.STATUS_CANCELLED,
                    order.status(), cancelReason.trim(), null)) {
                throw new ServiceException.Conflict("Đơn vừa được xử lý bởi một yêu cầu khác");
            }
            paymentDao.markConcessionPaymentsRefunded(conn, orderId);

            logger.info("Cancelled concession order " + order.orderCode() + " (" + cancelReason + ")");
            audit.record(conn, actorUserId, "CANCEL_CONCESSION_ORDER", "CONCESSION_ORDER", orderId,
                    "{\"status\":\"" + order.status() + "\"}",
                    "{\"status\":\"CANCELLED\",\"reason\":\"" + cancelReason.trim() + "\"}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return loadOrderWithLines(conn, orderId);
        });
    }

    public void deductOrderInventory(Connection conn, long branchId,
                                     List<ConcessionOrder.OrderLine> lines) throws Exception {
        deductOrderInventory(conn, branchId, lines, null, null);
    }

    public void deductOrderInventory(Connection conn, long branchId,
                                     List<ConcessionOrder.OrderLine> lines,
                                     Long actorId, String orderCode) throws Exception {
        for (InventoryRequirement requirement : inventoryRequirements(conn, lines)) {
            if (!inventoryDao.tryDeduct(conn, branchId, requirement.productId(), requirement.qty())) {
                throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                        "Sản phẩm " + requirement.name() + " không đủ tồn kho"
                                + " (cần " + requirement.qty() + ")");
            }
            try {
                long actor = (actorId != null && actorId > 0) ? actorId : 1L;
                String reason = "BÁN HÀNG" + (orderCode != null && !orderCode.isBlank() ? " (" + orderCode + ")" : "");
                inventoryDao.insertAdjustment(conn, branchId, requirement.productId(), -requirement.qty(), reason, actor);
            } catch (Exception e) {
                logger.warning("Failed to insert inventory adjustment for deduction: " + e.getMessage());
            }
        }
    }

    public void restoreOrderInventory(Connection conn, long branchId,
                                      List<ConcessionOrder.OrderLine> lines) throws Exception {
        restoreOrderInventory(conn, branchId, lines, null, null);
    }

    public void restoreOrderInventory(Connection conn, long branchId,
                                      List<ConcessionOrder.OrderLine> lines,
                                      Long actorId, String orderCode) throws Exception {
        for (InventoryRequirement requirement : inventoryRequirements(conn, lines)) {
            inventoryDao.restore(conn, branchId, requirement.productId(), requirement.qty());
            try {
                long actor = (actorId != null && actorId > 0) ? actorId : 1L;
                String reason = "HOÀN KHO" + (orderCode != null && !orderCode.isBlank() ? " (" + orderCode + ")" : "");
                inventoryDao.insertAdjustment(conn, branchId, requirement.productId(), requirement.qty(), reason, actor);
            } catch (Exception e) {
                logger.warning("Failed to insert inventory adjustment for restore: " + e.getMessage());
            }
        }
    }

    private List<InventoryRequirement> inventoryRequirements(
            Connection conn, List<ConcessionOrder.OrderLine> lines) throws Exception {
        List<InventoryRequirement> requirements = new ArrayList<>();
        for (ConcessionOrder.OrderLine line : lines) {
            Product product = productDao.findById(conn, line.productId())
                    .orElseThrow(() -> new ServiceException.NotFound(
                            "Sản phẩm " + line.productId() + " không tồn tại"));
            if (!product.isCombo()) {
                requirements.add(new InventoryRequirement(product.id(), product.name(), line.qty()));
                continue;
            }
            List<Product.ComboComponent> components = productDao.findComboItems(conn, product.id());
            if (components.isEmpty()) {
                throw new ServiceException.BusinessRule("COMBO_CONFIGURATION_INVALID",
                        "Combo " + product.name() + " chưa được cấu hình sản phẩm thành phần");
            }
            for (Product.ComboComponent component : components) {
                int componentQty;
                try {
                    componentQty = Math.multiplyExact(line.qty(), component.qty());
                } catch (ArithmeticException e) {
                    throw new ServiceException.Validation("Số lượng combo vượt quá giới hạn");
                }
                requirements.add(new InventoryRequirement(
                        component.productId(), component.productName(), componentQty));
            }
        }
        return requirements;
    }

    private record InventoryRequirement(long productId, String name, int qty) { }

    // ========== 21 — Pre-order gắn vé/suất + pickup (Req 21.1–21.9) ==========

    /**
     * Tạo đơn pre-order cơ bản (không có points).
     * @deprecated Use createPreorderWithPoints() for Phase 3
     */
    public ConcessionOrder createPreorder(long ticketId, List<OrderLineInput> lines,
                                          String payMethod, Long actorUserId) throws Exception {
        return createPreorderWithPoints(ticketId, lines, payMethod, actorUserId, 0);
    }

    /**
     * Pre-order kèm booking hoặc cho vé đã confirmed (Req 21.1, 21.2, Phase 3):
     * bắt buộc suất chiếu chưa kết thúc và đặt trước giờ chiếu tối thiểu PREORDER_CUTOFF_MINUTES.
     * Thanh toán thành công → READY_FOR_PICKUP (Req 21.3).
     *
     * @param pointsUsed số điểm khách muốn đổi (Phase 3)
     */
    public ConcessionOrder createPreorderWithPoints(long ticketId, List<OrderLineInput> lines,
                                                   String payMethod, Long actorUserId,
                                                   int pointsUsed) throws Exception {
        if (pointsUsed < 0) {
            throw new ServiceException.Validation("Số điểm sử dụng không được âm");
        }
        validateOrderLines(lines);
        TicketLike ticket = requirePreorderTicket(ticketId, actorUserId);
        requireBranchActiveProduct(lines, ticket.branchId());
        Showtime showtime = showtimeService.findById(ticket.showtimeId()); // helper below
        requirePreorderCutoff(showtime);

        List<ConcessionOrder.OrderLine> priced = priceLines(lines);
        requireAvailableInventory(ticket.branchId(), priced);
        long total = totalAmount(priced);

        // Customer có thể đổi toàn bộ số điểm khả dụng; số tiền phải thanh toán
        // được chặn ở 0 trong amountAfterPointsDiscount().
        long pointsDiscount = 0;
        int finalPointsUsed = 0;
        if (pointsUsed > 0 && actorUserId != null) {
            int redemptionRate = 1000;
            if (!true) {
                throw new ServiceException.BusinessRule("POINTS_INSUFFICIENT",
                        "Số dư điểm không đủ. Bạn có " + 0 + " điểm.");
            }
            finalPointsUsed = pointsUsed;
            pointsDiscount = (long) finalPointsUsed * redemptionRate;
        }

        ConcessionOrder order = new ConcessionOrder();
        order.setOrderCode(generateOrderCode());
        order.setBranchId(ticket.branchId());
        order.setTicketId(ticketId);
        order.setShowtimeId(ticket.showtimeId());
        order.setUserId(actorUserId);
        order.setStatus(ConcessionOrder.STATUS_PENDING);
        order.setTotalAmount(total);
        // Lưu lại số điểm đã burn + số tiền được giảm để ledger loyalty ghi nhận
        // đúng và để refund (nếu hủy đơn) trả lại đúng phần đã trừ.
        order.setPointsUsed(finalPointsUsed);
        order.setPointsDiscount(pointsDiscount);
        order.setPickupCode(generatePickupCode());
        order.setPickupDeadline(calculatePickupDeadline(showtime));

        final int preorderPointsUsed = finalPointsUsed;
        final long preorderPointsDiscount = pointsDiscount;
        return tx.execute(conn -> {
            long orderId = orderDao.insert(conn, order);
            orderDao.insertLines(conn, orderId, priced);
            logger.info("Created concession preorder " + order.orderCode()
                    + " for ticket " + ticketId + " (" + total + "đ, pointsUsed=" + preorderPointsUsed
                    + ", pointsDiscount=" + preorderPointsDiscount + ")");
            return loadOrderWithLines(conn, orderId);
        });
    }

    /**
     * Create standalone F&B order (not attached to a ticket) with points redemption (Phase 3).
     * Used by the F&B cart/checkout flow.
     *
     * @param branchId chi nhánh nhận hàng
     * @param lines danh sách sản phẩm
     * @param actorUserId user đang đặt (Customer)
     * @param pointsUsed số điểm muốn đổi
     * @return ConcessionOrder đã tạo với PENDING status
     */
    public ConcessionOrder createStandaloneOrder(long branchId, List<OrderLineInput> lines,
                                                  Long actorUserId, int pointsUsed) throws Exception {
        if (pointsUsed < 0) {
            throw new ServiceException.Validation("Số điểm sử dụng không được âm");
        }
        validateOrderLines(lines);
        requireActiveBranch(branchId);
        requireBranchActiveProduct(lines, branchId);

        List<ConcessionOrder.OrderLine> priced = priceLines(lines);
        requireAvailableInventory(branchId, priced);
        long total = totalAmount(priced);

        // Cho phép dùng toàn bộ số điểm khả dụng; amountAfterPointsDiscount() bảo đảm
        // số tiền thanh toán không âm nếu giá trị điểm vượt tổng đơn.
        long pointsDiscount = 0;
        int finalPointsUsedForStandalone = 0;
        if (pointsUsed > 0 && actorUserId != null) {
            int redemptionRate = 1000;
            if (!true) {
                throw new ServiceException.BusinessRule("POINTS_INSUFFICIENT",
                        "Số dư điểm không đủ. Bạn có " + 0 + " điểm.");
            }
            finalPointsUsedForStandalone = pointsUsed;
            pointsDiscount = (long) finalPointsUsedForStandalone * redemptionRate;
        }

        ConcessionOrder order = new ConcessionOrder();
        order.setOrderCode(generateOrderCode());
        order.setBranchId(branchId);
        order.setUserId(actorUserId);
        order.setStatus(ConcessionOrder.STATUS_PENDING);
        order.setTotalAmount(total);
        order.setPointsUsed(finalPointsUsedForStandalone);
        order.setPointsDiscount(pointsDiscount);
        order.setPickupCode(generatePickupCode());
        // Standalone F&B không gắn với suất chiếu nên không có pickupDeadline — khách
        // có thể đến quầy nhận hàng bất cứ lúc nào trong giờ mở cửa chi nhánh.

        final int standalonePointsUsed = finalPointsUsedForStandalone;
        final long standalonePointsDiscount = pointsDiscount;
        return tx.execute(conn -> {
            long orderId = orderDao.insert(conn, order);
            orderDao.insertLines(conn, orderId, priced);
            logger.info("Created standalone F&B order " + order.orderCode()
                    + " (" + total + "đ, pointsUsed=" + standalonePointsUsed
                    + ", pointsDiscount=" + standalonePointsDiscount + ")");
            return loadOrderWithLines(conn, orderId);
        });
    }

    /**
     * Cancel standalone F&B order (Phase 3).
     */
    public ConcessionOrder cancelStandaloneOrder(long orderId, long actorUserId,
                                                  boolean isManager, String cancelReason) throws Exception {
        if (cancelReason == null || cancelReason.isBlank()) {
            throw new ServiceException.Validation("Hủy đơn phải có lý do");
        }

        return tx.execute(conn -> {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn bắp nước không tồn tại"));

            if (!isManager && order.userId() != null && !order.userId().equals(actorUserId)) {
                throw new ServiceException.Forbidden("Bạn không có quyền hủy đơn này");
            }

            // Đơn READY_FOR_PICKUP đã được staff xác nhận, FULFILLED/CANCELLED thì khoét xong.
            // Cho phép hủy cả PENDING (chưa thanh toán) để tránh đơn "treo" chiếm tồn kho ảo.
            if (!ConcessionOrder.STATUS_PENDING.equals(order.status())
                    && !ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())) {
                throw new ServiceException.Conflict("Đơn không thể hủy ở trạng thái hiện tại");
            }

            boolean wasPaid = ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status());

            // Chỉ đơn đã thanh toán mới cần hoàn lại inventory + điểm; với đơn
            // PENDING chưa trừ gì thì chỉ cần chuyển trạng thái là đủ.
            if (wasPaid) {
                List<ConcessionOrder.OrderLine> lines = orderDao.findLines(conn, orderId);
                restoreOrderInventory(conn, order.branchId(), lines, actorUserId, order.orderCode());

                // Hoàn điểm loyalty đã burn ở bước confirm để số dư khớp với thực tế.
            }

            // updateStatusGuarded kiểm tra status cũ == status hiện tại (optimistic
            // concurrency): nếu staff khác vừa chuyển trạng thái thì fail và ném
            // Conflict thay vì ghi đè lên thay đổi mới.
            if (!orderDao.updateStatusGuarded(conn, orderId, ConcessionOrder.STATUS_CANCELLED,
                    order.status(), cancelReason.trim(), null)) {
                throw new ServiceException.Conflict("Đơn vừa được xử lý bởi yêu cầu khác");
            }

            logger.info("Cancelled standalone F&B order " + order.orderCode() + " (" + cancelReason + ")");
            audit.record(conn, actorUserId, "CANCEL_STANDALONE_FNB", "CONCESSION_ORDER", orderId,
                    "{\"status\":\"" + order.status() + "\"}",
                    "{\"status\":\"CANCELLED\",\"reason\":\"" + cancelReason.trim()
                            + "\",\"pointsRefunded\":" + order.pointsUsed() + "}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return loadOrderWithLines(conn, orderId);
        });
    }

    /**
     * Xác nhận thanh toán pre-order (Req 21.3, Phase 3): trừ tồn kho guarded (online hai
     * Customer tranh sản phẩm cuối → đúng một thắng, Req 21.8), trừ điểm đã đổi (Phase 3),
     * cộng điểm tích lũy, tạo pickup code. Đơn có số dư bằng 0 có thể xác nhận bằng điểm.
     */
    public ConcessionOrder confirmPreorderPayment(long orderId, String payMethod,
                                                  Long actorUserId) throws Exception {
        String method = payMethod == null || payMethod.isBlank() ? "CASH"
                : payMethod.trim().toUpperCase();
        if (!"CASH".equals(method) && !"POINTS".equals(method)) {
            throw new ServiceException.Validation("Phương thức pre-order không hợp lệ");
        }

        return tx.execute(conn -> {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn bắp nước không tồn tại"));
            if (!ConcessionOrder.STATUS_PENDING.equals(order.status())) {
                throw new ServiceException.Conflict("Đơn không ở trạng thái chờ thanh toán");
            }
            if (actorUserId == null || !actorUserId.equals(order.userId())) {
                throw new ServiceException.Forbidden("Chỉ chủ đơn mới được xác nhận thanh toán");
            }
            List<ConcessionOrder.OrderLine> lines = orderDao.findLines(conn, orderId);
            long amountToCharge = order.amountAfterPointsDiscount();
            if ("POINTS".equals(method) && amountToCharge > 0) {
                throw new ServiceException.Validation(
                        "Điểm chưa đủ thanh toán toàn bộ đơn hàng");
            }
            deductOrderInventory(conn, order.branchId(), lines, actorUserId, order.orderCode());

            // Deduct điểm ngay khi confirm để đảm bảo user không thể burn điểm
            // đồng thời cho nhiều đơn (redeemPoints dùng idempotency key bên trong).

            // Số tiền còn phải thu sau khi điểm đã "trả thay".
            if (!orderDao.updateStatusGuarded(conn, orderId,
                    ConcessionOrder.STATUS_READY_FOR_PICKUP, ConcessionOrder.STATUS_PENDING,
                    null, null)) {
                throw new ServiceException.Conflict("Đơn vừa được xử lý bởi một yêu cầu khác");
            }

            // Cộng điểm tích lũy trên số tiền thực trả (không tính điểm đã đổi)

            logger.info("Confirmed preorder " + order.orderCode() + " → READY_FOR_PICKUP (pointsUsed="
                    + order.pointsUsed() + ", pointsDiscount=" + order.pointsDiscount() + ")");
            return loadOrderWithLines(conn, orderId);
        });
    }

    /**
     * Nhận hàng tại quầy (Req 21.4, 21.5): pickup code hợp lệ, đơn READY_FOR_PICKUP,
     * chưa quá pickup deadline, đúng chi nhánh, một lần — lần hai bị từ chối.
     */
    public ConcessionOrder pickup(String pickupCode, long actorStaffBranchId, long actorStaffId) throws Exception {
        if (pickupCode == null || pickupCode.isBlank()) {
            throw new ServiceException.Validation("Mã nhận hàng là bắt buộc");
        }
        String normalized = pickupCode.trim().toUpperCase();

        return tx.execute(conn -> {
            ConcessionOrder order = orderDao.findByPickupCode(normalized) // read without lock
                    .orElseThrow(() -> new ServiceException.NotFound("Mã nhận hàng không tồn tại"));

            // Khóa trong cùng transaction để chặn nhận hai lần (Req 21.5)
            order = orderDao.findByIdLocked(conn, order.id())
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn không tồn tại"));

            if (order.branchId() != actorStaffBranchId) {
                throw new ServiceException.Forbidden(
                        "Vi phạm phạm vi chi nhánh: đơn thuộc chi nhánh khác");
            }
            if (!ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())) {
                if (ConcessionOrder.STATUS_FULFILLED.equals(order.status())) {
                    throw new ServiceException.Conflict("Đơn đã hoàn tất nhận — không thể nhận lại");
                }
                if (ConcessionOrder.STATUS_CANCELLED.equals(order.status())) {
                    throw new ServiceException.BusinessRule("ORDER_CANCELLED",
                            "Đơn đã bị hủy — không thể nhận hàng");
                }
                if (ConcessionOrder.STATUS_EXPIRED_NO_SHOW.equals(order.status())) {
                    throw new ServiceException.BusinessRule("ORDER_EXPIRED",
                            "Đơn quá hạn nhận — không thể nhận hàng");
                }
                throw new ServiceException.Conflict("Đơn không ở trạng thái chờ nhận");
            }
            // Quá hạn nhận: kết thúc suất chiếu gắn đơn
            if (order.pickupDeadline() != null
                    && LocalDateTime.now(ZoneOffset.UTC).isAfter(order.pickupDeadline())) {
                throw new ServiceException.BusinessRule("ORDER_EXPIRED",
                        "Đơn đã quá hạn nhận (deadline: " + order.pickupDeadline() + ")");
            }

            if (!orderDao.updateStatusGuarded(conn, order.id(), ConcessionOrder.STATUS_FULFILLED,
                    ConcessionOrder.STATUS_READY_FOR_PICKUP, null, LocalDateTime.now(ZoneOffset.UTC))) {
                throw new ServiceException.Conflict("Đơn vừa được xử lý bởi một yêu cầu khác");
            }
            orderDao.markFulfilledByStaff(conn, order.id(), actorStaffId);

            logger.info("Fulfilled concession order " + order.orderCode() + " by staff " + actorStaffId);
            audit.record(conn, actorStaffId, "FULFILL_CONCESSION_ORDER", "CONCESSION_ORDER", order.id(),
                    "{\"status\":\"READY_FOR_PICKUP\"}", "{\"status\":\"FULFILLED\"}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return loadOrderWithLines(conn, order.id());
        });
    }

    /**
     * Cascade khi vé hủy trước pickup: hủy pre-order chưa nhận → refund + hoàn kho (Req 21.6).
     */
    public List<ConcessionOrder> cascadeTicketCancel(long ticketId) throws Exception {
        return tx.execute(conn -> cascadeTicketCancelInTx(conn, ticketId));
    }

    /**
     * Tổng tiền các đơn F&B PENDING gắn vé — dùng gộp vào tổng thanh toán của vé (Req 21.1).
     * Gọi trong transaction của BookingService khi confirm booking.
     */
    public long pendingTotalForTicket(Connection conn, long ticketId) throws Exception {
        return orderDao.sumPendingTotalByTicket(conn, ticketId);
    }

    /**
     * Xác nhận các đơn F&B PENDING gắn vé ngay trong transaction confirm vé (Req 21.1, 21.3, 21.8):
     * trừ tồn kho guarded (thiếu hàng → ném lỗi, rollback cả vé lẫn F&B), chuyển
     * READY_FOR_PICKUP, cộng điểm. Trả về danh sách đơn đã xác nhận.
     *
     * <p>Thiết kế: F&B và vé trả tiền cùng một lần — thất bại tồn kho phải làm hỏng
     * cả giao dịch để không thu tiền vé mà không giao được bắp nước.
     */
    public List<ConcessionOrder> confirmOrdersForTicket(Connection conn, long ticketId,
                                                        Long userId, String refType) throws Exception {
        List<Long> orderIds = orderDao.findIdsByTicketLocked(conn, ticketId,
                List.of(ConcessionOrder.STATUS_PENDING));
        List<ConcessionOrder> confirmed = new ArrayList<>();

        for (Long orderId : orderIds) {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn bắp nước không tồn tại"));
            if (!ConcessionOrder.STATUS_PENDING.equals(order.status())) continue;

            deductOrderInventory(conn, order.branchId(), orderDao.findLines(conn, orderId), userId, order.orderCode());

            if (!orderDao.updateStatusGuarded(conn, orderId,
                    ConcessionOrder.STATUS_READY_FOR_PICKUP, ConcessionOrder.STATUS_PENDING,
                    null, null)) {
                throw new ServiceException.Conflict(
                        "Đơn bắp nước vừa được xử lý bởi một yêu cầu khác");
            }

            logger.info("Confirmed preorder " + order.orderCode()
                    + " together with ticket " + ticketId);
            confirmed.add(order);
        }
        return confirmed;
    }

    /**
     * Cascade hủy pre-order trong transaction của caller (Req 21.6) — BookingService
     * gọi ngay sau khi vé chuyển CANCELLED để việc hoàn tiền/hoàn kho nguyên tử với hủy vé.
     * Hủy cả đơn PENDING (chưa thanh toán, chỉ giải phóng) và READY_FOR_PICKUP (refund + hoàn kho).
     */
    public List<ConcessionOrder> cascadeTicketCancelInTx(Connection conn, long ticketId) throws Exception {
        List<Long> orderIds = orderDao.findIdsByTicketLocked(conn, ticketId,
                List.of(ConcessionOrder.STATUS_PENDING, ConcessionOrder.STATUS_READY_FOR_PICKUP));
        List<ConcessionOrder> cancelled = new ArrayList<>();

        for (Long orderId : orderIds) {
            ConcessionOrder order = orderDao.findByIdLocked(conn, orderId).orElse(null);
            if (order == null) continue;

            boolean wasPaid = ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status());
            if (!wasPaid && !ConcessionOrder.STATUS_PENDING.equals(order.status())) continue;

            if (wasPaid) {
                // Đơn đã thanh toán → hoàn kho + hoàn tiền + trừ điểm (Req 21.6)
                restoreOrderInventory(conn, order.branchId(), orderDao.findLines(conn, orderId), order.userId(), order.orderCode());
            }
            // Đơn PENDING chưa trừ kho → chỉ chuyển trạng thái, không hoàn kho

            if (!orderDao.updateStatusGuarded(conn, orderId, ConcessionOrder.STATUS_CANCELLED,
                    order.status(), "Vé hủy — hủy pre-order gắn vé", null)) {
                throw new ServiceException.Conflict(
                        "Đơn bắp nước vừa được cập nhật bởi một yêu cầu khác");
            }
            if (wasPaid) paymentDao.markConcessionPaymentsRefunded(conn, orderId);
            logger.info("Cascade cancelled concession order " + order.orderCode()
                    + " (ticket " + ticketId + " cancelled)");
            cancelled.add(order);
        }
        return cancelled;
    }



    /**
     * Chưa đến nhận quá hạn (Req 21.9) và đơn PENDING quá hạn (Req 20.6, 21.7):
     * PENDING → CANCELLED, READY_FOR_PICKUP quá deadline → EXPIRED_NO_SHOW
     * (không hoàn tiền theo chính sách mặc định). Scheduler 11.1 gọi định kỳ.
     *
     * @return số đơn đã chuyển trạng thái
     */
    public int expireElapsed() throws Exception {
        return tx.execute(conn -> {
            // PENDING quá OFFLINE_PENDING_MINUTES (20.6, 21.7) — orderDao tự quản PENDING minutes
            List<Long> pendingIds = orderDao.findExpiredPendingOrderIds(conn, OFFLINE_PENDING_MINUTES);
            int expired = 0;
            for (Long orderId : pendingIds) {
                ConcessionOrder order = orderDao.findByIdLocked(conn, orderId).orElse(null);
                if (order == null || !ConcessionOrder.STATUS_PENDING.equals(order.status())) continue;
                if (orderDao.updateStatusGuarded(conn, orderId, ConcessionOrder.STATUS_CANCELLED,
                        ConcessionOrder.STATUS_PENDING, "Quá hạn chờ thanh toán", null)) {
                    expired++;
                }
            }

            // READY_FOR_PICKUP quá pickup_deadline → EXPIRED_NO_SHOW (Req 21.9)
            String sweepSql = """
                UPDATE dbo.concession_order
                SET status = 'EXPIRED_NO_SHOW', version = version + 1
                WHERE status = 'READY_FOR_PICKUP'
                  AND pickup_deadline IS NOT NULL
                  AND pickup_deadline <= SYSUTCDATETIME()
                """;
            try (PreparedStatement ps = conn.prepareStatement(sweepSql)) {
                expired += ps.executeUpdate();
            }

            if (expired > 0) logger.info("Expired " + expired + " concession order(s)");
            return expired;
        });
    }

    // ========== Internals ==========

    private void validateProductCreate(String name, long price, String type) {
        if (name == null || name.isBlank()) {
            throw new ServiceException.Validation("Tên sản phẩm là bắt buộc");
        }
        if (price < 0) {
            throw new ServiceException.Validation("Giá bán không được âm");
        }
        if (type == null || !VALID_TYPES.contains(type.trim().toUpperCase())) {
            throw new ServiceException.Validation(
                    "Loại sản phẩm phải là POPCORN, DRINK, COMBO hoặc OTHER");
        }
    }

    private void validateOrderLines(List<OrderLineInput> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException.Validation("Đơn phải có ít nhất một sản phẩm (Req 20.1)");
        }
        for (OrderLineInput line : lines) {
            if (line.qty() <= 0) {
                throw new ServiceException.Validation("Số lượng phải là số nguyên dương");
            }
        }
    }

    /** Req 20.1, 20.2 — cây sản phẩm ACTIVE tại chi nhánh, tồn kho đủ (báo số lượng tối đa). */
    private void requireBranchActiveProduct(List<OrderLineInput> lines, long branchId) throws Exception {
        for (OrderLineInput line : lines) {
            Product product = productDao.findById(line.productId())
                    .orElseThrow(() -> new ServiceException.NotFound("Sản phẩm " + line.productId() + " không tồn tại"));
            if (!product.isActive()) {
                throw new ServiceException.BusinessRule("PRODUCT_INACTIVE",
                        "Sản phẩm " + product.name() + " đã ngừng kinh doanh");
            }
        }
    }

    private void requireAvailableInventory(Connection conn, long branchId, List<ConcessionOrder.OrderLine> lines) throws Exception {
        for (InventoryRequirement requirement : inventoryRequirements(conn, lines)) {
            int currentQty = inventoryDao.findQty(branchId, requirement.productId());
            if (currentQty < requirement.qty()) {
                throw new ServiceException.BusinessRule("INVENTORY_INSUFFICIENT",
                        "Sản phẩm " + requirement.name() + " không đủ tồn kho tại chi nhánh"
                                + " (hiện còn " + currentQty + ", yêu cầu " + requirement.qty() + ")");
            }
        }
    }

    private void requireAvailableInventory(long branchId, List<ConcessionOrder.OrderLine> lines) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            requireAvailableInventory(conn, branchId, lines);
        }
    }

    private void requireBranchScope(Long actorBranchScope, long branchId) {
        if (actorBranchScope != null && actorBranchScope != branchId) {
            throw new ServiceException.Forbidden(
                    "Vi phạm phạm vi chi nhánh: không thể thao tác đơn bắp nước của chi nhánh khác");
        }
    }

    private void requireActiveBranch(long branchId) throws Exception {
        var branch = branchDao.findById(branchId)
                .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
        if (!branch.isActive()) {
            throw new ServiceException.BusinessRule("BRANCH_INACTIVE",
                    "Chi nhánh đã ngừng hoạt động");
        }
    }

    private long requireTicketShowtime(Connection conn, Long ticketId, long branchId) throws Exception {
        String sql = "SELECT showtime_id, branch_id FROM dbo.ticket WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException.NotFound("Vé không tồn tại");
                if (rs.getLong("branch_id") != branchId) {
                    throw new ServiceException.Forbidden("Vé thuộc chi nhánh khác");
                }
                return rs.getLong("showtime_id");
            }
        }
    }

    private long requireTicketShowtime(Long ticketId, long branchId) throws Exception {
        try (Connection conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
            return requireTicketShowtime(conn, ticketId, branchId);
        }
    }

    private record TicketLike(long branchId, long showtimeId) { }

    private TicketLike requirePreorderTicket(long ticketId, Long actorUserId) throws Exception {
        String sql = "SELECT branch_id, showtime_id, user_id, status FROM dbo.ticket WHERE id = ?";
        try (Connection conn = com.cinema.infrastructure.database.DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException.NotFound("Vé không tồn tại");
                String status = rs.getString("status");
                // Req 21.1 — thêm F&B cùng booking (vé PENDING trong hold);
                // Req 21.2 — pre-order sau khi vé đã CONFIRMED
                if (!"PENDING".equals(status) && !"CONFIRMED".equals(status)) {
                    throw new ServiceException.BusinessRule("TICKET_NOT_BOOKABLE",
                            "Chỉ vé đang chờ thanh toán hoặc đã xác nhận mới được đặt bắp nước kèm");
                }
                long ownerId = rs.getLong("user_id");
                if (!rs.wasNull() && !Long.valueOf(ownerId).equals(actorUserId)) {
                    throw new ServiceException.Forbidden("Chỉ chủ vé mới được pre-order");
                }
                return new TicketLike(rs.getLong("branch_id"), rs.getLong("showtime_id"));
            }
        }
    }

    private void requirePreorderCutoff(Showtime showtime) throws Exception {
        Showtime s = showtime == null ? null : showtime;
        if (s != null && s.startTime() != null) {
            if (LocalDateTime.now(ZoneOffset.UTC).plusMinutes(PREORDER_CUTOFF_MINUTES).isAfter(s.startTime())) {
                throw new ServiceException.BusinessRule("PREORDER_CUTOFF",
                        "Pre-order bắp nước phải đặt trước giờ chiếu tối thiểu "
                                + PREORDER_CUTOFF_MINUTES + " phút");
            }
            if (LocalDateTime.now(ZoneOffset.UTC).isAfter(s.endTime())) {
                throw new ServiceException.BusinessRule("SHOWTIME_ENDED",
                        "Suất chiếu đã kết thúc — không thể pre-order");
            }
        }
    }

    private List<ConcessionOrder.OrderLine> priceLines(List<OrderLineInput> inputs) throws Exception {
        List<ConcessionOrder.OrderLine> lines = new ArrayList<>();
        for (OrderLineInput input : inputs) {
            Product product = productDao.findById(input.productId())
                    .orElseThrow(() -> new ServiceException.NotFound(
                            "Sản phẩm " + input.productId() + " không tồn tại"));
            lines.add(new ConcessionOrder.OrderLine(product.id(), product.name(),
                    input.qty(), product.price()));
        }
        return lines;
    }

    private long totalAmount(List<ConcessionOrder.OrderLine> lines) {
        long total = 0;
        for (ConcessionOrder.OrderLine line : lines) total += line.lineTotal();
        return total;
    }

    private ConcessionOrder loadOrderWithLines(Connection conn, long orderId) throws Exception {
        ConcessionOrder order = orderDao.findById(conn, orderId)
                .orElseThrow(() -> new ServiceException.NotFound("Không tải lại được đơn bắp nước"));
        order.setLines(orderDao.findLines(conn, orderId));
        return order;
    }

    private Long effectiveUserId(ConcessionOrder order, Long counterCustomerId) {
        if (counterCustomerId != null) return counterCustomerId;
        return order.userId();
    }

    private int estimatePoints(long amount) throws Exception {
        // Ước lượng điểm dựa trên tỷ lệ tier hiện hành (loyalty default 1%/10k)
        return (int) (amount / 10000);
    }

    private LocalDateTime calculatePickupDeadline(Showtime showtime) {
        return showtime != null && showtime.endTime() != null ? showtime.endTime() : null;
    }

    private String generateOrderCode() {
        return "CON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Pickup code duy nhất 8 ký tự (UNIQUE constraint chốt chặn). */
    private String generatePickupCode() {
        return "PK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** Input form-line: productId + qty (frontend gửi danh sách). */
    public record OrderLineInput(long productId, int qty) { }
}
