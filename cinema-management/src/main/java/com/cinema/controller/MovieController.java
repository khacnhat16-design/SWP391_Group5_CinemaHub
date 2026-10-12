package com.cinema.controller;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.model.branch.Branch;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.model.review.Review;
import com.cinema.dao.review.ReviewDAO;
import com.cinema.service.review.ReviewService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for movie reviews (RSC-2 / R1).
 *
 * <ul>
 *   <li>GET  /api/reviews?movieId=&offset=&limit= — public list + average.</li>
 *   <li>POST /api/reviews — Customer upsert (rating + comment).</li>
 *   <li>DELETE /api/reviews/{id} — Customer deletes own review.</li>
 * </ul>
 */
public class ReviewController extends HttpServlet {

    private ReviewService service;

    @Override
    public void init() throws ServletException {
        this.service = new ReviewService(new ReviewDAO());
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            // Manager view: tất cả review của phim đã/đang chiếu tại một chi nhánh.
            if ("true".equalsIgnoreCase(request.getParameter("branches"))) {
                handleListBranches(request, response);
                return;
            }
            String branchParam = request.getParameter("branchId");
            if (branchParam != null && !branchParam.isBlank()) {
                handleListByBranch(request, response);
                return;
            }

            Long movieId = parseLong(request.getParameter("movieId"));
            if (movieId == null) {
                sendBadRequest(response, "Thiếu movieId hoặc branchId");
                return;
            }
            int offset = parseInt(request.getParameter("offset"), 0);
            int limit = parseInt(request.getParameter("limit"), 20);

            List<Review> items = service.listByMovie(movieId, offset, limit);
            long total = service.countByMovie(movieId);
            double avg = service.averageByMovie(movieId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", total);
            data.put("average", avg);
            data.put("offset", offset);
            data.put("limit", limit);
            sendOk(response, data);
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private void handleListBranches(HttpServletRequest request, HttpServletResponse response) throws Exception {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || (scope.role() != Role.ADMIN
                && scope.role() != Role.BRANCH_MANAGER
                && scope.role() != Role.BRANCH_STAFF)) {
            sendForbidden(response, "Tài khoản hiện tại không có quyền xem đánh giá chi nhánh");
            return;
        }
        List<Branch> branches = new BranchDAO().findAllActive();
        if (scope.role() != Role.ADMIN) {
            branches = branches.stream()
                    .filter(branch -> scope.includesBranch(branch.id()))
                    .toList();
        }
        sendOk(response, branches);
    }

    /**
     * GET /api/reviews?branchId=&offset=&limit=
     * Permission: BRANCH_MANAGER (only own branches), BRANCH_STAFF (read-only),
     * ADMIN (all branches). Đánh giá "Phim ở rạp X".
     */
    private void handleListByBranch(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
        if (scope == null || scope.isGuest()) {
            sendForbidden(response, "Cần đăng nhập để xem đánh giá của chi nhánh");
            return;
        }
        if (scope.role() != Role.BRANCH_MANAGER
                && scope.role() != Role.BRANCH_STAFF
                && scope.role() != Role.ADMIN) {
            sendForbidden(response, "Tài khoản hiện tại không có quyền xem đánh giá chi nhánh");
            return;
        }
        Long branchId = parseLong(request.getParameter("branchId"));
        if (branchId == null) {
            sendBadRequest(response, "Thiếu branchId");
            return;
        }
        if (!scope.includesBranch(branchId)) {
            sendForbidden(response, "Bạn không phụ trách chi nhánh này");
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branchId", branchId);
        Long movieId = parseLong(request.getParameter("movieId"));
        if (movieId == null) {
            data.put("items", service.movieSummariesByBranch(branchId));
            data.put("total", service.countByBranch(branchId));
            data.put("average", service.averageByBranch(branchId));
            sendOk(response, data);
            return;
        }

        int offset = parseInt(request.getParameter("offset"), 0);
        int limit = Math.min(Math.max(parseInt(request.getParameter("limit"), 50), 1), 100);
        List<Review> items = service.listByBranchAndMovie(branchId, movieId, offset, limit);
        long total = service.countByBranchAndMovie(branchId, movieId);
        data.put("items", items);
        data.put("total", total);
        data.put("average", total == 0 ? 0d : service.averageByMovie(movieId));
        data.put("movieId", movieId);
        data.put("offset", offset);
        data.put("limit", limit);
        sendOk(response, data);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
                sendForbidden(response, "Chỉ khách hàng đăng nhập mới có thể viết review");
                return;
            }

            Long movieId = parseLong(request.getParameter("movieId"));
            if (movieId == null) {
                sendBadRequest(response, "Thiếu movieId");
                return;
            }
            int rating = parseInt(request.getParameter("rating"), 0);
            String comment = request.getParameter("comment");

            Review saved = service.upsert(sessionUserId(request), movieId, rating, comment);
            sendOk(response, saved);
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Tham số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.isGuest() || scope.role() != Role.CUSTOMER) {
                sendForbidden(response, "Chỉ khách hàng mới có thể xóa review của mình");
                return;
            }
            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.length() < 2) {
                sendBadRequest(response, "Thiếu review id");
                return;
            }
            Long id = parseLong(pathInfo.substring(1));
            if (id == null) {
                sendBadRequest(response, "ID không hợp lệ");
                return;
            }
            service.deleteOwn(id, sessionUserId(request));
            sendOk(response, Map.of("deleted", id));
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers (mirror MovieController)
    // -----------------------------------------------------------------------
    private Long sessionUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object userId = session.getAttribute("userId");
        return userId instanceof Long value ? value : null;
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Long.parseLong(raw.trim());
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return fallback; }
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
