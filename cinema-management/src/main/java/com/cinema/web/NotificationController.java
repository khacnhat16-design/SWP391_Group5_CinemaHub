package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.notification.Notification;
import com.cinema.notification.NotificationDAO;
import com.cinema.notification.NotificationService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API controller cho thông báo in-app cá nhân (Req 23.5).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /notification — danh sách của chính tài khoản (?unreadOnly=true lọc chưa đọc).</li>
 *   <li>POST /notification/{id}/read — đánh dấu đã đọc một thông báo (guard owner).</li>
 *   <li>POST /notification/read-all — đánh dấu tất cả đã đọc.</li>
 * </ul>
 *
 * <p>IDOR: chỉ dùng userId từ session; thông báo của tài khoản khác → 403/404.
 */
public class NotificationController extends HttpServlet {
    private NotificationService notificationService;

    @Override
    public void init() throws ServletException {
        this.notificationService = new NotificationService(new NotificationDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            Long userId = requireAuthenticatedUser(request, response);
            if (userId == null) return;

            boolean unreadOnly = "true".equalsIgnoreCase(request.getParameter("unreadOnly"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("unreadCount", notificationService.unreadCount(userId));
            payload.put("notifications", notificationService.listForUser(userId,
                    unreadOnly ? Boolean.TRUE : null));
            sendOk(response, payload);
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            Long userId = requireAuthenticatedUser(request, response);
            if (userId == null) return;

            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if ("/read-all".equals(pathInfo)) {
                int updated = notificationService.markAllRead(userId);
                sendOk(response, Map.of("markedRead", updated));
                return;
            }
            if (pathInfo.matches("/\\d+/read")) {
                long notificationId = Long.parseLong(
                        pathInfo.substring(1, pathInfo.length() - "/read".length()));
                Notification notification = notificationService.markRead(notificationId, userId);
                sendOk(response, notification);
                return;
            }
            sendError(response, 400, "BAD_REQUEST",
                    "Dùng /notification/read-all hoặc /notification/{id}/read");
        } catch (NumberFormatException e) {
            sendError(response, 400, "BAD_REQUEST", "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // ---- helpers ----

    /** Req 23.5 — mỗi tài khoản chỉ thao tác thông báo của chính mình. */
    private Long requireAuthenticatedUser(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return null;
        }
        var session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute("userId");
        if (!(userId instanceof Long value)) {
            sendError(response, 401, "UNAUTHORIZED", "Cần đăng nhập");
            return null;
        }
        return value;
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

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            sendError(response, service.httpStatus(), service.code(), service.getMessage());
        } else {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }
}
