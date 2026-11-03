package com.cinema.controller;

import com.cinema.model.notification.Notification;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.model.auth.User;
import com.cinema.dao.auth.UserDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.dao.loyalty.LoyaltyDAO;
import com.cinema.service.loyalty.LoyaltyService;
import com.cinema.model.loyalty.PointLedger;
import com.cinema.model.auth.CustomerSummary;
import com.cinema.service.notification.NotificationService;
import com.cinema.dao.notification.NotificationDAO;
import com.cinema.infrastructure.database.DBContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Staff loyalty operations: point adjustment with audit trail and ledger history.
 * 
 * <p>Endpoints:
 * <ul>
 *   <li>POST /loyalty/adjust — Staff adjust customer points with reason (audit trail)</li>
 *   <li>GET /loyalty/ledger — Staff view point transaction history with pagination</li>
 * </ul>
 */
public class StaffLoyaltyController extends HttpServlet {
    private static final Logger logger = Logger.getLogger(StaffLoyaltyController.class.getName());

    private LoyaltyService loyaltyService;
    private NotificationService notificationService;

    @Override
    public void init() throws ServletException {
        this.loyaltyService = new LoyaltyService(new LoyaltyDAO());
        this.notificationService = new NotificationService(new NotificationDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest()) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }

            String path = request.getPathInfo() == null ? "" : request.getPathInfo();

            // Customer-facing endpoints (Phase 3: Points Redemption)
            if ("/me".equals(path)) {
                handleGetMe(scope, request, response);
                return;
            }
            if ("/ledger/me".equals(path)) {
                handleGetCustomerLedger(scope, request, response);
                return;
            }
            if ("/info".equals(path)) {
                handleGetInfo(scope, request, response);
                return;
            }

            // Staff endpoints (require adjustment permission)
            if (!scope.canAdjustPoints()) {
                sendForbidden(response, "Không có quyền xem thông tin này");
                return;
            }
            if ("/ledger".equals(path)) {
                handleGetLedger(scope, request, response);
                return;
            }
            if ("/customer-points".equals(path)) {
                handleGetCustomerPoints(scope, request, response);
                return;
            }
            if ("/customers".equals(path)) {
                handleListCustomers(scope, request, response);
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Đường dẫn không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // ================================================================
    // Customer-facing endpoints (Phase 3: Points Redemption)
    // ================================================================

    /**
     * GET /loyalty/me — Thông tin loyalty cơ bản của khách hàng đang đăng nhập.
     * Returns: { points, tier, redemptionRate }
     */
    private void handleGetMe(AccessScope scope, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer mới xem được thông tin này");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("points", loyaltyService.findPoints(userId));
        result.put("tier", loyaltyService.summary(userId).tier());
        result.put("redemptionRate", loyaltyService.getRedemptionRate());

        sendOk(response, result);
    }

    /**
     * GET /loyalty/ledger/me?limit=50&offset=0 — Lịch sử giao dịch điểm của khách hàng.
     * Returns: { items: [...], total, limit, offset }
     */
    private void handleGetCustomerLedger(AccessScope scope, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer mới xem được thông tin này");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }

        int limit = 50;
        int offset = 0;
        try {
            String limitStr = request.getParameter("limit");
            String offsetStr = request.getParameter("offset");
            if (limitStr != null && !limitStr.isBlank()) {
                limit = Math.min(Integer.parseInt(limitStr.trim()), 100);
            }
            if (offsetStr != null && !offsetStr.isBlank()) {
                offset = Math.max(Integer.parseInt(offsetStr.trim()), 0);
            }
        } catch (NumberFormatException ignored) {}

        var summary = loyaltyService.summary(userId);
        var ledger = summary.ledger();

        // Cắt subList theo offset/limit nhưng giữ an toàn khi offset > total.
        int total = ledger.size();
        int toIndex = Math.min(offset + limit, total);
        var pageItems = offset < total ? ledger.subList(offset, toIndex) : java.util.Collections.emptyList();

        Map<String, Object> result = new HashMap<>();
        result.put("items", pageItems);
        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);

        sendOk(response, result);
    }

    /**
     * GET /loyalty/info?orderTotal=X — Thông tin loyalty kèm điểm tối đa có thể đổi.
     * Returns: { points, tier, redemptionRate, maxRedeemable }
     */
    private void handleGetInfo(AccessScope scope, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer mới xem được thông tin này");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }

        long orderTotal = 0;
        try {
            String orderTotalStr = request.getParameter("orderTotal");
            if (orderTotalStr != null && !orderTotalStr.isBlank()) {
                orderTotal = Long.parseLong(orderTotalStr.trim());
            }
        } catch (NumberFormatException ignored) {}

        LoyaltyService.LoyaltyInfo info = loyaltyService.getLoyaltyInfo(userId, orderTotal);

        Map<String, Object> result = new HashMap<>();
        result.put("points", info.points());
        result.put("tier", info.tier());
        result.put("redemptionRate", info.redemptionRate());
        result.put("maxRedeemable", info.maxRedeemable());

        sendOk(response, result);
    }

    // ================================================================
    // Staff endpoints (require adjustment permission)
    // ================================================================

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest()) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
            if (!scope.canAdjustPoints()) {
                sendForbidden(response, "Không có quyền điều chỉnh điểm");
                return;
            }

            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            if ("/adjust".equals(path)) {
                handleAdjustPoints(scope, request, response);
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Đường dẫn không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    /**
     * POST /loyalty/adjust — Staff adjust customer points.
     * 
     * Body: { customerId, delta, reason }
     * 
     * Validation:
     * - Only BRANCH_STAFF or BRANCH_MANAGER allowed
     * - reason required, min 5 chars
     * - delta: positive or negative integer, max abs 10000
     * 
     * Returns: { newBalance, ledgerEntry: { id, delta, balanceAfter, createdAt } }
     */
    private void handleAdjustPoints(AccessScope scope, HttpServletRequest request,
                                   HttpServletResponse response) throws Exception {
        // Hỗ trợ cả application/x-www-form-urlencoded (mặc định form) lẫn query
        // string (?customerId=...) để client SPA có thể gọi tuỳ ngữ cảnh.
        String customerIdRaw = request.getParameter("customerId");
        String deltaRaw = request.getParameter("delta");
        String reason = request.getParameter("reason");

        if (customerIdRaw == null || customerIdRaw.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu customerId");
            return;
        }
        long customerId;
        try {
            customerId = Long.parseLong(customerIdRaw.trim());
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "customerId phải là số");
            return;
        }

        if (deltaRaw == null || deltaRaw.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu delta");
            return;
        }
        int delta;
        try {
            delta = Integer.parseInt(deltaRaw.trim());
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "delta phải là số nguyên");
            return;
        }
        // Cap delta để chặn typo (1 số 0 thừa) khiến số dư âm hoặc tăng đột biến.
        if (Math.abs(delta) > 10000) {
            sendError(response, 400, "BAD_REQUEST", "Điểm điều chỉnh tối đa là 10000 mỗi lần");
            return;
        }

        if (reason == null || reason.trim().length() < 5) {
            sendError(response, 400, "BAD_REQUEST", "Lý do phải có ít nhất 5 ký tự");
            return;
        }
        reason = reason.trim();

        // Staff chỉ có một chi nhánh trong scope → lấy luôn để gắn vào ledger
        // phục vụ audit "staff chi nhánh nào đã điều chỉnh".
        Long staffId = sessionUserId(request);
        Long branchId = scope.branchIds().isEmpty() ? null : scope.branchIds().iterator().next();

        // findPoints trả về Optional<PointBalance> — empty nghĩa là customer
        // không tồn tại hoặc chưa có profile, tránh tạo ledger row ma.
        var profile = new LoyaltyDAO().findPoints(customerId);
        if (profile.isEmpty()) {
            sendError(response, 404, "NOT_FOUND", "Không tìm thấy hồ sơ khách hàng");
            return;
        }

        // Chống staff chi nhánh A điều chỉnh điểm khách hàng "của" chi nhánh B: customer
        // không có branch_id cố định nhưng có chi nhánh "chính" = chi nhánh của ticket /
        // concession_order gần nhất. Staff chỉ được điều chỉnh nếu trùng chi nhánh đó.
        // Admin và Manager toàn hệ thống được bypass; nếu customer chưa từng giao dịch
        // → cho phép nhưng ledger ghi branchId của staff để audit.
        if (scope.role() == Role.BRANCH_STAFF && branchId != null) {
            Long customerPrimaryBranch = new LoyaltyDAO().findPrimaryBranchId(customerId);
            if (customerPrimaryBranch != null && customerPrimaryBranch != branchId) {
                sendError(response, 403, "FORBIDDEN",
                        "Khách hàng này thuộc chi nhánh khác (" + customerPrimaryBranch
                                + "); staff chi nhánh " + branchId + " không được điều chỉnh");
                return;
            }
        }

        // Manual transaction thay vì @Transactional: phải rollback khi notification fail
        // không kéo theo rollback ledger (ledger đã commit rồi), và ngược lại.
        try (var conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PointLedger ledger = loyaltyService.adjust(
                        conn, customerId, delta, "STAFF_ADJUST", null, staffId, branchId);

                if (ledger == null) {
                    sendError(response, 500, "INTERNAL_ERROR", "Không tạo được bản ghi điểm");
                    conn.rollback();
                    return;
                }

                conn.commit();

                // Notification là best-effort: nếu SMTP lỗi vẫn trả 200 vì điểm đã được ghi.
                notificationService.notifyPointsAdjusted(customerId, delta, reason);

                Map<String, Object> result = new HashMap<>();
                result.put("newBalance", ledger.balanceAfter());
                result.put("ledgerEntry", Map.of(
                        "id", ledger.id(),
                        "delta", ledger.delta(),
                        "balanceAfter", ledger.balanceAfter(),
                        "refType", ledger.refType() != null ? ledger.refType() : "STAFF_ADJUST",
                        "reason", reason,
                        "staffId", staffId != null ? staffId : 0,
                        "createdAt", ledger.createdAt()
                ));
                sendOk(response, result);

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * GET /loyalty/ledger?customerId=X&limit=50&offset=0
     * 
     * Returns: { items: [...], total: N }
     * 
     * If staff, only show ledger for customers who have transacted at their branch.
     * For admin, show all.
     */
    private void handleGetLedger(AccessScope scope, HttpServletRequest request,
                                HttpServletResponse response) throws Exception {
        String customerIdRaw = request.getParameter("customerId");
        if (customerIdRaw == null || customerIdRaw.isBlank()) {
            sendError(response, 400, "BAD_REQUEST", "Thiếu customerId");
            return;
        }
        long customerId;
        try {
            customerId = Long.parseLong(customerIdRaw.trim());
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "customerId không hợp lệ");
            return;
        }

        int limit = 50;
        String limitRaw = request.getParameter("limit");
        if (limitRaw != null && !limitRaw.isBlank()) {
            try {
                limit = Integer.parseInt(limitRaw.trim());
                if (limit <= 0) limit = 50;
                if (limit > 100) limit = 100;
            } catch (NumberFormatException ignored) {}
        }

        int offset = 0;
        String offsetRaw = request.getParameter("offset");
        if (offsetRaw != null && !offsetRaw.isBlank()) {
            try {
                offset = Integer.parseInt(offsetRaw.trim());
                if (offset < 0) offset = 0;
            } catch (NumberFormatException ignored) {}
        }

        // Manager/Staff bị giới hạn ledger thuộc chi nhánh mình quản lý; Admin để null = toàn hệ thống.
        Long branchId = null;
        if (scope.role() != Role.ADMIN && !scope.branchIds().isEmpty()) {
            branchId = scope.branchIds().iterator().next();
        }

        LoyaltyDAO dao = new LoyaltyDAO();
        List<PointLedger> ledger = dao.findLedgerPaginated(customerId, branchId, limit, offset);
        int total = dao.countLedger(customerId, branchId);

        enrichLedgerEntries(ledger);

        Map<String, Object> result = new HashMap<>();
        result.put("items", ledger);
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        sendOk(response, result);
    }

    private void enrichLedgerEntries(List<PointLedger> ledger) {
        // Placeholder cho tương lai: dự kiến enrich mỗi entry với staffName để UI
        // hiển thị "Điều chỉnh bởi Nguyễn Văn A". Hiện tại staff_id đã có sẵn
        // trong response, client tự resolve nếu cần — tránh N+1 query ở backend.
        if (ledger.isEmpty()) return;
    }

    /**
     * GET /loyalty/customer-points?email=X  hoặc  ?phone=X
     * — Tra cứu điểm của khách hàng theo email hoặc số điện thoại.
     * Staff/Manager dùng để xem điểm trước khi điều chỉnh hoặc bán vé/combo.
     * Returns: { id, email, phone, fullName, points, tier, redemptionRate, tierDiscountPercent }
     */
    private void handleGetCustomerPoints(AccessScope scope, HttpServletRequest request,
                                         HttpServletResponse response) throws Exception {
        String email = request.getParameter("email");
        String phone = request.getParameter("phone");
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            sendError(response, 400, "VALIDATION_ERROR", "Cần cung cấp email hoặc số điện thoại");
            return;
        }

        UserDAO userDao = new UserDAO();
        User user = null;

        // Ưu tiên email nếu có
        if (email != null && !email.isBlank()) {
            user = userDao.findByEmail(email.trim().toLowerCase()).orElse(null);
        }
        // Thử phone nếu chưa tìm thấy
        if (user == null && phone != null && !phone.isBlank()) {
            user = userDao.findByPhone(normalizePhone(phone.trim())).orElse(null);
        }
        if (user == null) {
            sendError(response, 404, "NOT_FOUND", "Không tìm thấy khách hàng");
            return;
        }
        if (user.role() != Role.CUSTOMER) {
            sendError(response, 400, "BAD_REQUEST", "Tài khoản này không phải khách hàng");
            return;
        }

        var summary = loyaltyService.summary(user.id());
        Map<String, Object> result = new HashMap<>();
        result.put("id", user.id());
        result.put("email", user.email());
        result.put("phone", user.phone());
        result.put("fullName", user.fullName());
        result.put("points", summary.points());
        result.put("tier", summary.tier());
        result.put("redemptionRate", loyaltyService.getRedemptionRate());
        result.put("tierDiscountPercent", loyaltyService.tierDiscountFor(user.id(), 100));
        sendOk(response, result);
    }

    /**
     * GET /loyalty/customers?q=<term>&limit=<n>
     * - Không có q: trả về tất cả CUSTOMER mới nhất (mặc định limit 100)
     * - Có q: LIKE trên email/phone/full_name
     *
     * Trang "Điều chỉnh điểm" cần load danh sách khi vào để staff chọn
     * nhanh; q dùng để filter khi user gõ search box.
     */
    private void handleListCustomers(AccessScope scope, HttpServletRequest request,
                                     HttpServletResponse response) throws Exception {
        if (!scope.canAdjustPoints()) {
            sendForbidden(response, "Không có quyền xem danh sách khách hàng");
            return;
        }
        String q = request.getParameter("q");
        int limit = 100;
        try {
            String limitRaw = request.getParameter("limit");
            if (limitRaw != null && !limitRaw.isBlank()) {
                limit = Integer.parseInt(limitRaw.trim());
                if (limit <= 0) limit = 100;
                if (limit > 500) limit = 500;
            }
        } catch (NumberFormatException ignored) {}

        UserDAO dao = new UserDAO();
        List<CustomerSummary> items = (q == null || q.isBlank())
                ? dao.listCustomers(limit)
                : dao.searchCustomers(q, limit);

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("q", q == null ? "" : q.trim());
        result.put("limit", limit);
        sendOk(response, result);
    }

    /** Chuẩn hóa số điện thoại: bỏ khoảng trắng, leading 0 → 84. */
    private String normalizePhone(String raw) {
        String p = raw.replaceAll("\\s+", "");
        if (p.startsWith("0")) p = "84" + p.substring(1);
        if (p.startsWith("+")) p = p.substring(1);
        return p;
    }

    private Long sessionUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object userId = session.getAttribute("userId");
        return userId instanceof Long value ? value : null;
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
            logger.severe("StaffLoyaltyController error: " + e.getMessage());
            e.printStackTrace();
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }
}
