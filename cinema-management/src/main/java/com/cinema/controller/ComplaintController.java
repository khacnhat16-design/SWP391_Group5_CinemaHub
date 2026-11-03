package com.cinema.controller;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.model.complaint.Complaint;
import com.cinema.dao.complaint.ComplaintDAO;
import com.cinema.service.complaint.ComplaintService;
import com.cinema.filter.AuthFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for complaints (RSC-4 / R3).
 *
 * <ul>
 *   <li>GET  /api/complaints/mine — Customer's own list.</li>
 *   <li>POST /api/complaints — Customer submits complaint.</li>
 *   <li>GET  /api/complaints?status= — Manager view.</li>
 *   <li>POST /api/complaints/{id}/reply — Manager reply.</li>
 *   <li>POST /api/complaints/{id}/status — Manager status transition.</li>
 * </ul>
 */
public class ComplaintController extends HttpServlet {

    private ComplaintService service;

    @Override
    public void init() throws ServletException {
        this.service = new ComplaintService(new ComplaintDAO());
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
                if (scope.role() != Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ khách hàng mới có thể xem khiếu nại của mình");
                    return;
                }
                List<Complaint> items = service.listMine(sessionUserId(request));
                sendOk(response, Map.of("items", items, "total", items.size()));
                return;
            }
            if (!isManager(scope)) {
                sendForbidden(response, "Chỉ Admin/Manager được xem tất cả khiếu nại");
                return;
            }
            // Manager có thể không có branchId trong scope → trả rỗng thay vì lỗi
            if (scope.role() != Role.ADMIN
                    && (scope.branchIds() == null || scope.branchIds().isEmpty())) {
                sendOk(response, Map.of("items", List.of(), "total", 0));
                return;
            }
            String status = request.getParameter("status");
            List<Complaint> items = service.listAll(status,
                    scope.role() == Role.ADMIN ? null : scope.branchIds());
            sendOk(response, Map.of("items", items, "total", items.size()));
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

            if (path.isEmpty() || "/".equals(path)) {
                if (scope.role() != Role.CUSTOMER) {
                    sendForbidden(response, "Chỉ khách hàng mới có thể gửi khiếu nại");
                    return;
                }
                Long branchId = parseLongOrNull(request.getParameter("branchId"));
                if (branchId == null) {
                    sendBadRequest(response, "Vui lòng chọn chi nhánh nơi xảy ra sự việc");
                    return;
                }
                Long ticketId = parseLongOrNull(request.getParameter("relatedTicketId"));
                Complaint created = service.create(
                        sessionUserId(request),
                        branchId,
                        request.getParameter("category"),
                        request.getParameter("subject"),
                        request.getParameter("message"),
                        ticketId);
                sendOk(response, created);
                return;
            }

            if (path.endsWith("/reply")) {
                if (!isManager(scope)) {
                    sendForbidden(response, "Chỉ Admin/Manager được phản hồi");
                    return;
                }
                Long id = parseId(path);
                if (!canManageComplaint(scope, id, response)) return;
                Long managerId = sessionUserId(request);
                sendOk(response, service.reply(managerId, id, request.getParameter("reply")));
                return;
            }

            if (path.endsWith("/status")) {
                if (!isManager(scope)) {
                    sendForbidden(response, "Chỉ Admin/Manager được đổi trạng thái");
                    return;
                }
                Long id = parseId(path);
                if (!canManageComplaint(scope, id, response)) return;
                Long managerId = sessionUserId(request);
                sendOk(response, service.transition(managerId, id, request.getParameter("status")));
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

    private boolean canManageComplaint(AccessScope scope, long id, HttpServletResponse response)
            throws IOException {
        if (scope.role() == Role.ADMIN) return true;
        Complaint complaint = service.findById(id);
        if (complaint.branchId() == null || !scope.includesBranch(complaint.branchId())) {
            sendForbidden(response, "Bạn không phụ trách chi nhánh của khiếu nại này");
            return false;
        }
        return true;
    }

    private Long parseId(String path) {
        String[] parts = path.split("/");
        if (parts.length < 2) throw new NumberFormatException("missing id");
        return Long.parseLong(parts[1]);
    }

    private Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Long.parseLong(raw.trim());
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
