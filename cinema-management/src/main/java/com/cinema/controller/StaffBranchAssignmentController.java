package com.cinema.controller;

import com.cinema.model.auth.Role;
import com.cinema.model.auth.User;

import com.cinema.service.auth.AccessAssignmentService;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.StaffBranchAssignment;
import com.cinema.dao.auth.StaffBranchAssignmentDAO;
import com.cinema.dao.auth.UserDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
/** Admin API for assigning branch managers/staff to active branches. */
public class StaffBranchAssignmentController extends HttpServlet {
    private AccessAssignmentService service;
    private StaffBranchAssignmentDAO assignmentDao;

    @Override
    public void init() {
        this.assignmentDao = new StaffBranchAssignmentDAO();
        this.service = new AccessAssignmentService(new UserDAO(), assignmentDao);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            AccessScope scope = requireScope(request);
            String userId = request.getParameter("userId");
            String branchId = request.getParameter("branchId");
            String path = request.getPathInfo() == null ? "" : request.getPathInfo().replaceAll("^/+", "");
            if ("active-map".equals(path)) {
                if (scope.role() != Role.ADMIN && scope.role() != Role.BRANCH_MANAGER) {
                    throw new ServiceException.Forbidden(
                            "Chỉ Admin/BRM được xem bản phân công");
                }
                var assignments = scope.role() == Role.ADMIN
                        ? assignmentDao.findActiveBranchNamesByUser()
                        : assignmentDao.findActiveBranchNamesByUser(scope.branchIds());
                sendOk(response, assignments);
                return;
            }
            if (branchId != null && !branchId.isBlank()) {
                long id = Long.parseLong(branchId);
                if (!scope.includesBranch(id)) {
                    throw new ServiceException.Forbidden("Không thuộc chi nhánh " + id);
                }
                // all=true trả cả BRM + STAFF (cho popup phân công nhân sự).
                // Mặc định chỉ trả Branch Staff; all=true bổ sung Branch Manager.
                boolean includeManager = "true".equalsIgnoreCase(request.getParameter("all"));
                if (includeManager) {
                    sendOk(response, assignmentDao.findAssignedUsersByBranch(id));
                } else {
                    sendOk(response, assignmentDao.findStaffByBranch(id));
                }
                return;
            }
            if (userId == null || userId.isBlank()) {
                sendError(response, 400, "VALIDATION", "Thiếu userId");
                return;
            }
            if (!scope.can("USER_MANAGE") && scope.role() != com.cinema.model.auth.Role.BRANCH_MANAGER) {
                throw new ServiceException.Forbidden("Không có quyền xem phân công");
            }
            var assignments = assignmentDao.findActiveAssignments(Long.parseLong(userId));
            if (scope.role() == com.cinema.model.auth.Role.BRANCH_MANAGER
                    && assignments.stream().noneMatch(row ->
                    scope.includesBranch(((Number) row.get("branchId")).longValue()))) {
                throw new ServiceException.Forbidden("User không thuộc chi nhánh của bạn");
            }
            sendOk(response, assignments);
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            long staffUserId = Long.parseLong(requireParam(request, "userId"));
            long branchId = Long.parseLong(requireParam(request, "branchId"));
            long actorId = requireSessionUserId(request);
            AccessScope scope = requireManagerOrAdminForBranch(request, branchId);
            StaffBranchAssignment assignment = service.assignBranchWithScope(
                    staffUserId, branchId, actorId, scope);
            sendOk(response, assignment);
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String path = request.getPathInfo() == null ? "" : request.getPathInfo().replaceAll("^/+", "");
            if (path.isBlank()) {
                sendError(response, 400, "VALIDATION", "Thiếu assignmentId");
                return;
            }
            long assignmentId = Long.parseLong(path);
            long actorId = requireSessionUserId(request);
            AccessScope scope = requireScope(request);
            service.endAssignmentWithScope(assignmentId, actorId, scope);
            sendOk(response, java.util.Map.of("ok", true));
        } catch (ServiceException e) {
            sendError(response, e.httpStatus(), e.code(), e.getMessage());
        } catch (Exception e) {
            sendError(response, 500, "INTERNAL_ERROR", "Lỗi hệ thống");
        }
    }

    private static AccessScope requireAdmin(HttpServletRequest request) {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) throw new ServiceException.Unauthorized("Cần đăng nhập");
        if (!scope.can("USER_MANAGE")) throw new ServiceException.Forbidden("Chỉ Admin mới có thể phân công chi nhánh");
        return scope;
    }

    private static AccessScope requireScope(HttpServletRequest request) {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) throw new ServiceException.Unauthorized("Cần đăng nhập");
        return scope;
    }

    /**
     * Admin có thể phân công cho mọi chi nhánh; BRM chỉ được phân công staff
     * cho chính chi nhánh mà họ đang phụ trách. Staff/Customer bị từ chối.
     */
    private static AccessScope requireManagerOrAdminForBranch(HttpServletRequest request,
                                                              long branchId) {
        AccessScope scope = requireScope(request);
        if (scope.role() == Role.ADMIN) return scope;
        if (scope.role() != Role.BRANCH_MANAGER) {
            throw new ServiceException.Forbidden(
                    "Chỉ Admin hoặc Quản lý chi nhánh được phân công nhân viên");
        }
        if (!scope.includesBranch(branchId)) {
            throw new ServiceException.Forbidden(
                    "Bạn chỉ có thể phân công nhân viên cho chi nhánh của mình");
        }
        return scope;
    }

    private static long requireSessionUserId(HttpServletRequest request) {
        Object value = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute("userId");
        if (!(value instanceof Number)) throw new ServiceException.Unauthorized("Cần đăng nhập");
        return ((Number) value).longValue();
    }

    private static String requireParam(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) throw new ServiceException.Validation("Thiếu tham số: " + name);
        return value.trim();
    }

    private static void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(data));
    }

    private static void sendError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(new ErrorEnvelope(code, message)));
    }
}
