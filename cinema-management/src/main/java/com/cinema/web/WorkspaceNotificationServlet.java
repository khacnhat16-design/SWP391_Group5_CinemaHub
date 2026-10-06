package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import dal.NotificationDAO;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notification endpoint cho bell header (workspace).
 *
 * <p>GET /api/notification/unread-count — count + recent items.
 * <p>POST /api/notification/mark-read/{id} — đánh dấu 1 notification đã đọc.
 *
 * <p>Lưu ý: dùng sessionScope.userId để tránh mở rộng AccessScope API.
 */
public class WorkspaceNotificationServlet extends HttpServlet {

    private final NotificationDAO notificationDAO = new NotificationDAO();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null) {
                sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
                return;
            }
            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if ("/unread-count".equals(pathInfo)) {
                int limit = parseIntOr(request.getParameter("limit"), 5);
                Long userId = currentUserId(request);
                int count = notificationDAO.countUnread(userId);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("count", count);
                payload.put("recent", notificationDAO.findRecentForCurrent(userId, limit));
                sendOk(response, payload);
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Endpoint không hợp lệ");
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null) {
                sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
                return;
            }
            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (pathInfo.startsWith("/mark-read/")) {
                long id = Long.parseLong(pathInfo.substring("/mark-read/".length()));
                markRead(request, id);
                sendOk(response, Map.of("ok", true));
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Endpoint không hợp lệ");
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }

    private void markRead(HttpServletRequest request, long notificationId) throws Exception {
        Long userId = currentUserId(request);
        if (userId == null) throw new ServiceException.Unauthorized("Yêu cầu đăng nhập");
        java.sql.Connection conn = dal.DBContext.getConnection();
        try (java.sql.PreparedStatement ps = conn.prepareStatement(
                "UPDATE dbo.notification SET is_read = 1 WHERE id = ? AND user_id = ?")) {
            ps.setLong(1, notificationId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } finally {
            conn.close();
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute("userId");
        return (value instanceof Long l) ? l : null;
    }

    private int parseIntOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (Exception e) { return fallback; }
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }
}
