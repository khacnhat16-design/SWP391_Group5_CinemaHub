package com.cinema.controller;

import com.cinema.model.branch.Branch;

import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.model.branch.Branch;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.model.movie.Movie;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.service.movie.MovieService;
import com.cinema.model.movie.Genre;
import com.cinema.dao.movie.GenreDAO;
import com.cinema.dao.review.ReviewDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST API for movie reviews (RSC-2 / R1).
 *
 * <ul>
 *   <li>GET — Admin/Branch Manager: catalog view; Branch Manager/Staff
 *       root queries return only PUBLISHED movies within the effective screening window (Req 2.5).</li>
 *   <li>POST/PUT (create, update, publish, archive) — Admin only.</li>
 * </ul>
 */
public class MovieController extends HttpServlet {
    private MovieService movieService;
    private ReviewDAO reviewDao;
    private GenreDAO genreDao;

    @Override
    public void init() throws ServletException {
        this.movieService = new MovieService(new MovieDAO());
        this.reviewDao = new ReviewDAO();
        this.genreDao = new GenreDAO();
    }

    private static String readGenreName(HttpServletRequest request) {
        return normalizeGenreName(request.getParameter("name"));
    }

    private static String readGenreName(Function<String, String> parameter) {
        return normalizeGenreName(parameter.apply("name"));
    }

    private static String normalizeGenreName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new ServiceException.Validation("Tên thể loại bắt buộc và tối đa 100 ký tự");
        }
        return name.trim();
    }

    private void ensureUniqueGenreName(String name, Long exceptId) throws Exception {
        for (Genre genre : genreDao.findAll()) {
            if (!genre.id().equals(exceptId) && genre.name().equalsIgnoreCase(name)) {
                throw new ServiceException.Conflict("Tên thể loại đã tồn tại");
            }
        }
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

            boolean canManageMovies = scope.role() == Role.ADMIN
                    || scope.role() == Role.BRANCH_MANAGER;
            if (canManageMovies && pathInfo != null && pathInfo.equals("/search")) {
                // Server-side search/filter/sort/pagination for the management table.
                MovieDAO.MovieQuery q = new MovieDAO.MovieQuery();
                q.search = request.getParameter("q");
                q.status = request.getParameter("status");
                q.genre = request.getParameter("genre");
                q.rating = request.getParameter("rating");
                q.sortBy = request.getParameter("sortBy");
                q.sortDir = request.getParameter("sortDir");
                q.offset = parseInt(request.getParameter("offset"), 0);
                q.limit = parseInt(request.getParameter("limit"), 20);
                if (scope.role() == Role.BRANCH_MANAGER) {
                    q.branchIds = scope.branchIds();
                }
                long total = movieService.countSearch(q);
                List<Movie> items = enrichWithReviews(movieService.search(q));
                sendOk(response, java.util.Map.of(
                        "items", items,
                        "total", total,
                        "offset", q.offset,
                        "limit", q.limit
                ));
            } else if (canManageMovies && pathInfo != null && !pathInfo.equals("/")) {
                Long movieId = parseLongId(pathInfo.substring(1));
                Movie movie = (scope.role() == Role.BRANCH_MANAGER
                        ? movieService.findByIdForBranches(movieId, scope.branchIds())
                        : movieService.listAll().stream()
                            .filter(m -> m.id().equals(movieId))
                            .findFirst())
                    .orElseThrow(() -> new ServiceException.NotFound("Phim không tồn tại"));
                List<Movie> single = enrichWithReviews(List.of(movie));
                sendOk(response, single.get(0));
            } else if (scope.role() == Role.ADMIN) {
                sendOk(response, enrichWithReviews(movieService.listAll()));
            } else if (scope.role() == Role.BRANCH_MANAGER || scope.role() == Role.BRANCH_STAFF) {
                if (pathInfo != null && !pathInfo.equals("/")) {
                    sendForbidden(response, "Không có quyền truy cập phim này");
                    return;
                }
                // Req 2.5 — danh sách dùng khi xếp lịch chỉ gồm phim đang hiệu lực.
                LocalDate date = parseDate(request.getParameter("date"));
                List<Movie> movies = movieService.listSelectableForScheduling(
                        date, scope.branchIds());
                sendOk(response, enrichWithReviews(movies));
            } else if (scope.role() == Role.CUSTOMER || scope.isGuest()) {
                // Customer/Guest cũng cần list phim (booking flow, trang khám phá).
                sendOk(response, enrichWithReviews(movieService.listAll()));
            } else {
                sendForbidden(response, "Không có quyền truy cập");
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
            if ("/genres".equals(request.getPathInfo())) {
                if (scope == null || scope.role() != Role.ADMIN) {
                    sendForbidden(response, "Chỉ Admin mới được quản lý thể loại phim");
                    return;
                }
                ensureUniqueGenreName(readGenreName(request), null);
                sendOk(response, genreDao.insert(readGenreName(request)));
                return;
            }
            if (scope == null || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể tạo phim");
                return;
            }

            Long movieId = parseLong(request.getParameter("movieId"));
            if (movieId == null) {
                sendBadRequest(response, "Thiếu movieId");
                return;
            }
            if (scope == null || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể cập nhật phim");
                return;
            }
            Map<String, String> parameters = FormParameters.readPut(request);
            Long movieId = parseLongId(path.substring(1));
            String action = parameters.get("action");

            if ("publish".equals(action)) {
                sendOk(response, movieService.publishMovie(movieId));
            } else if ("archive".equals(action)) {
                sendOk(response, movieService.archiveMovie(movieId));
            } else if ("restore".equals(action)) {
                sendOk(response, movieService.restoreMovie(movieId));
            } else {
                MovieService.MovieInput input = readInput(parameters::get);
                sendOk(response, movieService.updateMovie(movieId, input));
            }
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

    /**
     * Gắn averageRating + reviewCount vào từng Movie (1 batch query tránh N+1).
     * Nếu lỗi (DB chưa có bảng review) thì set mặc định 0 để response vẫn thành công.
     */
    private List<Movie> enrichWithReviews(List<Movie> movies) {
        if (movies == null || movies.isEmpty()) return movies;
        try {
            List<Long> ids = movies.stream().map(Movie::id).filter(java.util.Objects::nonNull).collect(Collectors.toList());
            if (ids.isEmpty()) return movies;
            Map<Long, ReviewDAO.ReviewStats> stats = reviewDao.statsByMovieIds(ids);
            for (Movie m : movies) {
                ReviewDAO.ReviewStats s = stats.get(m.id());
                if (s != null) {
                    m.setAverageRating(s.average);
                    m.setReviewCount(s.count);
                } else {
                    m.setAverageRating(0d);
                    m.setReviewCount(0L);
                }
            }
        } catch (Exception e) {
            // Best-effort: không block API nếu bảng review chưa sẵn sàng.
            for (Movie m : movies) {
                if (m.averageRating() == null) m.setAverageRating(0d);
                if (m.reviewCount() == null) m.setReviewCount(0L);
            }
        }
        return movies;
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
