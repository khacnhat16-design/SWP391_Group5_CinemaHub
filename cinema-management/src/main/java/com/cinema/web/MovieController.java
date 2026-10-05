package com.cinema.web;

import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ErrorEnvelope;
import com.cinema.common.FormParameters;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.filter.AuthFilter;
import com.cinema.movie.Movie;
import com.cinema.movie.MovieDAO;
import com.cinema.movie.MovieService;
import com.cinema.movie.Genre;
import com.cinema.movie.GenreDAO;
import com.cinema.review.ReviewDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST API controller for the centralized movie catalog (Req 2.1-2.6).
 *
 * <p>Access model (mirrors BranchController):
 * <ul>
 *   <li>GET — Admin/Branch Manager: full catalog management; Branch Manager/Staff
 *       root queries return only PUBLISHED movies within the effective screening window (Req 2.5).</li>
 *   <li>POST/PUT (create, update, publish, archive) — Admin/Branch Manager only.</li>
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
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            String pathInfo = request.getPathInfo();
            if (pathInfo != null && pathInfo.equals("/genres")) {
                sendOk(response, genreDao.findAll());
                return;
            }
            if (scope == null) {
                sendForbidden(response, "Cần đăng nhập để truy cập danh mục phim quản trị");
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
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
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
            if (!canManageMovies(scope)) {
                sendForbidden(response, "Chỉ Admin hoặc Quản lý chi nhánh mới có thể tạo phim");
                return;
            }

            MovieService.MovieInput input = readInput(request);
            Movie movie = movieService.createMovie(input);
            sendOk(response, movie);
        } catch (DateTimeParseException e) {
            sendBadRequest(response, "Định dạng ngày không hợp lệ (yyyy-MM-dd)");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            String path = request.getPathInfo();
            if (path != null && path.startsWith("/genres/")) {
                if (scope == null || scope.role() != Role.ADMIN) {
                    sendForbidden(response, "Chỉ Admin mới được quản lý thể loại phim");
                    return;
                }
                long genreId = parseLongId(path.substring("/genres/".length()));
                Map<String, String> parameters = FormParameters.readPut(request);
                String name = readGenreName(parameters::get);
                if (genreDao.findById(genreId).isEmpty()) {
                    throw new ServiceException.NotFound("Thể loại không tồn tại");
                }
                ensureUniqueGenreName(name, genreId);
                sendOk(response, genreDao.update(genreId, name));
                return;
            }
            if (!canManageMovies(scope)) {
                sendForbidden(response, "Chỉ Admin hoặc Quản lý chi nhánh mới có thể cập nhật phim");
                return;
            }
            Map<String, String> parameters = FormParameters.readPut(request);
            Long movieId = parseLongId(path.substring(1));
            String action = parameters.get("action");

            if ("publish".equals(action)) {
                sendOk(response, movieService.publishMovie(movieId));
            } else if ("archive".equals(action)) {
                sendOk(response, movieService.archiveMovie(movieId));
            } else {
                MovieService.MovieInput input = readInput(parameters::get);
                sendOk(response, movieService.updateMovie(movieId, input));
            }
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (DateTimeParseException e) {
            sendBadRequest(response, "Định dạng ngày không hợp lệ (yyyy-MM-dd)");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            String path = request.getPathInfo();
            if (path == null || !path.startsWith("/genres/")) {
                sendBadRequest(response, "Đường dẫn thể loại không hợp lệ");
                return;
            }
            if (scope == null || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới được quản lý thể loại phim");
                return;
            }
            long genreId = parseLongId(path.substring("/genres/".length()));
            genreDao.delete(genreId);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID thể loại không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers: parse multipart/form-data, query string, JSON body; không expose.
    // -----------------------------------------------------------------------

    private MovieService.MovieInput readInput(HttpServletRequest request) throws DateTimeParseException {
        return readInput(request::getParameter);
    }

    private boolean canManageMovies(AccessScope scope) {
        return scope != null
                && (scope.role() == Role.ADMIN || scope.role() == Role.BRANCH_MANAGER);
    }

    private MovieService.MovieInput readInput(Function<String, String> parameter)
            throws DateTimeParseException {
        String durationRaw = parameter.apply("durationMin");
        Integer durationMin = null;
        if (durationRaw != null && !durationRaw.isBlank()) {
            try {
                durationMin = Integer.parseInt(durationRaw.trim());
            } catch (NumberFormatException e) {
                durationMin = -1; // để service báo lỗi validation "số nguyên dương"
            }
        }

        return new MovieService.MovieInput(
            parameter.apply("title"),
            durationMin,
            parameter.apply("genre"),
            parameter.apply("rating"),
            parseDate(parameter.apply("releaseDate")),
            parseDate(parameter.apply("endDate")),
            parameter.apply("posterUrl"),
            parameter.apply("description"),
            parameter.apply("author"),
            parameter.apply("status")
        );
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return LocalDate.parse(raw.trim());
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
        ErrorEnvelope error = new ErrorEnvelope("FORBIDDEN", message);
        response.getWriter().write(SerializationUtil.toJson(error));
    }

    private void sendBadRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        ErrorEnvelope error = new ErrorEnvelope("BAD_REQUEST", message);
        response.getWriter().write(SerializationUtil.toJson(error));
    }

    private void sendInternalError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json;charset=UTF-8");
        ErrorEnvelope error = new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống");
        response.getWriter().write(SerializationUtil.toJson(error));
    }

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            response.setStatus(service.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            ErrorEnvelope error = new ErrorEnvelope(service.code(), service.getMessage());
            response.getWriter().write(SerializationUtil.toJson(error));
        } else {
            sendInternalError(response);
        }
    }
}
