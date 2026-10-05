package com.cinema.web;

import com.cinema.branch.Branch;
import com.cinema.branch.BranchDAO;
import com.cinema.branch.BranchService;
import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.FormParameters;
import com.cinema.filter.AuthFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

/** REST API controller for branch management and active-branch lookup. */
public class BranchController extends HttpServlet {
    private BranchService branchService;

    @Override
    public void init() throws ServletException {
        try {
            this.branchService = new BranchService(new BranchDAO());
        } catch (Exception e) {
            throw new ServletException("Failed to initialize BranchService", e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null) {
                sendForbidden(response, "Cần đăng nhập");
                return;
            }

            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.equals("/")) {
                // Admin sees all branches. Managers only see assigned branches so
                // management dropdowns cannot offer a branch outside their scope.
                if (scope.role() == Role.ADMIN) {
                    listBranches(response);
                } else if (scope.role() == Role.BRANCH_MANAGER) {
                    listActiveBranches(response, scope.branchIds());
                } else {
                    // Staff, customers and guests need active branches for ticketing.
                    listActiveBranches(response, null);
                }
                return;
            }
            // /branch/{id} và /branch/search — yêu cầu đăng nhập (không guest)
            if (scope.isGuest()) {
                sendForbidden(response, "Cần đăng nhập để xem chi tiết chi nhánh");
                return;
            }
            if (scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể quản lý chi nhánh");
                return;
            } else if (pathInfo.equals("/search")) {
                BranchDAO dao = new BranchDAO();
                BranchDAO.BranchQuery q = new BranchDAO.BranchQuery();
                q.search = request.getParameter("q");
                q.status = request.getParameter("status");
                q.sortBy = request.getParameter("sortBy");
                q.sortDir = request.getParameter("sortDir");
                q.offset = parseInt(request.getParameter("offset"), 0);
                q.limit = parseInt(request.getParameter("limit"), 20);
                long total = dao.countSearch(q);
                List<Branch> items = dao.search(q);
                sendOk(response, java.util.Map.of(
                        "items", items,
                        "total", total,
                        "offset", q.offset,
                        "limit", q.limit
                ));
            } else {
                // /branch/{id} → lấy 1 branch (dùng cho edit form / detail panel).
                Long branchId = parseLongId(pathInfo.substring(1));
                getBranch(branchId, response);
            }
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            sendInternalError(response, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest() || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể tạo chi nhánh");
                return;
            }

            String name = request.getParameter("name");
            String address = request.getParameter("address");
            String phone = request.getParameter("phone");
            Branch branch = branchService.createBranch(name, address, phone);
            sendOk(response, branch);
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest() || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể cập nhật chi nhánh");
                return;
            }

            // pathInfo luôn dạng "/{id}" với servlet mapping /branch/*.
            // Trim "/" cuối nếu có và lấy phần tử đầu tiên để tránh NumberFormatException
            // khi client gọi /branch/5/ (có trailing slash).
            String pathInfo = request.getPathInfo();
            String idPart = (pathInfo == null) ? "" : pathInfo.replaceAll("^/+", "");
            if (idPart.contains("/")) {
                idPart = idPart.substring(0, idPart.indexOf('/'));
            }
            if (idPart.isEmpty()) {
                sendBadRequest(response, "Thiếu ID chi nhánh");
                return;
            }
            Long branchId = parseLongId(idPart);
            var parameters = FormParameters.readPut(request);
            String name = parameters.get("name");
            String address = parameters.get("address");
            String phone = parameters.get("phone");
            String action = parameters.get("action");

            if ("deactivate".equals(action)) {
                Branch deactivated = branchService.deactivateBranch(branchId);
                sendOk(response, deactivated);
            } else {
                Branch updated = branchService.updateBranch(branchId, name, address, phone);
                sendOk(response, updated);
            }
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers: parse + render list/get/deactivate; không expose.
    // -----------------------------------------------------------------------

    private void listBranches(HttpServletResponse response) throws Exception {
        BranchDAO dao = new BranchDAO();
        List<Branch> branches = dao.findAll();
        sendOk(response, branches);
    }

    private void listActiveBranches(HttpServletResponse response, java.util.Set<Long> allowedBranchIds)
            throws Exception {
        BranchDAO dao = new BranchDAO();
        sendOk(response, dao.findAll().stream()
                .filter(branch -> "ACTIVE".equalsIgnoreCase(branch.status()))
                .filter(branch -> allowedBranchIds == null || allowedBranchIds.contains(branch.id()))
                .map(branch -> new BranchSummary(branch.id(), branch.name(), branch.address(),
                        branch.phone(), branch.status()))
                .toList());
    }

    private record BranchSummary(Long id, String name, String address, String phone, String status) { }

    private void getBranch(Long branchId, HttpServletResponse response) throws Exception {
        BranchDAO dao = new BranchDAO();
        Branch branch = dao.findById(branchId)
            .orElseThrow(() -> new com.cinema.common.ServiceException.NotFound("Chi nhánh không tồn tại"));
        sendOk(response, branch);
    }

    private Long parseLongId(String id) {
        return Long.parseLong(id);
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void sendOk(HttpServletResponse response, Object data) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(data));
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        ErrorEnvelope error = new ErrorEnvelope("FORBIDDEN", message);
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(error));
    }

    private void sendBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        ErrorEnvelope error = new ErrorEnvelope("BAD_REQUEST", message);
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(error));
    }

    private void sendInternalError(HttpServletResponse response, Exception e) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json;charset=UTF-8");
        ErrorEnvelope error = new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống");
        response.getWriter().write(com.cinema.common.SerializationUtil.toJson(error));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof com.cinema.common.ServiceException.Validation) {
            sendBadRequest(response, e.getMessage());
        } else if (e instanceof com.cinema.common.ServiceException.Conflict) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/json;charset=UTF-8");
            ErrorEnvelope error = new ErrorEnvelope("CONFLICT", e.getMessage());
            response.getWriter().write(com.cinema.common.SerializationUtil.toJson(error));
        } else if (e instanceof com.cinema.common.ServiceException.NotFound) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            ErrorEnvelope error = new ErrorEnvelope("NOT_FOUND", e.getMessage());
            response.getWriter().write(com.cinema.common.SerializationUtil.toJson(error));
        } else if (e instanceof com.cinema.common.ServiceException.BusinessRule businessRule) {
            response.setStatus(businessRule.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            ErrorEnvelope error = new ErrorEnvelope(businessRule.code(), businessRule.getMessage());
            response.getWriter().write(com.cinema.common.SerializationUtil.toJson(error));
        } else {
            sendInternalError(response, e);
        }
    }
}
