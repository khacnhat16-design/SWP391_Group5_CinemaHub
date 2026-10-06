package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.auth.User;
import com.cinema.auth.UserDAO;
import com.cinema.booking.BookingService;
import com.cinema.booking.HoldResult;
import com.cinema.booking.SeatHoldDAO;
import com.cinema.booking.Ticket;
import com.cinema.booking.TicketDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.payment.MockGatewayProvider;
import com.cinema.payment.Payment;
import com.cinema.payment.PaymentDAO;
import com.cinema.payment.PaymentService;
import com.cinema.payment.VnPayConfig;
import com.cinema.payment.VnPayProvider;
import com.cinema.payment.VnPayUtil;
import com.cinema.notification.NotificationDAO;
import com.cinema.notification.NotificationService;
import com.cinema.pricing.PriceRuleDAO;
import com.cinema.pricing.PricingService;
import com.cinema.pricing.TicketType;
import com.cinema.showtime.ShowtimeDAO;
import com.cinema.showtime.ShowtimeService;
import com.cinema.showtime.Showtime;
import com.cinema.wallet.WalletDAO;
import com.cinema.wallet.WalletService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller cho booking flow (Req 7, 8, 11, 12).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /booking/hold — Customer giữ ghế 10 phút (Req 7.1).</li>
 *   <li>POST /booking/confirm — xác nhận booking trong hold + method/voucher (Req 7.5).</li>
 *   <li>POST /booking/counter-sale — Branch Staff bán vé tiền mặt tại quầy (Req 7.7).</li>
 *   <li>POST /booking/cancel — hủy vé bậc thang (Customer chủ vé hoặc Staff đúng branch, Req 11).</li>
 *   <li>POST /booking/validate — Branch Staff soát vé một lần (Req 12).</li>
 *   <li>GET /booking/mine — lịch sử vé của chính Customer (Req 16.5, chặn IDOR).</li>
 * </ul>
 */
public class BookingController extends HttpServlet {
    /** Secret HMAC demo cho mock gateway — production đọc từ cấu hình bảo mật. */
    private static final String MOCK_HMAC_SECRET = System.getProperty(
            "cinema.payment.hmacSecret", "cinema-demo-secret");

    private BookingService bookingService;
    private TicketDAO ticketDao;
    private ShowtimeDAO showtimeDao;
    private ShowtimeService showtimeService;
    private PricingService pricingService;
    private VnPayProvider vnPayProvider;
    private UserDAO userDao;

    @Override
    public void init() throws ServletException {
        this.userDao = new UserDAO();
        this.showtimeDao = new ShowtimeDAO();
        this.showtimeService = new ShowtimeService(showtimeDao,
                new com.cinema.screen.ScreenDAO(), new com.cinema.movie.MovieDAO(),
                new com.cinema.branch.BranchDAO());
        this.pricingService = new PricingService(new PriceRuleDAO(), showtimeDao,
                new com.cinema.pricing.HolidayDAO(),
                new com.cinema.pricing.PricingConfigDAO());
        WalletService walletService = new WalletService(new WalletDAO());
        VnPayConfig vnPayConfig = VnPayConfig.load();
        this.vnPayProvider = new VnPayProvider(vnPayConfig);
        PaymentService paymentService = new PaymentService(new PaymentDAO(), walletService,
                new MockGatewayProvider(MOCK_HMAC_SECRET, ""), "CinemaHub demo account",
                vnPayProvider);
        NotificationService notificationService = new NotificationService(new NotificationDAO());
        this.ticketDao = new TicketDAO();
        this.bookingService = new BookingService(new SeatHoldDAO(), ticketDao, showtimeService,
                pricingService, walletService, paymentService, notificationService);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            // /booking/list — Admin xem toàn hệ thống; Manager/Staff xem vé trong phạm vi chi nhánh.
            if ("/list".equals(path)) {
                AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
                if (scope == null) {
                    sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
                    return;
                }
                if (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER
                        && scope.role() != Role.BRANCH_STAFF) {
                    sendError(response, 403, "FORBIDDEN", "Chỉ nhân viên, quản lý hoặc Admin xem được danh sách vé");
                    return;
                }
                sendOk(response, listAllTickets(scope, request));
                return;
            }
            // /booking/lookup?code=X — Admin/Manager/Staff tra cứu nhanh một vé theo mã
            // (Không thay đổi trạng thái, khác với /validate để soát vé tại cửa.)
            if ("/lookup".equals(path)) {
                AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
                if (scope == null || scope.isGuest()) {
                    sendForbidden(response, "Cần đăng nhập");
                    return;
                }
                if (scope.role() == Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ nhân viên mới tra cứu được");
                    return;
                }
                String code = request.getParameter("code");
                if (code == null || code.isBlank()) {
                    sendError(response, 400, "BAD_REQUEST", "Thiếu mã vé");
                    return;
                }
                sendOk(response, lookupTicket(scope, code.trim()));
                return;
            }
            if (!path.isBlank() && !"/mine".equals(path)) {
                sendError(response, 404, "NOT_FOUND", "Endpoint khong ton tai");
                return;
            }
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
                sendForbidden(response, "Chỉ Customer được xem vé của mình");
                return;
            }
            Long userId = sessionUserId(request);
            if (userId == null) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
            // Req 16.5/16.6 — chỉ vé của chính tài khoản trong session, không nhận userId từ client
            sendOk(response, ticketsForUser(userId));
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private java.util.Map<String, Object> listAllTickets(AccessScope scope, HttpServletRequest request) throws Exception {
        int limit = parseIntOr(request.getParameter("limit"), 50);
        if (limit <= 0 || limit > 200) limit = 50;
        Long branchScope = (scope.role() == Role.ADMIN) ? null : singleBranch(scope);

        StringBuilder sql = new StringBuilder("""
            SELECT TOP (?) t.id, t.ticket_code, COALESCE(u.full_name, N'Khách lẻ') AS customer,
                   m.title AS movie, s.start_time AS showtime_start,
                   t.total_amount, t.status, t.created_at
            FROM dbo.ticket t
            LEFT JOIN dbo.user_account u ON u.id = t.user_id
            JOIN dbo.showtime s ON s.id = t.showtime_id
            JOIN dbo.movie m ON m.id = s.movie_id
            WHERE 1=1
            """);
        if (branchScope != null) sql.append(" AND t.branch_id = ?");
        sql.append(" ORDER BY t.created_at DESC");

        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        try (java.sql.Connection conn = dal.DBContext.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, limit);
            if (branchScope != null) ps.setLong(2, branchScope);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("ticketCode", rs.getString("ticket_code"));
                    m.put("customerName", rs.getString("customer"));
                    m.put("movieTitle", rs.getString("movie"));
                    java.sql.Timestamp ts = rs.getTimestamp("showtime_start");
                    m.put("showtimeStart", ts == null ? null : ts.toLocalDateTime().toString());
                    m.put("totalAmount", rs.getLong("total_amount"));
                    m.put("status", rs.getString("status"));
                    items.add(m);
                }
            }
        }
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return resp;
    }

    private long singleBranch(AccessScope scope) {
        if (scope.branchIds().isEmpty()) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        return scope.branchIds().iterator().next();
    }

    private int parseIntOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (Exception e) { return fallback; }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        try {
            switch (path) {
                case "/quote" -> quote(request, response);
                case "/hold" -> hold(request, response);
                case "/release" -> release(request, response);
                case "/confirm" -> confirm(request, response);
                case "/counter-sale" -> counterSale(request, response);
                case "/cancel" -> cancel(request, response);
                case "/validate" -> validate(request, response);
                default -> sendError(response, 400, "BAD_REQUEST", "Endpoint không tồn tại");
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    /** Báo giá chính thức trước khi giữ ghế; dùng cùng pricing/tier logic khi xác nhận vé. */
    private void quote(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer đã đăng nhập mới được xem báo giá đặt vé");
            return;
        }

        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        if (!isActiveCustomer(userId)) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            sendError(response, 401, "UNAUTHORIZED",
                    "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
            return;
        }

        long showtimeId = Long.parseLong(requireParam(request, "showtimeId"));
        List<Long> seatIds = parseSeatIds(requireParam(request, "seatIds"));
        if (new java.util.HashSet<>(seatIds).size() != seatIds.size()) {
            throw new ServiceException.Validation("Danh sách ghế không được trùng");
        }
        // Req 11 — ignore client-provided unitPrice; ticketType hợp lệ (Req 9).
        TicketType ticketType = parseTicketType(request.getParameter("ticketType"));

        showtimeService.requireBookable(showtimeId);
        var detail = showtimeService.getDetail(showtimeId);
        Map<Long, ShowtimeDAO.ShowtimeSeat> seatsById = detail.seats().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ShowtimeDAO.ShowtimeSeat::seatId, seat -> seat));
        List<String> seatTypes = new ArrayList<>();
        for (Long seatId : seatIds) {
            ShowtimeDAO.ShowtimeSeat seat = seatsById.get(seatId);
            if (seat == null) {
                throw new ServiceException.Validation("Ghế không thuộc suất chiếu này");
            }
            boolean available = "AVAILABLE".equalsIgnoreCase(seat.status());
            boolean ownHold = seat.holdId() != null && userId.equals(seat.holdUserId());
            if (!available && !ownHold) {
                throw new ServiceException.Conflict("Ghế đã được giữ hoặc bán");
            }
            seatTypes.add(seat.seatType());
        }

        sendOk(response, pricingService.calcOrder(showtimeId, seatTypes, ticketType));
    }

    /** Req 7.1-7.3 — Customer giữ ghế 10 phút. */
    private void hold(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer đã đăng nhập mới được giữ ghế online");
            return;
        }

        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        if (!isActiveCustomer(userId)) {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            sendError(response, 401, "UNAUTHORIZED",
                    "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
            return;
        }
        long showtimeId = Long.parseLong(requireParam(request, "showtimeId"));
        List<Long> seatIds = parseSeatIds(requireParam(request, "seatIds"));
        TicketType ticketType = parseTicketType(request.getParameter("ticketType"));
        java.util.logging.Logger.getLogger(BookingController.class.getName()).info(
                "[booking-debug] HOLD request userId=" + userId
                        + " showtimeId=" + showtimeId + " seatIds=" + seatIds
                        + " ticketType=" + ticketType);

        HoldResult result = bookingService.holdSeats(showtimeId, seatIds, userId, ticketType);
        if (result.success()) {
            sendOk(response, result);
        } else {
            // Ghế không còn trống / showtime không mở bán → 409 (Req 7.2, 8.5)
            sendError(response, 409, "CONFLICT", result.message());
        }
    }

    /** Parse optional ticketType — null khi client không gửi (Req 9). */
    private TicketType parseTicketType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        TicketType t = TicketType.fromCode(raw);
        if (t == null) {
            throw new ServiceException.Validation("Loại vé phải là ADULT/CHILD/STUDENT/VIP");
        }
        return t;
    }

    private boolean isActiveCustomer(Long userId) {
        try {
            return userDao.findById(userId)
                    .filter(User::isActive)
                    .map(user -> user.role() == Role.CUSTOMER)
                    .orElse(false);
        } catch (Exception e) {
            throw new ServiceException.Unauthorized("Không thể xác thực tài khoản đặt vé");
        }
    }

    private void release(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer đã đăng nhập mới được hủy chỗ");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        long showtimeId = Long.parseLong(requireParam(request, "showtimeId"));
        List<Long> seatIds = parseSeatIds(requireParam(request, "seatIds"));
        bookingService.releaseHold(showtimeId, seatIds, userId);
        sendOk(response, Map.of("success", true));
    }

    /** Req 7.5, 7.6 — xác nhận booking trong thời hạn hold. */
    private void confirm(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
            sendForbidden(response, "Chỉ Customer đã đăng nhập mới được xác nhận booking online");
            return;
        }
        Long userId = sessionUserId(request);
        if (userId == null) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        long holdId = Long.parseLong(requireParam(request, "holdId"));
        String method = request.getParameter("paymentMethod");
        java.util.logging.Logger.getLogger(getClass().getName()).info(
                "[confirm] holdId=" + holdId + " userId=" + userId + " method=" + method);
        com.cinema.booking.Ticket ticket;
        try {
            ticket = bookingService.confirmBooking(holdId, method, userId, null);
        } catch (RuntimeException ex) {
            java.util.logging.Logger.getLogger(getClass().getName()).log(
                    java.util.logging.Level.SEVERE,
                    "[confirm] confirmBooking failed holdId=" + holdId + " userId=" + userId, ex);
            throw ex;
        }
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket", ticket);
        // Với VNPay / MOCK_GATEWAY trả về redirect URL chuyển khoản cổng thanh toán.
        // Với các method khác (WALLET/CASH) trả về payload thôi.
        if ("MOCK_GATEWAY".equals(normalizedMethod) || "VNPAY".equals(normalizedMethod)) {
            Payment payment = new PaymentDAO().findPendingByTicket(ticket.id())
                    .orElseThrow(() -> new ServiceException.NotFound(
                            "Không tạo được phiên thanh toán"));
            String redirect;
            if ("VNPAY".equals(normalizedMethod)) {
                // Dùng cùng flow với WalletController: redirect thẳng sang VNPay Sandbox
                // bằng URL đã ký, không chuyển qua form demo nội bộ hoặc trang home.
                Map<String, String> params = new java.util.LinkedHashMap<>();
                params.put("vnp_Version", "2.1.0");
                params.put("vnp_Command", "pay");
                params.put("vnp_TmnCode", vnPayProvider.config().tmnCode());
                params.put("vnp_Amount", VnPayUtil.toVnpAmount(payment.amount()));
                params.put("vnp_CurrCode", "VND");
                params.put("vnp_TxnRef", payment.idempotencyKey());
                params.put("vnp_OrderInfo", "Thanh toan ve xem phim ticket " + ticket.id());
                params.put("vnp_OrderType", "other");
                params.put("vnp_Locale", "vn");
                params.put("vnp_ReturnUrl", request.getScheme() + "://" + request.getServerName()
                        + (request.getServerPort() == 80 || request.getServerPort() == 443
                        ? "" : ":" + request.getServerPort())
                        + request.getContextPath() + "/vnpay/return");
                params.put("vnp_IpAddr", request.getRemoteAddr());
                params.putAll(VnPayUtil.paymentWindow(vnPayProvider.config().expireMinutes()));
                redirect = VnPayUtil.buildRedirectUrl(vnPayProvider.config().payUrl(), params,
                        vnPayProvider.config().hashSecret());
            } else {
                String hmac = com.cinema.util.HmacUtil.sign(MOCK_HMAC_SECRET,
                        payment.id() + "" + payment.amount());
                redirect = request.getContextPath() + "/payment/mock-gateway"
                        + "?paymentId=" + payment.id()
                        + "&amount=" + payment.amount()
                        + "&idempotencyKey=" + URLEncoder.encode(
                                payment.idempotencyKey(), StandardCharsets.UTF_8)
                        + "&hmac=" + hmac;
            }
            result.put("payment", payment);
            result.put("redirectUrl", redirect);
        }
        sendOk(response, result);
    }

    /** Req 7.7 — Branch Staff bán vé tiền mặt tại quầy (ép branch scope). */
    private void counterSale(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || !scope.can("TICKET_SELL")) {
            sendForbidden(response, "Chỉ Branch Staff/Branch Manager mới được bán vé tại quầy");
            return;
        }
        Long staffId = sessionUserId(request);
        long staffBranchId = requireSingleBranch(scope);

        long showtimeId = Long.parseLong(requireParam(request, "showtimeId"));
        List<Long> seatIds = parseSeatIds(requireParam(request, "seatIds"));
        Long customerId = parseOptionalLong(request.getParameter("customerId"));
        TicketType ticketType = parseTicketType(request.getParameter("ticketType"));

        Ticket ticket = bookingService.sellAtCounter(showtimeId, seatIds,
                customerId, staffId, staffBranchId, ticketType);
        sendOk(response, ticket);
    }

    /** Req 11 — Customer hủy vé của mình hoặc Staff hủy tại quầy đúng chi nhánh. */
    private void cancel(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return;
        }
        long ticketId = Long.parseLong(requireParam(request, "ticketId"));

        Long actorCustomerId = null;
        Long actorStaffBranchId = null;
        if (scope.role() == Role.CUSTOMER) {
            actorCustomerId = sessionUserId(request);
            if (actorCustomerId == null) {
                sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
                return;
            }
        } else if (scope.can("TICKET_SELL")) {
            actorStaffBranchId = requireSingleBranch(scope);
        } else {
            sendForbidden(response, "Không có quyền hủy vé");
            return;
        }

        Ticket ticket = bookingService.cancelTicket(ticketId, actorCustomerId, actorStaffBranchId);
        sendOk(response, ticket);
    }

    /** Req 12 — Branch Staff soát vé một lần tại đúng chi nhánh. */
    private void validate(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.role() != Role.BRANCH_STAFF
                || !scope.can("TICKET_VALIDATE")) {
            sendForbidden(response, "Chỉ nhân viên chi nhánh mới được soát vé");
            return;
        }
        Long staffId = sessionUserId(request);
        long staffBranchId = requireSingleBranch(scope);
        String ticketCode = requireParam(request, "ticketCode").toUpperCase();

        Map<String, Object> result = lookupTicket(scope, ticketCode);
        Ticket ticket = bookingService.validateTicket(ticketCode, staffId, staffBranchId);
        result.put("status", ticket.status());
        result.put("usedAt", ticket.usedAt());
        result.put("usedBy", ticket.usedBy());
        sendOk(response, result);
    }

    /**
     * Tra cứu nhanh một vé theo ticketCode (read-only). Manager xem toàn hệ thống;
     * Staff bị giới hạn về chi nhánh của mình.
     */
    private Map<String, Object> lookupTicket(AccessScope scope, String ticketCode) throws Exception {
        StringBuilder sql = new StringBuilder("""
            SELECT TOP 1 t.id, t.ticket_code, COALESCE(u.full_name, N'Khách lẻ') AS customer_name,
                   u.email AS customer_email, m.title AS movie_title,
                   sc.name AS screen_name, s.start_time AS showtime_start,
                   b.name AS branch_name, t.total_amount, t.status,
                   t.created_at, t.confirmed_at, t.used_at, t.used_by
            FROM dbo.ticket t
            LEFT JOIN dbo.user_account u ON u.id = t.user_id
            JOIN dbo.showtime s ON s.id = t.showtime_id
            JOIN dbo.movie m ON m.id = s.movie_id
            JOIN dbo.screen sc ON sc.id = s.screen_id
            JOIN dbo.branch b ON b.id = t.branch_id
            WHERE t.ticket_code = ?
            """);
        // Staff chỉ xem vé chi nhánh mình; Admin/Manager: không giới hạn.
        if (scope.role() != Role.ADMIN && !scope.branchIds().isEmpty()) {
            sql.append(" AND t.branch_id = ?");
        }

        try (java.sql.Connection conn = dal.DBContext.getConnection()) {
            Map<String, Object> row = new LinkedHashMap<>();
            long ticketId;
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                ps.setString(1, ticketCode.trim().toUpperCase());
                if (scope.role() != Role.ADMIN && !scope.branchIds().isEmpty()) {
                    ps.setLong(2, scope.branchIds().iterator().next());
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ServiceException.NotFound("Không tìm thấy vé với mã " + ticketCode);
                    }
                    ticketId = rs.getLong("id");
                    row.put("id", ticketId);
                    row.put("ticketCode", rs.getString("ticket_code"));
                    row.put("customerName", rs.getString("customer_name"));
                    row.put("customerEmail", rs.getString("customer_email"));
                    row.put("movieTitle", rs.getString("movie_title"));
                    row.put("screenName", rs.getString("screen_name"));
                    java.sql.Timestamp ts = rs.getTimestamp("showtime_start");
                    row.put("showtimeStart", ts == null ? null : ts.toLocalDateTime().toString());
                    row.put("branchName", rs.getString("branch_name"));
                    row.put("totalAmount", rs.getLong("total_amount"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", stringifyTs(rs.getTimestamp("created_at")));
                    row.put("confirmedAt", stringifyTs(rs.getTimestamp("confirmed_at")));
                    row.put("usedAt", stringifyTs(rs.getTimestamp("used_at")));
                    row.put("usedBy", rs.getObject("used_by"));
                }
            }
            List<Map<String, Object>> seats = new ArrayList<>();
            for (Ticket.TicketSeat seat : ticketDao.findSeats(conn, ticketId)) {
                Map<String, Object> seatRow = new LinkedHashMap<>();
                seatRow.put("seatId", seat.seatId());
                seatRow.put("rowLabel", seat.rowLabel());
                seatRow.put("colNo", seat.colNo());
                seatRow.put("seatType", seat.seatType());
                seatRow.put("price", seat.price());
                seatRow.put("seatCode", seat.rowLabel() + seat.colNo());
                seats.add(seatRow);
            }
            row.put("seats", seats);
            return row;
        }
    }

    private static String stringifyTs(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toString();
    }

    private List<Map<String, Object>> ticketsForUser(long userId) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (java.sql.Connection conn = dal.DBContext.getConnection()) {
            List<Ticket> tickets = ticketDao.findByUser(userId);
            // N+1 fix: batch-load all seats for these tickets in a single query.
            java.util.Map<Long, List<Ticket.TicketSeat>> seatsByTicket = ticketDao.findSeatsByTickets(
                    conn,
                    tickets.stream().map(Ticket::id).toList());
            for (Ticket ticket : tickets) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", ticket.id());
                row.put("ticketCode", ticket.ticketCode());
                row.put("showtimeId", ticket.showtimeId());
                row.put("branchId", ticket.branchId());
                row.put("status", ticket.status());
                row.put("totalAmount", ticket.totalAmount());
                row.put("createdAt", ticket.createdAt());
                row.put("confirmedAt", ticket.confirmedAt());
                row.put("cancelledAt", ticket.cancelledAt());
                row.put("refundAmount", ticket.refundAmount());

                List<Map<String, Object>> seats = new ArrayList<>();
                List<Ticket.TicketSeat> ticketSeats = seatsByTicket.getOrDefault(ticket.id(), List.of());
                for (Ticket.TicketSeat seat : ticketSeats) {
                    Map<String, Object> seatMap = new LinkedHashMap<>();
                    seatMap.put("seatId", seat.seatId());
                    seatMap.put("rowLabel", seat.rowLabel());
                    seatMap.put("colNo", seat.colNo());
                    seatMap.put("seatType", seat.seatType());
                    seatMap.put("price", seat.price());
                    seatMap.put("seatCode", seat.rowLabel() + seat.colNo());
                    seats.add(seatMap);
                }
                row.put("seats", seats);
                Map<String, Object> show = showtimeInfo(conn, ticket.showtimeId());
                row.putAll(show);

                // Nghiệp vụ: suất chiếu đã kết thúc thì không cho hoàn — tránh tình trạng
                // user refund vé sau khi xem phim. UI dùng showtimePassed để disable nút.
                java.time.LocalDateTime endTime = (java.time.LocalDateTime) show.get("endTime");
                boolean showtimePassed = endTime != null
                        && java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).isAfter(endTime);
                boolean canCancel = Ticket.STATUS_CONFIRMED.equals(ticket.status()) && !showtimePassed;
                row.put("showtimePassed", showtimePassed);
                row.put("canCancel", canCancel);
                row.put("refundable", canCancel);
                result.add(row);
            }
        }
        return result;
    }

    private Map<String, Object> showtimeInfo(java.sql.Connection conn, long showtimeId) throws Exception {
        String sql = """
            SELECT s.start_time, s.end_time, m.title AS movie_title,
                   b.name AS branch_name, sc.name AS screen_name
            FROM dbo.showtime s
            JOIN dbo.movie m ON m.id = s.movie_id
            JOIN dbo.branch b ON b.id = s.branch_id
            JOIN dbo.screen sc ON sc.id = s.screen_id
            WHERE s.id = ?
            """;
        Map<String, Object> info = new LinkedHashMap<>();
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.Timestamp start = rs.getTimestamp("start_time");
                    java.sql.Timestamp end = rs.getTimestamp("end_time");
                    info.put("movieTitle", rs.getString("movie_title"));
                    info.put("branchName", rs.getString("branch_name"));
                    info.put("screenName", rs.getString("screen_name"));
                    info.put("startTime", start == null ? null : start.toLocalDateTime());
                    info.put("endTime", end == null ? null : end.toLocalDateTime());
                }
            }
        }
        return info;
    }

    // ---- helpers (mirror MovieController/ScreenController patterns) ----

    private Long sessionUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object userId = session.getAttribute("userId");
        return userId instanceof Long value ? value : null;
    }

    /** Manager/Staff phải có đúng một branch trong scope để ép phạm vi. */
    private long requireSingleBranch(AccessScope scope) {
        if (scope.branchIds().isEmpty()) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        return scope.branchIds().iterator().next();
    }

    private String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số bắt buộc: " + name);
        }
        return value.trim();
    }

    /** seatIds nhận dạng "1,2,3". */
    private List<Long> parseSeatIds(String raw) {
        List<Long> seatIds = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) seatIds.add(Long.parseLong(trimmed));
        }
        if (seatIds.isEmpty()) {
            throw new ServiceException.Validation("Danh sách ghế không được rỗng");
        }
        return seatIds;
    }

    private Long parseOptionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
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
            java.util.logging.Logger.getLogger(BookingController.class.getName())
                    .log(java.util.logging.Level.SEVERE, "Booking request failed", e);
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }
}
