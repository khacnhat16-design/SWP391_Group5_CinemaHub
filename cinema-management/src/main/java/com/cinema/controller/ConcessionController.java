package com.cinema.controller;

import com.cinema.dao.auth.UserDAO;
import com.cinema.common.FormParameters;
import com.cinema.dao.payment.PaymentDAO;
import com.cinema.model.branch.Branch;
import com.cinema.model.payment.VnPayConfig;
import com.cinema.service.payment.MockGatewayProvider;
import com.cinema.service.payment.VnPayProvider;
import com.cinema.util.TransactionTemplate;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.model.concession.ConcessionOrder;
import com.cinema.model.concession.ConcessionRefundRequest;
import com.cinema.dao.concession.ConcessionOrderDAO;
import com.cinema.dao.concession.ConcessionRefundRequestDAO;
import com.cinema.service.concession.ConcessionService;
import com.cinema.service.concession.ConcessionRefundService;
import com.cinema.dao.concession.InventoryDAO;
import com.cinema.dao.concession.ProductDAO;
import com.cinema.model.concession.Product;
import com.cinema.filter.AuthFilter;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.service.notification.NotificationService;
import com.cinema.dao.notification.NotificationDAO;
import com.cinema.model.payment.Payment;
import com.cinema.service.payment.PaymentService;
import com.cinema.dao.screen.ScreenDAO;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.service.showtime.ShowtimeService;
import com.cinema.infrastructure.database.DBContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Optional;

/**
 * REST API controller cho danh mục F&B, POS offline và pre-order online (Req 19-21).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /concession/products — Admin: mọi trạng thái; Manager/Staff/Customer: ACTIVE.</li>
 *   <li>POST /concession/products — Admin tạo sản phẩm/combo (Req 19.1, 19.2).</li>
 *   <li>PUT /concession/products/{id} — Admin đổi giá / deactivate (Req 19.3, 19.8).</li>
 *   <li>POST /concession/order — Staff tạo đơn POS (Req 20.1); Customer tạo pre-order (Req 21.1, 21.2).</li>
 *   <li>POST /concession/order/pay — Staff confirm POS (Req 20.3) / Customer confirm pre-order (Req 21.3).</li>
 *   <li>POST /concession/order/cancel — Manager hủy/hoàn đơn confirmed (Req 20.5).</li>
 *   <li>POST /concession/refunds — Customer yêu cầu hoàn 100% đơn F&B.</li>
 *   <li>GET /concession/refunds/mine, GET /concession/refunds/console — Xem yêu cầu hoàn F&B.</li>
 *   <li>POST /concession/refunds/{id}/approve|reject — Manager/Admin xử lý yêu cầu hoàn.</li>
 *   <li>POST /concession/pickup — Staff giao hàng bằng pickup code (Req 21.4, 21.5).</li>
 *   <li>GET /concession/orders/mine — Customer xem đơn của mình (Req 16.5).</li>
 * </ul>
 */
public class ConcessionController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(ConcessionController.class.getName());
    private ConcessionService concessionService;
    private ConcessionOrderDAO orderDao;
    private ConcessionRefundService refundService;
    private PaymentService paymentService;
    private NotificationService notificationService;

    @Override
    public void init() throws ServletException {
        ShowtimeService showtimeService = new ShowtimeService(new ShowtimeDAO(),
                new ScreenDAO(), new MovieDAO(), new BranchDAO());
        this.orderDao = new ConcessionOrderDAO();
        this.concessionService = new ConcessionService(new ProductDAO(), new InventoryDAO(),
                orderDao, new BranchDAO(), showtimeService);

        // Khởi tạo PaymentService để checkout F&B online có thể tạo session VNPay và
        // burn điểm cho khách hàng.
        com.cinema.dao.payment.PaymentDAO paymentDao = new com.cinema.dao.payment.PaymentDAO();
        com.cinema.model.payment.VnPayConfig vnpConfig = com.cinema.model.payment.VnPayConfig.load();
        com.cinema.service.payment.VnPayProvider vnPayProvider = new com.cinema.service.payment.VnPayProvider(vnpConfig);
        com.cinema.service.payment.MockGatewayProvider mockGateway = new com.cinema.service.payment.MockGatewayProvider("unused", "");
        this.paymentService = new com.cinema.service.payment.PaymentService(paymentDao,
                mockGateway, "CinemaHub demo account", vnPayProvider);
        this.paymentService.setConcessionService(this.concessionService);

        this.notificationService = new NotificationService(new NotificationDAO());
        this.refundService = new ConcessionRefundService(new ConcessionRefundRequestDAO(),
                orderDao, paymentDao, notificationService,
                concessionService);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();

            if ("/products".equals(pathInfo)) {
                ProductDAO dao = new ProductDAO();
                // Ai co PRODUCT_MANAGE (Admin hoac Manager) duoc xem ca INACTIVE de quan ly.
                boolean canManage = scope != null && scope.can("PRODUCT_MANAGE");
                boolean activeOnly = scope == null || scope.isGuest() || !canManage;
                List<Product> list = dao.findAll(activeOnly);

                Long branchId = null;
                if (request.getParameter("branchId") != null && !request.getParameter("branchId").isBlank()) {
                    try { branchId = Long.parseLong(request.getParameter("branchId").trim()); } catch (Exception ignored) {}
                } else if (scope != null && !scope.isGuest() && scope.branchIds() != null && !scope.branchIds().isEmpty()) {
                    branchId = scope.branchIds().iterator().next();
                }

                if (branchId != null && branchId > 0) {
                    InventoryDAO invDao = new InventoryDAO();
                    for (Product p : list) {
                        try {
                            if (!p.isCombo()) {
                                p.setStock(invDao.findQty(branchId, p.id()));
                            } else {
                                List<Product.ComboComponent> comps = dao.findComboItems(p.id());
                                if (comps.isEmpty()) {
                                    p.setStock(0);
                                } else {
                                    int minStock = Integer.MAX_VALUE;
                                    for (Product.ComboComponent comp : comps) {
                                        int compQty = comp.qty() > 0 ? comp.qty() : 1;
                                        int available = invDao.findQty(branchId, comp.productId()) / compQty;
                                        if (available < minStock) minStock = available;
                                    }
                                    p.setStock(minStock == Integer.MAX_VALUE ? 0 : Math.max(0, minStock));
                                }
                            }
                        } catch (Exception ignored) {
                            p.setStock(0);
                        }
                    }
                }

                sendOk(response, list);
                return;
            }

            if ("/refunds/mine".equals(pathInfo)) {
                if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ khách hàng mới được xem yêu cầu hoàn tiền F&B");
                    return;
                }
                Long userId = sessionUserId(request);
                if (userId == null) {
                    sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                    return;
                }
                sendOk(response, Map.of("items", refundService.listMine(userId).stream()
                        .map(this::refundRequestJson).toList()));
                return;
            }
            if ("/refunds/console".equals(pathInfo)) {
                if (scope == null || (scope.role() != Role.BRANCH_MANAGER && scope.role() != Role.ADMIN)) {
                    sendForbidden(response, "Chỉ Quản lý chi nhánh hoặc Admin được xử lý hoàn tiền F&B");
                    return;
                }
                List<Long> branchIds = scope.role() == Role.ADMIN ? null : new ArrayList<>(scope.branchIds());
                if (scope.role() == Role.ADMIN && request.getParameter("branchId") != null
                        && !request.getParameter("branchId").isBlank()) {
                    try {
                        long branchId = Long.parseLong(request.getParameter("branchId").trim());
                        if (branchId <= 0) throw new NumberFormatException();
                        branchIds = List.of(branchId);
                    } catch (NumberFormatException e) {
                        throw new ServiceException.Validation("Mã chi nhánh không hợp lệ");
                    }
                }
                sendOk(response, Map.of("items", refundService.listForConsole(
                        branchIds, trimOrNull(request.getParameter("status"))).stream()
                        .map(this::refundRequestJson).toList()));
                return;
            }

            // GET /concession/orders — staff order list with filters
            if ("/orders".equals(pathInfo)) {
                handleGetStaffOrders(scope, request, response);
                return;
            }

            if (pathInfo.startsWith("/orders/")) {
                // GET /concession/orders/{id} — order detail
                String idStr = pathInfo.substring("/orders/".length());
                if (idStr.isEmpty() || idStr.equals("mine")) {
                    // Fallthrough to /orders/mine handler
                } else {
                    try {
                        long orderId = Long.parseLong(idStr);
                        var orderOpt = orderDao.findById(orderId);
                        if (orderOpt == null || orderOpt.isEmpty()) {
                            sendError(response, 404, "NOT_FOUND", "Không tìm thấy đơn hàng");
                            return;
                        }
                        var order = orderOpt.get();
                        if (scope == null || scope.isGuest()) {
                            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                            return;
                        }
                        if (scope.role() == Role.CUSTOMER) {
                            Long customerId = sessionUserId(request);
                            if (customerId == null || order.userId() == null
                                    || !customerId.equals(order.userId())) {
                                sendError(response, 403, "FORBIDDEN",
                                        "Bạn không có quyền xem đơn hàng này");
                                return;
                            }
                        } else if (scope.role() != Role.ADMIN
                                && !scope.branchIds().contains(order.branchId())) {
                            sendError(response, 403, "FORBIDDEN",
                                    "Bạn không có quyền xem đơn hàng ngoài chi nhánh được phân công");
                            return;
                        }
                        // Trả về đầy đủ thông tin đơn + lines + payment cho UI "Chi tiết đơn"
                        // của customer (status page), khác với handleScan chỉ trả cho staff.
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("id", order.id());
                        detail.put("orderCode", order.orderCode());
                        detail.put("pickupCode", order.pickupCode());
                        detail.put("status", order.status());
                        detail.put("totalAmount", order.totalAmount());
                        detail.put("pointsUsed", order.pointsUsed());
                        detail.put("pointsDiscount", order.pointsDiscount());
                        detail.put("amountAfterPointsDiscount", order.amountAfterPointsDiscount());
                        detail.put("createdAt", order.createdAt());
                        detail.put("fulfilledAt", order.fulfilledAt());
                        detail.put("fulfilledByStaffId", order.fulfilledByStaffId());
                        if (order.cancelReason() != null && !order.cancelReason().isEmpty()) {
                            detail.put("cancelledAt", order.createdAt()); // cancelledAt not tracked separately, use createdAt
                        }
                        detail.put("cancelReason", order.cancelReason());
                        detail.put("userId", order.userId());
                        detail.put("ticketId", order.ticketId());
                        detail.put("branchId", order.branchId());
                        detail.put("showtimeId", order.showtimeId());
                        if (order.branchId() > 0) {
                            try {
                                var branchOpt = new BranchDAO().findById(order.branchId());
                                if (branchOpt != null && branchOpt.isPresent()) {
                                    detail.put("branchName", branchOpt.get().name());
                                }
                            } catch (Exception ignored) {}
                        }
                        // Lines + tổng phụ để hiển thị trong trang chi tiết đơn
                        // (khách hàng cần thấy cụ thể từng món và giá từng món).
                        try (var conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
                            var lines = orderDao.findLines(conn, orderId);
                            List<Map<String, Object>> linesList = new ArrayList<>();
                            for (var line : lines) {
                                Map<String, Object> lm = new HashMap<>();
                                lm.put("productId", line.productId());
                                lm.put("productName", line.productName());
                                lm.put("qty", line.qty());
                                lm.put("unitPrice", line.unitPrice());
                                lm.put("subtotal", line.lineTotal());
                                linesList.add(lm);
                            }
                            detail.put("lines", linesList);
                        }
                        sendOk(response, detail);
                    } catch (NumberFormatException e) {
                        sendError(response, 400, "BAD_REQUEST", "ID đơn hàng không hợp lệ");
                    }
                    return;
                }
            }
            if ("/orders/mine".equals(pathInfo)) {
                if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ Customer được xem đơn bắp nước của mình");
                    return;
                }
                Long userId = sessionUserId(request);
                if (userId == null) {
                    sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                    return;
                }
                // Req 16.5/16.6 — chỉ đơn của chính tài khoản trong session
                sendOk(response, orderDao.findByUser(userId));
                return;
            }
            // GET /concession/order/{id}/payment-status — Kiểm tra trạng thái thanh toán VNPay
            if (pathInfo.matches("/order/\\d+/payment-status")) {
                handleGetPaymentStatus(scope, request, response);
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Đường dẫn không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    /**
     * GET /concession/orders — Staff order list with pagination and filters.
     * Staff: force branchId = session scope
     * Admin: allow any branchId
     */
    private void handleGetStaffOrders(AccessScope scope, HttpServletRequest request,
                                      HttpServletResponse response) throws Exception {
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        boolean isAdmin = scope.role() == Role.ADMIN;
        boolean isStaff = scope.role() == Role.BRANCH_STAFF || scope.role() == Role.BRANCH_MANAGER;
        if (!isAdmin && !isStaff) {
            sendForbidden(response, "Chỉ nhân viên hoặc quản lý mới được xem danh sách đơn F&B");
            return;
        }

        // Admin có thể xem mọi chi nhánh; nếu truyền branchId thì lọc theo đó. Staff/Manager
        // bị giới hạn theo AccessScope.branchIds() — không có branch nào là lỗi cấu hình.
        Long branchId = null;
        String branchParam = request.getParameter("branchId");
        if (isAdmin && branchParam != null && !branchParam.isBlank()) {
            branchId = Long.parseLong(branchParam.trim());
        } else if (isStaff) {
            if (scope.branchIds().isEmpty()) {
                sendForbidden(response, "Tài khoản chưa được gán chi nhánh");
                return;
            }
            branchId = scope.branchIds().iterator().next();
        }

        // Chuẩn hoá "" → null để DAO chỉ filter khi client truyền giá trị thật;
        // tránh SQL nhận chuỗi rỗng rồi trả về 0 kết quả.
        String status = request.getParameter("status");
        if (status != null && status.isBlank()) status = null;

        LocalDate fromDate = null;
        String fromParam = request.getParameter("from");
        if (fromParam != null && !fromParam.isBlank()) {
            fromDate = LocalDate.parse(fromParam.trim());
        }

        LocalDate toDate = null;
        String toParam = request.getParameter("to");
        if (toParam != null && !toParam.isBlank()) {
            toDate = LocalDate.parse(toParam.trim());
        }

        String searchText = request.getParameter("q");
        if (searchText != null && searchText.isBlank()) searchText = null;

        Long productId = null;
        String productParam = request.getParameter("productId");
        if (productParam != null && !productParam.isBlank()) {
            productId = Long.parseLong(productParam.trim());
        }

        int offset = 0;
        String offsetParam = request.getParameter("offset");
        if (offsetParam != null && !offsetParam.isBlank()) {
            offset = Integer.parseInt(offsetParam.trim());
        }

        int limit = 20;
        String limitParam = request.getParameter("limit");
        if (limitParam != null && !limitParam.isBlank()) {
            limit = Integer.parseInt(limitParam.trim());
            if (limit <= 0) limit = 20;
            if (limit > 100) limit = 100;
        }

        String sortBy = request.getParameter("sortBy");
        String sortDir = request.getParameter("sortDir");

        var query = new ConcessionOrderDAO.StaffOrderQuery(
                branchId, status, fromDate, toDate, searchText, productId,
                offset, limit, sortBy, sortDir);

        List<ConcessionOrder> orders = orderDao.searchStaffOrders(query);
        int total = orderDao.countStaffOrders(query);

        Map<String, Object> result = new HashMap<>();
        result.put("items", orders);
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        sendOk(response, result);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            switch (path) {
                case "/products" -> createProduct(scope, request, response);
                case "/order" -> createOrder(scope, request, response);
                case "/order/pay" -> payOrder(scope, request, response);
                case "/order/cancel" -> cancelOrder(scope, request, response);
                case "/refunds" -> openFnbRefundRequest(scope, request, response);
                case "/pickup" -> pickup(scope, request, response);
                case "/scan" -> handleScan(scope, request, response);
                case "/redeem" -> handleRedeem(scope, request, response);
                default -> {
                    // POST /concession/order/{id}/pay-vnpay
                    if (path.matches("/order/\\d+/pay-vnpay")) {
                        handlePayVnPay(scope, request, response);
                    } else if (path.matches("/refunds/\\d+/(approve|reject)")) {
                        decideFnbRefundRequest(scope, request, response);
                    } else {
                        sendError(response, 400, "BAD_REQUEST", "Endpoint không tồn tại");
                    }
                }
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            // Quan ly danh muc F&B (CRUD san pham/combo) — Admin HOAC Manager (PRODUCT_MANAGE).
            if (scope == null || !scope.can("PRODUCT_MANAGE")) {
                sendForbidden(response, "Bạn không có quyền quản lý danh mục F&B");
                return;
            }
            // pathInfo có dạng "/products/{id}" với servlet mapping /concession/*
            // → không thể Long.parseLong(toàn bộ substring(1)) vì sẽ ném NumberFormatException.
            // Tách đúng: lấy phần sau "/products/".
            String pathInfo = request.getPathInfo();
            long productId;
            if (pathInfo != null && pathInfo.startsWith("/products/")) {
                String idStr = pathInfo.substring("/products/".length());
                productId = Long.parseLong(idStr);
            } else if (pathInfo != null && pathInfo.startsWith("/")) {
                // Backward-compat: nếu trước đây pathInfo chỉ là "/{id}".
                productId = Long.parseLong(pathInfo.substring(1));
            } else {
                sendError(response, 400, "BAD_REQUEST", "Thiếu ID sản phẩm");
                return;
            }
            Map<String, String> formParams = FormParameters.readPut(request);
            String action = formParams.get("action");
            if (action == null || action.isBlank()) {
                action = request.getParameter("action");
            }

            if ("deactivate".equals(action)) {
                sendOk(response, concessionService.deactivateProduct(productId));
            } else if ("activate".equals(action)) {
                sendOk(response, concessionService.activateProduct(productId));
            } else {
                String name = formParams.get("name");
                if (name == null || name.isBlank()) {
                    name = request.getParameter("name");
                }
                String priceRaw = formParams.get("price");
                if (priceRaw == null || priceRaw.isBlank()) {
                    priceRaw = request.getParameter("price");
                }
                if (priceRaw == null || priceRaw.isBlank()) {
                    throw new ServiceException.Validation("Thiếu tham số: price");
                }
                long price = Long.parseLong(priceRaw.trim());

                if (name == null || name.isBlank()) {
                    sendOk(response, concessionService.updatePrice(productId, price));
                } else {
                    String desc = formParams.get("description");
                    if (desc == null) desc = request.getParameter("description");
                    String unit = formParams.get("unit");
                    if (unit == null) unit = request.getParameter("unit");
                    String type = formParams.get("type");
                    if (type == null) type = request.getParameter("type");
                    if (type == null || type.isBlank()) {
                        throw new ServiceException.Validation("Thiếu tham số: type");
                    }
                    sendOk(response, concessionService.updateProduct(productId, name,
                            desc, price, unit, type.trim()));
                }
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    /** Req 19.1, 19.2 — Admin hoặc Manager (PRODUCT_MANAGE) tạo sản phẩm/combo. */
    private void createProduct(AccessScope scope, HttpServletRequest request,
                               HttpServletResponse response) throws Exception {
        if (scope == null || !scope.can("PRODUCT_MANAGE")) {
            sendForbidden(response, "Bạn không có quyền tạo sản phẩm F&B");
            return;
        }
        long actorId = requireSessionUserId(request);
        String name = requireParam(request, "name");
        String description = request.getParameter("description");
        long price = Long.parseLong(requireParam(request, "price"));
        String unit = request.getParameter("unit");
        String type = requireParam(request, "type");

        if (isComboType(type)) {
            String compRaw = request.getParameter("components");
            List<Map<String, Object>> components = (compRaw != null && !compRaw.isBlank())
                    ? parseComboComponents(compRaw)
                    : List.of();
            if (components.isEmpty()) {
                sendOk(response, concessionService.createProduct(name, description, price, unit, Product.TYPE_COMBO, actorId));
            } else {
                sendOk(response, concessionService.createCombo(name, description, price, unit,
                        components, actorId));
            }
        } else {
            sendOk(response, concessionService.createProduct(name, description, price, unit,
                    type, actorId));
        }
    }

    /** Req 20.1 (Staff POS) hoặc Req 21.1/21.2 (Customer pre-order gắn ticketId). */
    private void createOrder(AccessScope scope, HttpServletRequest request,
                             HttpServletResponse response) throws Exception {
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        List<ConcessionService.OrderLineInput> lines = parseOrderLines(
                requireParam(request, "lines"));
        String ticketIdRaw = request.getParameter("ticketId");

        if (ticketIdRaw != null && !ticketIdRaw.isBlank()) {
            // Pre-order online gắn vé (Req 21.1, 21.2) — Customer
            if (scope.role() != Role.CUSTOMER) {
                sendForbidden(response, "Pre-order online chỉ dành cho Customer");
                return;
            }
            Long userId = sessionUserId(request);
            if (userId == null) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
            // Điểm loyalty có thể được burn tại pre-order checkout; parsePointsUsed
            // trả 0 khi thiếu/empty để service hiểu là không dùng điểm.
            int pointsUsed = parsePointsUsed(request);
            ConcessionOrder order = concessionService.createPreorderWithPoints(
                    Long.parseLong(ticketIdRaw), lines, request.getParameter("payMethod"), userId, pointsUsed);
            sendOk(response, order);
        } else if (request.getParameter("branchId") != null && !request.getParameter("branchId").isBlank()) {
            // Đơn F&B online không gắn với vé, chỉ cần biết chi nhánh nhận hàng + customer.
            if (scope.role() != Role.CUSTOMER) {
                sendForbidden(response, "Đặt bắp nước trực tuyến chỉ dành cho Customer");
                return;
            }
            Long userId = sessionUserId(request);
            if (userId == null) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
            long branchId = Long.parseLong(requireParam(request, "branchId"));
            int pointsUsed = parsePointsUsed(request);

            ConcessionOrder order = concessionService.createStandaloneOrder(
                    branchId, lines, userId, pointsUsed);
            sendOk(response, order);
        } else {
            // POS offline (Req 20.1) — Branch Staff/Manager
            if (!scope.can("CONCESSION_SELL")) {
                sendForbidden(response, "Chỉ Branch Staff/Branch Manager mới được bán bắp nước tại quầy");
                return;
            }
            long branchId = requireSingleBranch(scope);
            Long customerId = parseOptionalLong(request.getParameter("customerId"));
            ConcessionOrder order = concessionService.createOfflineOrder(
                    scopeBranchOrNull(scope), branchId, lines,
                    request.getParameter("payMethod"), customerId, null);
            sendOk(response, order);
        }
    }

    /** Req 20.3 (POS) / Req 21.3 (pre-order) — xác nhận thanh toán. */
    private void payOrder(AccessScope scope, HttpServletRequest request,
                          HttpServletResponse response) throws Exception {
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        long orderId = Long.parseLong(requireParam(request, "orderId"));
        String payMethod = request.getParameter("payMethod");

        if (scope.role() == Role.CUSTOMER) {
            Long userId = sessionUserId(request);
            if (userId == null) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
            sendOk(response, concessionService.confirmPreorderPayment(orderId, payMethod, userId));
        } else if (scope.can("CONCESSION_SELL")) {
            Long customerId = parseOptionalLong(request.getParameter("customerId"));
            sendOk(response, concessionService.confirmOfflinePayment(orderId, payMethod,
                    customerId, scopeBranchOrNull(scope), requireSessionUserId(request),
                    request.getParameter("paymentReference")));
        } else {
            sendForbidden(response, "Không có quyền xác nhận thanh toán đơn bắp nước");
        }
    }

    /** Req 20.5 — Manager hủy/hoàn đơn confirmed kèm lý do bắt buộc. */
    private void cancelOrder(AccessScope scope, HttpServletRequest request,
                             HttpServletResponse response) throws Exception {
        if (scope == null || !scope.can("REFUND_APPROVE")) {
            sendForbidden(response, "Chỉ Branch Manager trở lên mới được hoàn tiền đơn bắp nước");
            return;
        }

        long orderId = Long.parseLong(requireParam(request, "orderId"));
        String reason = requireParam(request, "cancelReason");
        long staffBranchId = requireSingleBranch(scope);

        sendOk(response, concessionService.cancelConfirmedOffline(orderId, reason,
                staffBranchId, requireSessionUserId(request), scope.role() == Role.BRANCH_MANAGER
                        || scope.role() == Role.ADMIN));
    }

    private void openFnbRefundRequest(AccessScope scope, HttpServletRequest request,
                                     HttpServletResponse response) throws Exception {
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ khách hàng mới được yêu cầu hoàn tiền F&B");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        ConcessionRefundRequest refund = refundService.openRequest(
                Long.parseLong(requireParam(request, "orderId")), userId,
                requireParam(request, "bankName"), requireParam(request, "bankAccountName"),
                requireParam(request, "bankAccountNumber"), trimOrNull(request.getParameter("reason")));
        sendOk(response, refundRequestJson(refund));
    }

    private void decideFnbRefundRequest(AccessScope scope, HttpServletRequest request,
                                        HttpServletResponse response) throws Exception {
        if (scope == null || (scope.role() != Role.BRANCH_MANAGER && scope.role() != Role.ADMIN)) {
            sendForbidden(response, "Chỉ Quản lý chi nhánh hoặc Admin được xử lý hoàn tiền F&B");
            return;
        }
        String[] parts = request.getPathInfo().split("/");
        long requestId = Long.parseLong(parts[2]);
        boolean approve = "approve".equals(parts[3]);
        Long managerId = sessionUserId(request);
        if (managerId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        Set<Long> branches = scope.role() == Role.ADMIN ? null : scope.branchIds();
        ConcessionRefundRequest refund = refundService.decide(requestId, managerId, branches,
                approve, trimOrNull(request.getParameter("managerNote")));
        sendOk(response, refundRequestJson(refund));
    }

    private Map<String, Object> refundRequestJson(ConcessionRefundRequest request) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", request.id());
        json.put("orderId", request.orderId());
        json.put("userId", request.userId());
        json.put("branchId", request.branchId());
        json.put("orderCode", request.orderCode());
        json.put("refundAmount", request.refundAmount());
        json.put("refundPoints", request.refundPoints());
        json.put("bankName", request.bankName());
        json.put("bankAccountName", request.bankAccountName());
        json.put("bankAccountNumber", request.bankAccountNumber());
        json.put("reason", request.reason());
        json.put("status", request.status());
        json.put("managerNote", request.managerNote());
        json.put("createdAt", request.createdAt());
        json.put("decidedAt", request.decidedAt());
        return json;
    }

    /** Req 21.4, 21.5 — Staff giao hàng một lần bằng pickup code. */
    private void pickup(AccessScope scope, HttpServletRequest request,
                        HttpServletResponse response) throws Exception {
        if (scope == null || !scope.can("CONCESSION_PICKUP")) {
            sendForbidden(response, "Chỉ Branch Staff/Branch Manager mới được giao đơn bắp nước");
            return;
        }
        String pickupCode = requireParam(request, "pickupCode");
        long staffBranchId = requireSingleBranch(scope);

        sendOk(response, concessionService.pickup(pickupCode, staffBranchId,
                requireSessionUserId(request)));
    }

    /**
     * POST /concession/scan — QR/barcode lookup for staff F&B pickup.
     * Tries orderCode first, then pickupCode.
     */
    private void handleScan(AccessScope scope, HttpServletRequest request,
                            HttpServletResponse response) throws Exception {
        if (scope == null || !scope.can("CONCESSION_PICKUP")) {
            sendForbidden(response, "Chỉ Branch Staff/Branch Manager mới được quét mã F&B");
            return;
        }
        long staffBranchId = requireSingleBranch(scope);
        String code = requireParam(request, "code").trim().toUpperCase();

        // Hai loại mã: orderCode (hiển thị cho khách) và pickupCode (nội bộ). Thử
        // orderCode trước vì đó là mã mà khách thường mang đến quầy.
        Optional<ConcessionOrder> orderOpt = orderDao.findByOrderCode(code);
        if (orderOpt.isEmpty()) {
            orderOpt = orderDao.findByPickupCode(code);
        }
        if (orderOpt.isEmpty()) {
            sendError(response, 404, "NOT_FOUND", "Không tìm thấy đơn hàng với mã: " + code);
            return;
        }

        ConcessionOrder order = orderOpt.get();

        // Đơn thuộc chi nhánh khác → staff hiện tại không có quyền thao tác. Trả
        // canRedeem=false + message rõ ràng thay vì 403 để client vẫn render được UI.
        if (scope.role() != Role.ADMIN && order.branchId() != staffBranchId) {
            Map<String, Object> result = new HashMap<>();
            result.put("canRedeem", false);
            result.put("message", "ACCESS DENIED: Đơn không thuộc chi nhánh của bạn");
            sendOk(response, result);
            return;
        }

        // Trả về snapshot đầy đủ để client hiển thị popup: header + lines + payment + redeemability.
        // Thông tin payment được attach riêng để UI biết đã thanh toán hay chưa.
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", order.id());
        result.put("orderCode", order.orderCode());
        result.put("pickupCode", order.pickupCode());
        result.put("status", order.status());
        result.put("totalAmount", order.totalAmount());
        result.put("createdAt", order.createdAt());
        result.put("fulfilledAt", order.fulfilledAt());
        result.put("branchId", order.branchId());

        // Lookup tên/SĐT/email khách để staff có thể verify CCCD khi giao hàng.
        if (order.userId() != null) {
            try {
                var userOpt = new com.cinema.dao.auth.UserDAO().findById(order.userId());
                if (userOpt != null && userOpt.isPresent()) {
                    var user = userOpt.get();
                    result.put("customerName", user.fullName());
                    result.put("customerPhone", user.phone());
                    result.put("customerEmail", user.email());
                }
            } catch (Exception ignored) {}
        }

        try (var conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
            var lines = orderDao.findLines(conn, order.id());
            List<Map<String, Object>> linesList = new ArrayList<>();
            long subtotal = 0;
            for (var line : lines) {
                Map<String, Object> lm = new HashMap<>();
                lm.put("productId", line.productId());
                lm.put("productName", line.productName());
                lm.put("qty", line.qty());
                lm.put("unitPrice", line.unitPrice());
                lm.put("subtotal", line.lineTotal());
                linesList.add(lm);
                subtotal += line.lineTotal();
            }
            result.put("lines", linesList);
            result.put("subtotal", subtotal);
        }

        var paymentStatus = paymentService.getFnbPaymentStatus(order.id());
        result.put("paymentStatus", paymentStatus.status());
        result.put("paymentMethod", paymentStatus.method());

        // READY_FOR_PICKUP chỉ được đặt sau khi luồng thanh toán tương ứng đã xác nhận;
        // VNPAY status không bao gồm các đơn POS thanh toán tiền mặt.
        boolean canRedeem = ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status());
        String message;
        if (order.status() == null) {
            message = "Trạng thái đơn không xác định";
        } else switch (order.status()) {
            case "FULFILLED" -> {
                canRedeem = false;
                message = "Đã hoàn tất nhận hàng lúc " + order.fulfilledAt()
                        + (order.fulfilledByStaffId() == null ? ""
                        : " bởi nhân viên #" + order.fulfilledByStaffId());
            }
            case "CANCELLED" -> {
                canRedeem = false;
                message = "Đơn đã bị hủy: " + (order.cancelReason() != null ? order.cancelReason() : "");
            }
            case "EXPIRED_NO_SHOW" -> {
                canRedeem = false;
                message = "Đơn đã quá hạn nhận hàng";
            }
            case "PENDING" -> {
                canRedeem = false;
                message = "Đơn đang chờ thanh toán, chưa thể giao";
            }
            default -> {
                if (ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())) {
                    message = "Sẵn sàng để giao";
                } else {
                    canRedeem = false;
                    message = "Đơn không ở trạng thái sẵn sàng nhận hàng";
                }
            }
        };

        result.put("canRedeem", canRedeem);
        result.put("message", message);
        sendOk(response, result);
    }

    /**
     * POST /concession/redeem — Staff confirm F&B pickup (idempotent).
     */
    private void handleRedeem(AccessScope scope, HttpServletRequest request,
                              HttpServletResponse response) throws Exception {
        if (scope == null || !scope.can("CONCESSION_PICKUP")) {
            sendForbidden(response, "Chỉ Branch Staff/Branch Manager mới được xác nhận giao đơn F&B");
            return;
        }
        long staffBranchId = requireSingleBranch(scope);
        long staffId = requireSessionUserId(request);
        long orderId = Long.parseLong(requireParam(request, "orderId"));

        // Use existing pickup flow
        var orderOpt = orderDao.findById(orderId);
        if (orderOpt.isEmpty()) {
            sendError(response, 404, "NOT_FOUND", "Không tìm thấy đơn hàng");
            return;
        }
        var order = orderOpt.get();

        if (scope.role() != Role.ADMIN && order.branchId() != staffBranchId) {
            sendError(response, 403, "FORBIDDEN", "Vi phạm phạm vi chi nhánh");
            return;
        }

        // Idempotent: nếu đơn đã FULFILLED thì trả 409 thay vì ghi đè fulfilled_by_staff_id,
        // tránh sai audit log và tránh staff kia nghĩ mình vừa giao thành công.
        if (ConcessionOrder.STATUS_FULFILLED.equals(order.status())) {
            sendError(response, 409, "CONFLICT", "Đơn đã được xác nhận giao trước đó");
            return;
        }

        // Tận dụng pickup() (đã có sẵn) để giữ single-source-of-truth về logic
        // update status + audit + notification. Fallback chỉ dành cho đơn legacy
        // chưa có pickupCode (rất hiếm, thường là dữ liệu migrate).
        ConcessionOrder fulfilled;
        if (order.pickupCode() != null && !order.pickupCode().isBlank()) {
            fulfilled = concessionService.pickup(order.pickupCode(), staffBranchId, staffId);
        } else {
            fulfilled = fulfillOrderDirect(orderId, staffId, staffBranchId);
        }

        // Send notification to customer
        if (fulfilled.userId() != null && fulfilled.userId() > 0) {
            notificationService.notifyFnbFulfilled(
                    fulfilled.userId(), fulfilled.id(), fulfilled.orderCode());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", fulfilled.id());
        result.put("orderCode", fulfilled.orderCode());
        result.put("status", fulfilled.status());
        result.put("fulfilledAt", fulfilled.fulfilledAt());
        result.put("fulfilledByStaffId", staffId);
        sendOk(response, result);
    }

    /** Direct fulfillment when no pickupCode exists (e.g., POS orders). */
    private ConcessionOrder fulfillOrderDirect(long orderId, long staffId, long staffBranchId) throws Exception {
        return new com.cinema.util.TransactionTemplate().execute(conn -> {
            var order = orderDao.findByIdLocked(conn, orderId)
                    .orElseThrow(() -> new ServiceException.NotFound("Đơn không tồn tại"));
            if (!ConcessionOrder.STATUS_READY_FOR_PICKUP.equals(order.status())) {
                if (ConcessionOrder.STATUS_FULFILLED.equals(order.status())) {
                    throw new ServiceException.Conflict("Đơn đã hoàn tất nhận — không thể nhận lại");
                }
                throw new ServiceException.Conflict("Đơn không ở trạng thái chờ nhận");
            }
            if (order.branchId() != staffBranchId) {
                throw new ServiceException.Forbidden("Vi phạm phạm vi chi nhánh");
            }
            if (!orderDao.updateStatusGuarded(conn, orderId, ConcessionOrder.STATUS_FULFILLED,
                    ConcessionOrder.STATUS_READY_FOR_PICKUP, null, LocalDateTime.now(ZoneOffset.UTC))) {
                throw new ServiceException.Conflict("Đơn vừa được xử lý bởi một yêu cầu khác");
            }
            orderDao.markFulfilledByStaff(conn, orderId, staffId);
            return loadOrderWithLines(conn, orderId);
        });
    }

    private ConcessionOrder loadOrderWithLines(java.sql.Connection conn, long orderId) throws Exception {
        ConcessionOrder order = orderDao.findById(conn, orderId)
                .orElseThrow(() -> new ServiceException.NotFound("Không tải lại được đơn bắp nước"));
        order.setLines(orderDao.findLines(conn, orderId));
        return order;
    }

    /** Req 21.x — Tạo phiên thanh toán VNPay cho đơn F&B. */
    private void handlePayVnPay(AccessScope scope, HttpServletRequest request,
                               HttpServletResponse response) throws Exception {
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        if (scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer mới có thể thanh toán VNPay cho đơn bắp nước online");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }

        // Path pattern /order/{id}/pay-vnpay — tách id bằng cách strip prefix/suffix.
        String pathInfo = request.getPathInfo();
        String orderIdStr = pathInfo.replace("/order/", "").replace("/pay-vnpay", "");
        long orderId;
        try {
            orderId = Long.parseLong(orderIdStr.trim());
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "ID đơn hàng không hợp lệ");
            return;
        }

        // IDOR: phải là chủ đơn mới được tạo session thanh toán, ngăn khách A
        // mở URL thanh toán cho đơn của khách B.
        var orderOpt = orderDao.findById(orderId);
        if (orderOpt == null || orderOpt.isEmpty()) {
            sendError(response, 404, "NOT_FOUND", "Không tìm thấy đơn hàng");
            return;
        }
        var order = orderOpt.get();
        if (!userId.equals(order.userId())) {
            sendForbidden(response, "Chỉ chủ đơn mới được thanh toán đơn này");
            return;
        }
        if (!"PENDING".equals(order.status())) {
            sendError(response, 409, "CONFLICT", "Đơn không ở trạng thái chờ thanh toán");
            return;
        }

        // successUrl/cancelUrl cho phép client tuỳ biến redirect sau khi VNPay
        // return — fallback về workspace console nếu không truyền.
        String successUrl = request.getParameter("successUrl");
        String cancelUrl = request.getParameter("cancelUrl");
        if (successUrl == null || successUrl.isBlank()) {
            successUrl = request.getContextPath() + "/console?module=fnb-orders";
        }
        if (cancelUrl == null || cancelUrl.isBlank()) {
            cancelUrl = request.getContextPath() + "/console?module=fnb-checkout";
        }

        // pointsUsed trên URL chỉ là metadata để echo lại client; số điểm thực sự
        // burn đã được snapshotted trong order tại thời điểm createPreorder.
        int pointsUsed = 0;
        try {
            String pointsUsedStr = request.getParameter("pointsUsed");
            if (pointsUsedStr != null && !pointsUsedStr.isBlank()) {
                pointsUsed = Integer.parseInt(pointsUsedStr.trim());
            }
        } catch (NumberFormatException ignored) {}

        // Tạo payment session VNPay: sinh redirectUrl có kèm checksum, ghi payment
        // row với method=VNPAY để IPN callback đối chiếu.
        String baseUrl = request.getScheme() + "://" + request.getServerName()
                + (request.getServerPort() == 80 || request.getServerPort() == 443
                ? "" : ":" + request.getServerPort())
                + request.getContextPath();
        var outcome = paymentService.createFnbPaymentSession(orderId, userId,
                baseUrl + "/vnpay/return", successUrl, cancelUrl);

        // Echo lại pointsUsed + discount để client hiển thị trang chờ thanh toán
        // cho khớp với trang checkout (finalAmount là số tiền thực charge).
        Map<String, Object> result = new HashMap<>();
        result.put("redirectUrl", outcome.redirectUrl());
        result.put("paymentId", outcome.payment().id());
        result.put("amount", outcome.payment().amount());
        result.put("orderId", orderId);
        result.put("pointsUsed", order.pointsUsed());
        result.put("pointsDiscount", order.pointsDiscount());
        result.put("finalAmount", order.amountAfterPointsDiscount());
        result.put("orderCode", order.orderCode());
        sendOk(response, result);
    }

    /** Req 21.x — Lấy trạng thái thanh toán VNPay của đơn F&B. */
    private void handleGetPaymentStatus(AccessScope scope, HttpServletRequest request,
                                       HttpServletResponse response) throws Exception {
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        if (scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer mới kiểm tra được trạng thái thanh toán");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }

        // Tách id từ /order/{id}/payment-status — cùng pattern với pay-vnpay.
        String pathInfo = request.getPathInfo();
        String orderIdStr = pathInfo.replace("/order/", "").replace("/payment-status", "");
        long orderId;
        try {
            orderId = Long.parseLong(orderIdStr.trim());
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "ID đơn hàng không hợp lệ");
            return;
        }

        // IDOR guard: khách A không thể polling trạng thái thanh toán đơn của khách B.
        var orderOpt = orderDao.findById(orderId);
        if (orderOpt == null || orderOpt.isEmpty()) {
            sendError(response, 404, "NOT_FOUND", "Không tìm thấy đơn hàng");
            return;
        }
        var order = orderOpt.get();
        if (!userId.equals(order.userId())) {
            sendForbidden(response, "Chỉ chủ đơn mới kiểm tra được trạng thái");
            return;
        }

        // Đọc payment status thật từ DB (không tin status trong order object vì
        // có thể stale do IPN chưa xử lý xong) để FE render chính xác.
        var paymentStatus = paymentService.getFnbPaymentStatus(orderId);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("orderCode", order.orderCode());
        result.put("orderStatus", order.status());
        result.put("paymentId", paymentStatus.paymentId());
        result.put("paymentStatus", paymentStatus.status());
        result.put("paymentMethod", paymentStatus.method());
        result.put("amount", paymentStatus.amount());
        result.put("txnRef", paymentStatus.txnRef());
        result.put("confirmedAt", paymentStatus.confirmedAt());

        // Trạng thái FE hiển thị phải kết hợp order.status + payment.status vì cùng
        // một trạng thái "READY_FOR_PICKUP" nhưng có thể chưa thanh toán hoặc đã hủy.
        String derivedStatus;
        if ("SUCCESS".equals(paymentStatus.status())) {
            derivedStatus = "SUCCESS";
        } else if ("FAILED".equals(paymentStatus.status())) {
            derivedStatus = "FAILED";
        } else if ("PENDING".equals(paymentStatus.status())) {
            derivedStatus = "PENDING";
        } else if ("NOT_FOUND".equals(paymentStatus.status())) {
            derivedStatus = "NOT_FOUND";
        } else {
            derivedStatus = paymentStatus.status();
        }
        result.put("status", derivedStatus);

        sendOk(response, result);
    }

    // ---- helpers (mirror BookingController patterns) ----

    private boolean isComboType(String type) {
        return "COMBO".equalsIgnoreCase(type == null ? "" : type.trim());
    }

    /** components dạng "productId:qty,productId:qty". */
    private List<Map<String, Object>> parseComboComponents(String raw) {
        List<Map<String, Object>> components = new ArrayList<>();
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) {
                throw new ServiceException.Validation(
                        "Thành phần combo phải có dạng productId:qty");
            }
            Map<String, Object> comp = new HashMap<>();
            comp.put("productId", Long.parseLong(kv[0].trim()));
            comp.put("qty", Integer.parseInt(kv[1].trim()));
            components.add(comp);
        }
        return components;
    }

    /** lines dạng "productId:qty,productId:qty". */
    private List<ConcessionService.OrderLineInput> parseOrderLines(String raw) {
        List<ConcessionService.OrderLineInput> lines = new ArrayList<>();
        for (String part : raw.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) {
                throw new ServiceException.Validation("Dòng đơn phải có dạng productId:qty");
            }
            try {
                long productId = Long.parseLong(kv[0].trim());
                int quantity = Integer.parseInt(kv[1].trim());
                if (productId <= 0 || quantity <= 0) {
                    throw new ServiceException.Validation("Mã sản phẩm và số lượng phải là số nguyên dương");
                }
                lines.add(new ConcessionService.OrderLineInput(productId, quantity));
            } catch (NumberFormatException e) {
                throw new ServiceException.Validation("Mã sản phẩm và số lượng phải là số nguyên hợp lệ");
            }
        }
        if (lines.isEmpty()) {
            throw new ServiceException.Validation("Đơn phải có ít nhất một sản phẩm");
        }
        return lines;
    }

    private Long sessionUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object userId = session.getAttribute("userId");
        return userId instanceof Long value ? value : null;
    }

    private long requireSessionUserId(HttpServletRequest request) {
        Long userId = sessionUserId(request);
        if (userId == null) throw new ServiceException.Unauthorized("Cần đăng nhập");
        return userId;
    }

    private long requireSingleBranch(AccessScope scope) {
        if (scope.branchIds().isEmpty()) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        return scope.branchIds().iterator().next();
    }

    private Long scopeBranchOrNull(AccessScope scope) {
        return scope.role() == Role.ADMIN ? null : requireSingleBranch(scope);
    }

    private String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        return value.trim();
    }

    private String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long parseOptionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
    }

    /** Parse pointsUsed parameter (Phase 3). */
    private int parsePointsUsed(HttpServletRequest request) {
        try {
            String pointsUsedStr = request.getParameter("pointsUsed");
            if (pointsUsedStr != null && !pointsUsedStr.isBlank()) {
                return Integer.parseInt(pointsUsedStr.trim());
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        sendError(response, 403, "FORBIDDEN", message);
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else {
            logger.log(Level.SEVERE, "F&B request failed", e);
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }
}
