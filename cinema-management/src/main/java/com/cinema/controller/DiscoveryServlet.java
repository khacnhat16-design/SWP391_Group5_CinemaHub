package com.cinema.controller;

import com.cinema.model.branch.Branch;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.model.movie.Movie;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.dao.screen.ScreenDAO;
import com.cinema.model.showtime.Showtime;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.service.showtime.ShowtimeService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public movie and showtime discovery backed by SQL Server. */
public final class DiscoveryServlet extends HttpServlet {
    private final MovieDAO movieDao = new MovieDAO();
    private final BranchDAO branchDao = new BranchDAO();
    private final ShowtimeService showtimeService = new ShowtimeService(
            new ShowtimeDAO(), new ScreenDAO(), movieDao, branchDao);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Long movieId = parseLong(request.getParameter("movieId"));
        Long branchId = parseLong(request.getParameter("branchId"));
        LocalDate date = parseDate(request.getParameter("date"));
        String ctx = request.getContextPath();

        response.setContentType("text/html;charset=UTF-8");
        var out = response.getWriter();
        out.printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="%s/assets/css/cinema.css?v=20261003-discovery-group-1">
            <title>Khám phá suất chiếu - CinemaHub</title></head><body>
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="%s/">Cinema<span class="accent">Hub</span></a>
            <nav>%s</nav></div></header>
            <main class="container section"><div class="page-heading"><div>
            <span class="eyebrow">SHOWTIME DISCOVERY</span>
            <h1>Phim đang chiếu và suất chiếu</h1>
            <p class="muted">Lọc theo phim, ngày và chi nhánh.</p></div></div>
            <form class="search-card" method="get" action="%s/discover">
            <label>Phim<select name="movieId"><option value="">Tất cả phim</option>
            """, ctx, ctx, renderAccountNav(request, ctx), ctx);

        try {
            List<Movie> movies = movieDao.findPublished(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
            Map<Long, Movie> movieById = new HashMap<>();
            for (Movie movie : movies) {
                movieById.put(movie.id(), movie);
                out.printf("<option value=\"%d\"%s>%s</option>", movie.id(),
                        movie.id().equals(movieId) ? " selected" : "", escape(movie.title()));
            }

            out.printf("""
                </select></label>
                <label>Ngày<input type="date" name="date" value="%s"></label>
                <label class="branch-filter">Chi nhánh<select name="branchId">
                <option value="">Tất cả chi nhánh</option>
                """, date == null ? "" : date);

            for (Branch branch : branchDao.findAllActive()) {
                out.printf("<option value=\"%d\"%s>%s</option>", branch.id(),
                        branch.id().equals(branchId) ? " selected" : "", escape(branch.name()));
            }
            out.print("""
                </select></label>
                <button class="btn" type="submit">Tìm suất chiếu</button>
                </form><section class="showtime-grid">
                """);

            List<Showtime> showtimes = showtimeService.discover(branchId, movieId, date);
            if (showtimes.isEmpty()) {
                out.print("""
                    <div class="empty-state"><h3>Chưa có suất chiếu phù hợp</h3>
                    <p>Hãy chọn ngày, phim hoặc chi nhánh khác.</p></div>
                    """);
            } else {
                Map<Long, List<Showtime>> showtimesByMovie = new LinkedHashMap<>();
                // Index movies in the same order as MovieDAO.findPublished
                // (release_date DESC, id DESC) so newest film appears first even
                // when its earliest showtime is later than older titles.
                Map<Long, Integer> movieOrder = new HashMap<>();
                int order = 0;
                for (Movie m : movies) {
                    movieOrder.put(m.id(), order++);
                }
                for (Showtime showtime : showtimes) {
                    showtimesByMovie.computeIfAbsent(showtime.movieId(), ignored -> new java.util.ArrayList<>())
                            .add(showtime);
                }
                List<Map.Entry<Long, List<Showtime>>> ordered = new java.util.ArrayList<>(
                        showtimesByMovie.entrySet());
                ordered.sort((a, b) -> {
                    int ai = movieOrder.getOrDefault(a.getKey(), Integer.MAX_VALUE);
                    int bi = movieOrder.getOrDefault(b.getKey(), Integer.MAX_VALUE);
                    return Integer.compare(ai, bi);
                });
                for (Map.Entry<Long, List<Showtime>> movieEntry : ordered) {
                    // Defensive: đảm bảo các suất chiếu trong cùng nhóm phim LUÔN
                    // hiển thị theo thứ tự thời gian chiếu gần nhất → xa nhất
                    // (start_time ASC). DAO đã ORDER BY s.start_time, nhưng sort
                    // lại ở đây để tránh phụ thuộc vào câu SQL và chống regression
                    // nếu ai đó thêm/dời ORDER BY trong tương lai.
                    List<Showtime> sortedShowtimes = new java.util.ArrayList<>(movieEntry.getValue());
                    sortedShowtimes.sort(java.util.Comparator.comparing(Showtime::startTime));
                    Showtime firstShowtime = sortedShowtimes.get(0);
                    Movie movie = movieById.get(movieEntry.getKey());
                    String poster = movie == null ? null : movie.posterUrl();
                    String detailUrl = ctx + "/movie-detail?movieId=" + movieEntry.getKey();
                    String image = poster == null || poster.isBlank()
                            ? "<div class=\"show-poster-placeholder\">NO POSTER</div>"
                            : "<img data-poster-fallback=\"show-poster-placeholder\" loading=\"lazy\" referrerpolicy=\"no-referrer\" src=\""
                                    + escape(poster) + "\" alt=\"" + escape(firstShowtime.movieTitle()) + "\">";
                    out.printf("""
                        <article class="panel discovery-movie">
                          <div class="discovery-movie-heading">
                            <a class="discovery-movie-poster" href="%s">%s</a>
                            <div class="show-details">
                              <a class="discovery-movie-title" href="%s"><b>%s</b></a>
                              <span>%d suất chiếu</span>
                            </div>
                          </div>
                          <div class="discovery-movie-showtimes">
                        """, escape(detailUrl), image, escape(detailUrl), escape(firstShowtime.movieTitle()),
                            sortedShowtimes.size());
                    for (Showtime showtime : sortedShowtimes) {
                    String bookingUrl = ctx + "/booking?movieId=" + showtime.movieId();
                    out.printf("""
                        <div class="discovery-showtime">
                          <span><b>%s</b><small>%s · %s</small></span>
                          <span class="discovery-seat-count">%d ghế trống</span>
                          <a class="btn small" href="%s">Đặt vé</a>
                        </div>
                        """, escape(showtime.branchName()), escape(showtime.screenName()),
                              escape(String.valueOf(showtime.startTime()).replace('T', ' ')),
                              showtime.availableSeats(), escape(bookingUrl));
                    }
                    out.print("</div></article>");
                }
            }
        } catch (Exception e) {
            out.print("""
                <div class="empty-state"><h3>Không thể tải lịch chiếu</h3>
                <p>Vui lòng thử lại sau.</p></div>
                """);
        }
        out.printf("</section></main><script src=\"%s/assets/js/app.js?v=20261003-notification-time-2\" defer></script></body></html>", ctx);
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String renderAccountNav(HttpServletRequest request, String ctx) {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return "<a href=\"" + ctx + "/\">Trang chủ</a>"
                    + "<a href=\"" + ctx + "/login\">Đăng nhập</a>"
                    + "<a class=\"btn\" href=\"" + ctx + "/register\">Đăng ký</a>";
        }
        String username = escape(String.valueOf(session.getAttribute("username")));
        return "<a href=\"" + ctx + "/\">Trang chủ</a>"
                + "<span class=\"account-summary\"><strong>" + username
                + "</strong><small>Khách hàng</small></span>"
                + "<a class=\"btn ghost\" href=\"" + ctx + "/console?module=profile\">Hồ sơ</a>"
                + "<a class=\"btn\" href=\"" + ctx + "/logout\">Đăng xuất</a>";
    }
}
