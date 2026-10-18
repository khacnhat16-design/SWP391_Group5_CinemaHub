package com.cinema.controller;

import com.cinema.model.auth.User;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.dao.auth.StaffBranchAssignmentDAO;
import com.cinema.service.notification.EmailService;
import com.cinema.dao.legacy.UserDAOext;
import com.cinema.dao.legacy.UserDAOext.UserRecord;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Admin user management — CRUD nội bộ CinemaHub.
 *
 * <p>GET  /api/users?role=&q=&page=&pageSize=  -> danh sách
 * <p>GET  /api/users/{id}                       -> chi tiết
 * <p>POST /api/users                            -> tạo user (ADMIN/MANAGER/STAFF hoặc CUSTOMER)
 * <p>PUT  /api/users/{id}                       -> cập nhật name/phone/status
 * <p>POST /api/users/{id}/status                -> đổi status (ACTIVE/LOCKED/INACTIVE)
 * <p>DELETE /api/users/{id}                     -> soft-delete (status=INACTIVE)
 *
 * <p>Admin quản lý toàn bộ user; Branch Manager có thể đọc và đổi trạng thái
 * nhân viên đang được gán vào chi nhánh mình phụ trách.
 */
public class AdminUsersServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(AdminUsersServlet.class.getName());
    private final UserDAOext dao = new UserDAOext();
    private final StaffBranchAssignmentDAO assignmentDao = new StaffBranchAssignmentDAO();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = requireReadAccess(request, response);
            if (scope == null) return;

            // Chuẩn hoá theo convention list-page.js (offset/limit + tổng số bản ghi):
            // - Frontend gửi q, role, offset, limit; backend map offset→page để DAO
            //   dùng SQL OFFSET/FETCH NEXT.
            // - Bổ sung countSearch để trả total chính xác phục vụ phân trang.
            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (pathInfo.isEmpty() || "/".equals(pathInfo)) {
                String q = request.getParameter("q");
                String roleParam = request.getParameter("role");
                Role filter = parseRole(roleParam);
                int offset = parseIntOr(request.getParameter("offset"), 0);
                int limit = parseIntOr(request.getParameter("limit"), 20);
                if (limit <= 0 || limit > 200) limit = 20;
                int page = offset / limit;
                List<UserRecord> rows = scope.role() == Role.ADMIN
                        ? dao.list(q, filter, page, limit)
                        : dao.listByBranch(q, filter, scope.branchIds().iterator().next(), page, limit);
                long total = scope.role() == Role.ADMIN
                        ? dao.count(q, filter)
                        : dao.countByBranch(q, filter, scope.branchIds().iterator().next());
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("items", rows);
                resp.put("total", total);
                resp.put("offset", offset);
                resp.put("limit", limit);
                sendOk(response, resp);
                return;
            }
            if (pathInfo.startsWith("/")) {
                long id = Long.parseLong(pathInfo.substring(1));
                UserRecord r = dao.findById(id);
                if (r == null) {
                    sendError(response, 404, "NOT_FOUND", "User không tồn tại");
                    return;
                }
                if (scope.role() == Role.BRANCH_MANAGER
                        && scope.branchIds().stream().noneMatch(branchId -> {
                            try {
                                return assignmentDao.hasActiveAssignment(id, branchId);
                            } catch (Exception e) {
                                return false;
                            }
                        })) {
                    sendError(response, 403, "FORBIDDEN", "User không thuộc chi nhánh của bạn");
                    return;
                }
                sendOk(response, r);
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
            AccessScope scope = requireManagerOrAdmin(request, response);
            if (scope == null) return;

            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (pathInfo.isEmpty() || "/".equals(pathInfo)) {
                // Tạo user — hệ thống luôn tự sinh mật khẩu random và gửi qua Gmail SMTP.
                String email = requireParam(request, "email");
                String phone = requireParam(request, "phone");
                String fullName = requireParam(request, "fullName");
                Role role = parseRole(requireParam(request, "role"));
                if (role == null) {
                    sendError(response, 400, "VALIDATION", "Vai trò không hợp lệ");
                    return;
                }
                // BRM chỉ được tạo STAFF (không tạo BRM khác, không tạo Admin/Customer).
                if (scope.role() == Role.BRANCH_MANAGER) {
                    if (role != Role.BRANCH_STAFF) {
                        sendError(response, 403, "FORBIDDEN",
                                "Manager chỉ có thể tạo tài khoản Nhân viên chi nhánh (BRANCH_STAFF).");
                        return;
                    }
                    // BRM phải có ít nhất 1 branch đang quản lý.
                    if (scope.branchIds().isEmpty()) {
                        sendError(response, 403, "FORBIDDEN",
                                "Tài khoản Manager chưa được gán chi nhánh nào.");
                        return;
                    }
                }
                UserDAOext.CreateResult result = dao.create(email, phone, fullName, role);

                boolean emailSent = false;
                if (result.temporaryPassword() != null) {
                    try {
                        new EmailService().sendAccountCredentials(email, fullName, result.temporaryPassword());
                        emailSent = true;
                    } catch (Exception emailErr) {
                        // Không fail toàn bộ flow — user đã được tạo, admin có thể reset password sau.
                        // Tạm thời trả plaintext password trong response để admin copy nếu cần.
                        logger.warning("Send credentials email failed: " + emailErr.getMessage());
                    }
                }

                // Nếu là BRM tạo STAFF, tự động gán vào branch của BRM.
                Map<String, Object> extra = new LinkedHashMap<>();
                if (scope.role() == Role.BRANCH_MANAGER
                        && role == Role.BRANCH_STAFF
                        && !scope.branchIds().isEmpty()) {
                    long branchId = scope.branchIds().iterator().next();
                    try {
                        var assignmentService = new com.cinema.service.auth.AccessAssignmentService(
                                new com.cinema.dao.auth.UserDAO(),
                                new com.cinema.dao.auth.StaffBranchAssignmentDAO());
                        var brmScope = com.cinema.model.auth.AccessScope.forAuthenticatedUser(
                                Role.BRANCH_MANAGER, scope.branchIds(), scope.userId());
                        assignmentService.assignBranchWithScope(result.id(), branchId,
                                scope.userId(), brmScope);
                        extra.put("assignedBranchId", branchId);
                    } catch (Exception assignErr) {
                        // Không rollback user — chỉ log và trả warning.
                        extra.put("assignmentError", assignErr.getMessage());
                        logger.warning("Auto-assign STAFF to branch failed: " + assignErr.getMessage());
                    }
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", result.id());
                payload.put("ok", true);
                payload.put("emailSent", emailSent);
                payload.put("generatedPassword", emailSent ? null : result.temporaryPassword());
                payload.putAll(extra);
                sendOk(response, payload);
                return;
            }
            if (pathInfo.endsWith("/status")) {
                String idStr = pathInfo.substring(1, pathInfo.length() - "/status".length());
                long id = Long.parseLong(idStr);
                if (scope.role() == Role.BRANCH_MANAGER) {
                    UserRecord user = dao.findById(id);
                    if (user == null) {
                        sendError(response, 404, "NOT_FOUND", "User không tồn tại");
                        return;
                    }
                    boolean assignedToManager = false;
                    if (user.role() == Role.BRANCH_STAFF) {
                        for (Long branchId : scope.branchIds()) {
                            if (assignmentDao.hasActiveAssignment(id, branchId)) {
                                assignedToManager = true;
                                break;
                            }
                        }
                    }
                    if (!assignedToManager) {
                        sendError(response, 403, "FORBIDDEN",
                                "Manager chỉ được đổi trạng thái nhân viên thuộc chi nhánh mình.");
                        return;
                    }
                }
                String status = requireParam(request, "status");
                if (!List.of("ACTIVE", "LOCKED", "INACTIVE").contains(status)) {
                    sendError(response, 400, "VALIDATION", "Trạng thái tài khoản không hợp lệ");
                    return;
                }
                dao.updateStatus(id, status);
                sendOk(response, Map.of("ok", true));
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Endpoint không hợp lệ");
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = requireAdmin(request, response);
            if (scope == null) return;

            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (pathInfo.startsWith("/") && pathInfo.length() > 1) {
                long id = Long.parseLong(pathInfo.substring(1));
                String fullName = request.getParameter("fullName");
                String phone = request.getParameter("phone");
                String status = request.getParameter("status");
                dao.update(id, fullName, phone, status);
                sendOk(response, Map.of("ok", true));
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Thiếu id");
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = requireAdmin(request, response);
            if (scope == null) return;

            String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
            if (pathInfo.startsWith("/") && pathInfo.length() > 1) {
                long id = Long.parseLong(pathInfo.substring(1));
                dao.softDelete(id);
                sendOk(response, Map.of("ok", true));
                return;
            }
            sendError(response, 400, "BAD_REQUEST", "Thiếu id");
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", e.getMessage());
        }
    }

    private AccessScope requireAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null) {
            sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
            return null;
        }
        if (scope.role() != Role.ADMIN) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin mới có quyền quản lý users");
            return null;
        }
        return scope;
    }

    /**
     * Cho phép Admin hoặc Branch Manager — Manager cần có ít nhất một chi nhánh
     * để tạo nhân viên hoặc đổi trạng thái nhân viên trong phạm vi của mình.
     */
    private AccessScope requireManagerOrAdmin(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null) {
            sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
            return null;
        }
        if (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER) {
            sendError(response, 403, "FORBIDDEN",
                    "Chỉ Admin hoặc Manager mới có thể thực hiện thao tác này");
            return null;
        }
        if (scope.role() == Role.BRANCH_MANAGER && scope.branchIds().isEmpty()) {
            sendError(response, 403, "FORBIDDEN",
                    "Tài khoản Manager chưa được gán chi nhánh nào");
            return null;
        }
        return scope;
    }

    private AccessScope requireReadAccess(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) {
            sendError(response, 401, "UNAUTHORIZED", "Yêu cầu đăng nhập");
            return null;
        }
        if (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER) {
            sendError(response, 403, "FORBIDDEN", "Chỉ Admin hoặc Manager mới có thể xem users");
            return null;
        }
        if (scope.role() == Role.BRANCH_MANAGER && scope.branchIds().isEmpty()) {
            sendError(response, 403, "FORBIDDEN", "Tài khoản chưa được gán chi nhánh");
            return null;
        }
        return scope;
    }

    private Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Role.valueOf(raw.trim().toUpperCase()); }
        catch (Exception e) {
            throw new ServiceException.Validation("Role không hợp lệ: " + raw);
        }
    }

    private String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation("Thiếu tham số: " + name);
        }
        return value.trim();
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
