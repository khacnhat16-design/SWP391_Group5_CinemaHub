package com.cinema.web;

import com.cinema.branch.Branch;
import com.cinema.branch.BranchDAO;
import com.cinema.movie.Movie;
import com.cinema.movie.MovieDAO;
import com.cinema.screen.ScreenDAO;
import com.cinema.showtime.Showtime;
import com.cinema.showtime.ShowtimeDAO;
import com.cinema.showtime.ShowtimeService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
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
            <link rel="stylesheet" href="%s/assets/css/cinema.css">
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
            List<Movie> movies = movieDao.findPublished(LocalDate.now());
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
                for (Showtime showtime : showtimes) {
                    Movie movie = movieById.get(showtime.movieId());
                    String poster = movie == null ? null : movie.posterUrl();
                    String bookingUrl = ctx + "/booking?movieId=" + showtime.movieId();
                    String image = poster == null || poster.isBlank()
                            ? "<div class=\"show-poster-placeholder\">NO POSTER</div>"
                            : "<img src=\"" + escape(poster) + "\" alt=\""
                                    + escape(showtime.movieTitle()) + "\">";
                    out.printf("""
                        <article class="panel show-row">
                          <div class="show-poster">%s</div>
                          <div class="show-details"><a href="%s"><b>%s</b></a>
                            <span>%s · %s · %s</span>
                          </div>
                          <strong>%d ghế trống</strong>
                        </article>
                        """, image, escape(bookingUrl), escape(showtime.movieTitle()),
                            escape(showtime.branchName()), escape(showtime.screenName()),
                            showtime.startTime(), showtime.availableSeats());
                }
            }
        } catch (Exception e) {
            out.print("""
                <div class="empty-state"><h3>Không thể tải lịch chiếu</h3>
                <p>Vui lòng thử lại sau.</p></div>
                """);
        }
        out.print("</section></main></body></html>");
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
