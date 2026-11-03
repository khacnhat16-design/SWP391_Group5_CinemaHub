package com.cinema.controller;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.model.support.SupportRequest;
import com.cinema.dao.support.SupportRequestDAO;
import com.cinema.service.support.SupportRequestService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for support requests (RSC-3 / R2).
 *
 * <ul>
 *   <li>GET  /api/support-requests/mine — Customer's own list.</li>
 *   <li>POST /api/support-requests — Customer creates a request.</li>
 *   <li>GET  /api/support-requests?status= — Manager view (filter by status).</li>
 *   <li>POST /api/support-requests/{id}/reply — Manager replies.</li>
 *   <li>POST /api/support-requests/{id}/close — Manager closes.</li>
 * </ul>
 */
public class SupportRequestController extends HttpServlet {

    private SupportRequestService service;

    @Override
    public void init() throws ServletException {
        this.service = new SupportRequestService(new SupportRequestDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest()) {
                sendForbidden(response, "Cần đăng nhập");
                return;
            }
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();
            if ("/mine".equals(path)) {
                Long userId = sessionUserId(request);
                if (userId == null || scope.role() != Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ khách hàng mới có thể xem yêu cầu của mình");
                    return;
                }
                List<SupportRequest> items = service.listMine(userId);
                sendOk(response, Map.of("items", items, "total", items.size()));
                return;
            }
            if (!isManagerOrStaff(scope)) {
                sendForbidden(response, "Chỉ Admin/Manager/Staff được xem tất cả yêu cầu");
                return;
            }
            String status = request.getParameter("status");
            sendOk(response, Map.of("items", service.listAll(status), "total", 0));
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest()) {
                sendForbidden(response, "Cần đăng nhập");
                return;
            }
            String path = request.getPathInfo() == null ? "" : request.getPathInfo();

            // POST /api/support-requests (no pathInfo) → Customer create
            if (path.isEmpty() || "/".equals(path)) {
                if (scope.role() != Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ khách hàng mới có thể gửi yêu cầu hỗ trợ");
                    return;
                }
                String subject = request.getParameter("subject");
                String message = request.getParameter("message");
                SupportRequest created = service.create(sessionUserId(request), subject, message);
                sendOk(response, created);
                return;
            }

            // POST /api/support-requests/{id}/reply
            if (path.endsWith("/reply")) {
                if (!isManager(scope)) {
                    sendForbidden(response, "Chỉ Admin/Manager được phản hồi");
                    return;
                }
                Long id = parseId(path);
                SupportRequest sr = service.reply(sessionUserId(request), id, request.getParameter("reply"));
                sendOk(response, sr);
                return;
            }

            // POST /api/support-requests/{id}/close
            if (path.endsWith("/close")) {
                if (!isManager(scope)) {
                    sendForbidden(response, "Chỉ Admin/Manager được đóng yêu cầu");
                    return;
                }
                Long id = parseId(path);
                sendOk(response, service.close(id));
                return;
            }

            sendBadRequest(response, "Endpoint không hợp lệ");
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private boolean isManager(AccessScope scope) {
        return scope.role() == Role.ADMIN || scope.role() == Role.BRANCH_MANAGER;
    }

    private boolean isManagerOrStaff(AccessScope scope) {
        return isManager(scope) || scope.role() == Role.BRANCH_STAFF;
    }

    private Long parseId(String path) {
        // path like "/123/reply" → "123"
        String[] parts = path.split("/");
        // parts: ["", "123", "reply"]
        if (parts.length < 2) throw new NumberFormatException("missing id");
        return Long.parseLong(parts[1]);
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
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("FORBIDDEN", message)));
    }

    private void sendBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("BAD_REQUEST", message)));
    }

    private void sendInternalError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống")));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            response.setStatus(service.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(SerializationUtil.toJson(
                    new ErrorEnvelope(service.code(), service.getMessage())));
        } else {
            sendInternalError(response);
        }
    }
}
