package com.cinema.controller;

import com.cinema.common.ErrorEnvelope;
import com.cinema.common.FormParameters;
import com.cinema.common.SerializationUtil;
import com.cinema.common.ServiceException;
import com.cinema.dao.movie.GenreDAO;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.dao.review.ReviewDAO;
import com.cinema.filter.AuthFilter;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.model.movie.Genre;
import com.cinema.model.movie.Movie;
import com.cinema.service.movie.MovieService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** REST API for movie catalog and genre management. */
public class MovieController extends HttpServlet {
    private MovieService movieService;
    private ReviewDAO reviewDao;
    private GenreDAO genreDao;

    @Override
    public void init() throws ServletException {
        movieService = new MovieService(new MovieDAO());
        reviewDao = new ReviewDAO();
        genreDao = new GenreDAO();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            String path = request.getPathInfo();
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if ("/genres".equals(path)) {
                sendOk(response, genreDao.findAll());
                return;
            }

            if ("/search".equals(path)) {
                if (scope == null || (scope.role() != Role.ADMIN
                        && scope.role() != Role.BRANCH_MANAGER)) {
                    sendForbidden(response, "Không có quyền tìm kiếm danh sách phim quản lý");
                    return;
                }
                MovieDAO.MovieQuery query = new MovieDAO.MovieQuery();
                query.search = request.getParameter("q");
                query.status = request.getParameter("status");
                query.genre = request.getParameter("genre");
                query.rating = request.getParameter("rating");
                query.sortBy = request.getParameter("sortBy");
                query.sortDir = request.getParameter("sortDir");
                query.offset = Math.max(parseInt(request.getParameter("offset"), 0), 0);
                query.limit = Math.min(Math.max(parseInt(request.getParameter("limit"), 20), 1), 200);
                if (scope.role() == Role.BRANCH_MANAGER) {
                    query.branchIds = scope.branchIds();
                }
                sendOk(response, Map.of(
                        "items", enrichWithReviews(movieService.search(query)),
                        "total", movieService.countSearch(query),
                        "offset", query.offset,
                        "limit", query.limit));
                return;
            }

            if (path != null && !path.isBlank() && !"/".equals(path)) {
                if (scope == null || (scope.role() != Role.ADMIN
                        && scope.role() != Role.BRANCH_MANAGER)) {
                    sendForbidden(response, "Không có quyền truy cập phim này");
                    return;
                }
                Long movieId = parseLong(path.substring(1));
                Movie movie = (scope.role() == Role.BRANCH_MANAGER
                        ? movieService.findByIdForBranches(movieId, scope.branchIds())
                        : movieService.listAll().stream()
                                .filter(item -> item.id().equals(movieId))
                                .findFirst())
                        .orElseThrow(() -> new ServiceException.NotFound("Phim không tồn tại"));
                sendOk(response, enrichWithReviews(List.of(movie)).get(0));
                return;
            }

            if (scope != null && scope.role() == Role.ADMIN) {
                sendOk(response, enrichWithReviews(movieService.listAll()));
            } else if (scope != null && (scope.role() == Role.BRANCH_MANAGER
                    || scope.role() == Role.BRANCH_STAFF)) {
                LocalDate date = parseDate(request.getParameter("date"));
                sendOk(response, enrichWithReviews(
                        movieService.listSelectableForScheduling(date, scope.branchIds())));
            } else {
                sendOk(response, enrichWithReviews(movieService.listPublishedAll()));
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
            if (scope == null || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể quản lý phim và thể loại");
                return;
            }
            String path = request.getPathInfo();
            if ("/genres".equals(path)) {
                String name = normalizeGenreName(request.getParameter("name"));
                ensureUniqueGenreName(name, null);
                sendOk(response, genreDao.insert(name));
                return;
            }
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                sendBadRequest(response, "Đường dẫn tạo phim không hợp lệ");
                return;
            }
            sendOk(response, movieService.createMovie(readInput(request::getParameter)));
        } catch (NumberFormatException e) {
            sendBadRequest(response, "Tham số không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        try {
            AccessScope scope = (AccessScope) request.getAttribute(AuthFilter.SCOPE_ATTRIBUTE);
            if (scope == null || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể cập nhật phim và thể loại");
                return;
            }
            Map<String, String> parameters = FormParameters.readPut(request);
            String path = request.getPathInfo();
            if (path != null && path.startsWith("/genres/")) {
                long genreId = parseLong(path.substring("/genres/".length()));
                String name = normalizeGenreName(parameters.get("name"));
                ensureUniqueGenreName(name, genreId);
                sendOk(response, genreDao.update(genreId, name));
                return;
            }
            if (path == null || path.length() < 2) {
                sendBadRequest(response, "Thiếu movie id");
                return;
            }
            long movieId = parseLong(path.substring(1));
            String action = parameters.get("action");
            if ("publish".equals(action)) {
                sendOk(response, movieService.publishMovie(movieId));
            } else if ("archive".equals(action)) {
                sendOk(response, movieService.archiveMovie(movieId));
            } else if ("restore".equals(action)) {
                sendOk(response, movieService.restoreMovie(movieId));
            } else {
                sendOk(response, movieService.updateMovie(movieId, readInput(parameters::get)));
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
            if (scope == null || scope.role() != Role.ADMIN) {
                sendForbidden(response, "Chỉ Admin mới có thể xóa thể loại phim");
                return;
            }
            String path = request.getPathInfo();
            if (path == null || !path.startsWith("/genres/")) {
                sendBadRequest(response, "Đường dẫn xóa thể loại không hợp lệ");
                return;
            }
            long genreId = parseLong(path.substring("/genres/".length()));
            genreDao.delete(genreId);
            sendOk(response, Map.of("deleted", genreId));
        } catch (NumberFormatException e) {
            sendBadRequest(response, "ID không hợp lệ");
        } catch (Exception e) {
            handleException(response, e);
        }
    }

    private MovieService.MovieInput readInput(Function<String, String> parameter) {
        String duration = parameter.apply("durationMin");
        Integer durationMin = duration == null || duration.isBlank()
                ? null : Integer.valueOf(duration.trim());
        return new MovieService.MovieInput(
                parameter.apply("title"),
                durationMin,
                parameter.apply("genre"),
                parameter.apply("rating"),
                parseOptionalDate(parameter.apply("releaseDate")),
                parseOptionalDate(parameter.apply("endDate")),
                parameter.apply("posterUrl"),
                parameter.apply("description"),
                parameter.apply("author"),
                parameter.apply("status"));
    }

    private LocalDate parseDate(String raw) {
        return raw == null || raw.isBlank() ? LocalDate.now() : LocalDate.parse(raw.trim());
    }

    private LocalDate parseOptionalDate(String raw) {
        return raw == null || raw.isBlank() ? null : LocalDate.parse(raw.trim());
    }

    private long parseLong(String raw) {
        return Long.parseLong(raw.trim());
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String normalizeGenreName(String name) {
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

    private List<Movie> enrichWithReviews(List<Movie> movies) throws Exception {
        if (movies == null || movies.isEmpty()) return movies;
        List<Long> ids = movies.stream()
                .map(Movie::id)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) return movies;
        Map<Long, ReviewDAO.ReviewStats> stats = reviewDao.statsByMovieIds(ids);
        for (Movie movie : movies) {
            ReviewDAO.ReviewStats movieStats = stats.get(movie.id());
            movie.setAverageRating(movieStats == null ? 0d : movieStats.average);
            movie.setReviewCount(movieStats == null ? 0L : movieStats.count);
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

    private void handleException(HttpServletResponse response, Exception e) throws IOException {
        if (e instanceof ServiceException service) {
            response.setStatus(service.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(SerializationUtil.toJson(
                    new ErrorEnvelope(service.code(), service.getMessage())));
            return;
        }
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SerializationUtil.toJson(
                new ErrorEnvelope("INTERNAL_ERROR", "Lỗi hệ thống")));
        getServletContext().log("Movie request failed", e);
    }
}
