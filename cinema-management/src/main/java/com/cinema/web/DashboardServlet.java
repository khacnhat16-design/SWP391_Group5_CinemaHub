package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.report.ReportService;
import dal.NotificationDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Dashboard endpoint — Kpi + Monthly revenue + Recent bookings +
 * Today's showtimes + Booking breakdown cho Management Dashboard.
 *
 * <p>Access: ADMIN / BRANCH_MANAGER / BRANCH_STAFF (workspace).
 * Branch scope ép từ AccessScope (Req 14.2).
 *
 * <p>GET /api/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD&branchId=
 *
 * <p>Cũng có /api/dashboard/notifications?limit=5 — trả về notification chưa đọc
 * cho header bell.
 */
public class DashboardServlet extends HttpServlet {
    private ReportService reportService;

    @Override
    public void init() throws ServletException {
        this.reportService = new ReportService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            // 1. Lấy đối tượng AccessScope do AuthFilter đã đính kèm vào Request
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null) {
                sendForbidden(response, "Yêu cầu đăng nhập");
                return;
            }
            // 2. Kiểm tra vai trò (Role-based Authorization)
            Role role = scope.role();
            if (role != Role.ADMIN && role != Role.BRANCH_MANAGER && role != Role.BRANCH_STAFF) {
                sendForbidden(response, "Chỉ tài khoản nội bộ mới xem được dashboard quản lý");
                return;
            }
            // 3. Routing (Định tuyến Sub-path)
            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            switch (pathInfo) {
                case "", "/" -> sendDashboard(request, response, scope);
                case "/notifications" -> sendNotifications(request, response, scope);
                default -> sendError(response, 400, "BAD_REQUEST",
                        "Endpoint không hợp lệ: " + pathInfo);
            }
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "Dữ liệu số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private void sendDashboard(HttpServletRequest request, HttpServletResponse response,
                                AccessScope scope) throws Exception {
        // 1. Parse khoảng thời gian lọc dữ liệu (Date Range)
        LocalDate from = parseDate(request.getParameter("from"));
        LocalDate to = parseDate(request.getParameter("to"));
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to == null) to = LocalDate.now().plusDays(1);

        // ADMIN xem toàn chuỗi; Manager/Staff bị ép về 1 chi nhánh.
        // Manager/Staff chưa được gán branch → trả DashboardSummary rỗng (không lộ
        // dữ liệu chuỗi, không 403 để UI vẫn render được).
        //// 2. Xử lý phạm vi chi nhánh bắt buộc cho Nhân viên / Quản lý (Multi-tenancy Isolation)
        Long branchScope = null;
        if (scope.role() != Role.ADMIN) {
            branchScope = scope.branchIds().isEmpty() ? null : scope.branchIds().iterator().next();
            if (branchScope == null) {
                sendOk(response, emptySummary());
                return;
            }
        } // 3. Xử lý bộ lọc chi nhánh cho ADMIN (Optional Filter)
        Long branchFilter = (scope.role() == Role.ADMIN)
                ? parseOptionalLong(request.getParameter("branchId")) : null;
        // 4. Gọi Service thực thi truy vấn & Trả kết quả JSON
        ReportService.DashboardSummary summary =
                reportService.dashboardSummary(from, to, branchScope, branchFilter);
        sendOk(response, summary);
    }

    private ReportService.DashboardSummary emptySummary() {
        return new ReportService.DashboardSummary(
                0L, 0L, 0, 0, 0,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                new ReportService.BookingBreakdown(0, 0, 0, 0));
    }

    private void sendNotifications(HttpServletRequest request, HttpServletResponse response,
                                   AccessScope scope) throws Exception {
        int limit = parseIntOr(request.getParameter("limit"), 5);
        if (limit <= 0 || limit > 20) limit = 5;
        Long userId = currentUserId(request);
        java.util.List<dal.NotificationDAO.NotificationRow> rows = new NotificationDAO()
                .findRecentForCurrent(userId, limit);
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("recent", rows);
        sendOk(response, payload);
    }

    private Long currentUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute("userId");
        return (value instanceof Long l) ? l : null;
    }

    private long requireSingleBranch(AccessScope scope) {
        if (scope.branchIds().isEmpty()) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        return scope.branchIds().iterator().next();
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return LocalDate.parse(raw.trim());
    }

    private Long parseOptionalLong(String raw) {
        return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
    }

    private int parseIntOr(String raw, int fallback) {
        return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        sendError(response, 403, "FORBIDDEN", message);
    }

    private void sendError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }
}
