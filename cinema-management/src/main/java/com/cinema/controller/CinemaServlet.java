package com.cinema.controller;

import com.cinema.controller.payment.VnPayDemoServlet;

import com.cinema.dao.auth.CustomerProfileDAO;
import com.cinema.service.auth.AuthService;
import com.cinema.model.auth.User;
import com.cinema.dao.auth.UserDAO;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.common.ServiceException;
import com.cinema.model.movie.Movie;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.dao.screen.ScreenDAO;
import com.cinema.service.site.SiteSettingService;
import com.cinema.model.showtime.Showtime;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.service.showtime.ShowtimeService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Public home page for the embedded development server. */
public final class CinemaServlet extends HttpServlet {
    private final MovieDAO movieDao = new MovieDAO();
    private final UserDAO userDao = new UserDAO();
    private final CustomerProfileDAO profileDao = new CustomerProfileDAO();
    private final AuthService authService = new AuthService(userDao, profileDao);
    private final SiteSettingService siteSettingService = new SiteSettingService();
    private final ShowtimeService showtimeService = new ShowtimeService(
            new ShowtimeDAO(), new ScreenDAO(), movieDao, new BranchDAO());

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/health".equals(path)) {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().println("OK");
            return;
        }
        if (path.startsWith("/__test-error/")) {
            throw testHook(path.substring("/__test-error/".length()));
        }
        if ("/logout".equals(path) || "/login".equals(path) || "/register".equals(path)
                || "/admin/login".equals(path)) {
            request.getServletContext().getNamedDispatcher("authController").forward(request, response);
            return;
        }
        if ("/profile".equals(path)) {
            response.sendRedirect(request.getContextPath() + "/console?module=profile");
            return;
        }
        if ("/booking".equals(path)) {
            var session = request.getSession(false);
            if (session == null || session.getAttribute("userId") == null) {
                renderGuestBooking(request, response);
            } else {
                request.getServletContext().getNamedDispatcher("bookingController").forward(request, response);
            }
            return;
        }
        if ("/movie-detail".equals(path)) {
            renderMovieDetail(request, response);
            return;
        }
        if (path.startsWith("/vnpay/demo")) {
            // Form sandbox nội bộ: forward sang VnPayDemoServlet để mô phỏng flow
            // thanh toán mà không cần kết nối VNPay thật (chỉ dùng trong dev).
            getServletContext().getNamedDispatcher("vnPayDemoServlet").forward(request, response);
            return;
        }
        if (path.startsWith("/vnpay/return") || path.startsWith("/vnpay/ipn")) {
            // VNPay callback phải đi qua controller để verify checksum trước khi
            // cập nhật DB — không được render HTML trực tiếp tại đây.
            getServletContext().getNamedDispatcher("vnPayController").forward(request, response);
            return;
        }
        if (!"/".equals(path) && !path.isBlank()) {
            response.sendRedirect(request.getContextPath() + "/");
            return;
        }
        // Admin/Manager/Staff đã đăng nhập vào trang chủ → vào khu vực quản lý.
        var session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            Object roleObj = session.getAttribute("role");
            String role = roleObj == null ? "" : String.valueOf(roleObj);
            if ("ADMIN".equals(role) || "BRANCH_MANAGER".equals(role) || "BRANCH_STAFF".equals(role)) {
                response.sendRedirect(request.getContextPath() + "/console");
                return;
            }
        }
        renderHomeClean(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/profile".equals(path)) {
            updateProfile(request, response);
            return;
        }
        request.getServletContext().getNamedDispatcher("authController").forward(request, response);
    }

    private void renderHomeClean(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");
        String ctx = request.getContextPath();
        PrintWriter out = response.getWriter();
        String accountNav = renderAccountNavClean(request, ctx);

        // Banner động: ưu tiên URL đã upload lên UploadThing (Admin set trong
        // Site Settings); fallback về ảnh Unsplash để trang chủ không bao giờ
        // hiển thị banner trống khi DB chưa có setting.
        String bannerUrl = siteSettingService.getHomeBannerUrl();
        if (bannerUrl == null || bannerUrl.isBlank()) {
            bannerUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=1920&q=80";
        }

        out.printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="ctx" content="%s">
            <meta name="user-role" content="%s">
            <title>CinemaHub</title>
            <link rel="stylesheet" href="%s/assets/css/cinema.css?v=20261003-genres-1"></head><body>
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="%s/"><span class="logo-mark">C</span>Cinema<span class="accent">Hub</span></a>
            <nav><a href="%s/">Trang ch&#7911;</a><a href="%s/#movies">Phim</a>
            <a href="%s/discover">Su&#7845;t chi&#7871;u</a>%s</nav></div></header>
            <main><section class="hero" style="background-image:linear-gradient(90deg,rgba(8,8,10,.78) 0%%,rgba(8,8,10,.45) 50%%,rgba(8,8,10,.12) 100%%),url('%s');background-size:cover;background-position:center;background-repeat:no-repeat"><div class="hero-overlay"></div>
            <div class="hero-content container"><span class="eyebrow">MULTI-BRANCH CINEMA</span>
            <h1>&#272;i&#7879;n &#7843;nh hay.<br><span class="accent">Tr&#7843;i nghi&#7879;m tuy&#7879;t v&#7901;i.</span></h1>
            <p>Kh&#225;m ph&#225; phim &#273;ang chi&#7871;u, su&#7845;t chi&#7871;u v&#224; chi nh&#225;nh CinemaHub.</p>
            <div class="actions"><a class="btn" href="%s/discover">Kh&#225;m ph&#225; phim</a>
            <a class="btn ghost" href="%s/booking">&#272;&#7863;t v&#233; ngay</a></div></div></section>
            <section id="movies" class="section container"><div class="section-heading">
            <div><span class="eyebrow">NOW SHOWING</span><h2>Phim &#273;ang chi&#7871;u</h2></div>
            </div><label class="genre-filter-label" for="customerGenreFilter">Thể loại phim
            <select id="customerGenreFilter" class="genre-filter"><option value="">Tất cả thể loại</option>
            """, ctx, currentRole(request), ctx, ctx, ctx, ctx, ctx, accountNav, bannerUrl, ctx, ctx);
        out.println("</select></label>"
                + "<p id=\"genreFilterEmpty\" class=\"empty-state\" hidden>Không có phim thuộc thể loại này.</p>"
                + "<p id=\"genreFilterStatus\" class=\"genre-filter-status\" role=\"status\" hidden></p>"
                + "<div class=\"movie-grid\" id=\"customerMovieGrid\">");
        try {
            // Public landing page cần show MỌI phim PUBLISHED (kể cả "Sắp chiếu"
            // với release_date tương lai hoặc "Ngừng chiếu" với end_date đã qua)
            // — filter date-range hẹp của findPublished() khiến nhiều phim biến mất
            // ngay khi Admin tạo mới. UI sẽ tự gắn badge theo ngày.
            List<Movie> movies = movieDao.findPublishedAll();
            if (movies.isEmpty()) out.println("<p class=\"empty-state\">Ch&#432;a c&#243; phim &#273;ang chi&#7871;u.</p>");
            else for (Movie movie : movies) renderMovieClean(out, ctx, movie);
        } catch (Exception e) {
            out.println("<p class=\"empty-state\">Kh&#244;ng th&#7875; t&#7843;i th&#244;ng tin phim.</p>");
        }
        out.printf("""
            </div></section></main><footer><div class="container footer-inner">
            <a class="logo" href="%s/">Cinema<span class="accent">Hub</span></a>
            <span>H&#7879; th&#7889;ng qu&#7843;n l&#253; r&#7841;p phim &#273;a chi nh&#225;nh</span>
            </div></footer>
            <script>window.CINEMA_CONTEXT = '%s';</script>
            <script src="%s/assets/js/app.js?v=20261003-notification-time-2" defer></script>
            <script>
              document.addEventListener('DOMContentLoaded', function () {
                var filter = document.getElementById('customerGenreFilter');
                var cards = Array.from(document.querySelectorAll('#customerMovieGrid .movie-card'));
                var empty = document.getElementById('genreFilterEmpty');
                var status = document.getElementById('genreFilterStatus');
                if (!filter) return;
                if (!cards.length) {
                  filter.disabled = true;
                  empty.hidden = true;
                  return;
                }
                fetch((window.CINEMA_CONTEXT || '') + '/movie/genres', { credentials: 'same-origin' })
                  .then(function (response) {
                    if (!response.ok) throw new Error('Không thể tải danh mục thể loại phim.');
                    return response.json();
                  })
                  .then(function (genres) {
                    genres.forEach(function (genre) {
                      filter.appendChild(new Option(genre.name, genre.name));
                    });
                  })
                  .catch(function () {
                    status.textContent = 'Không thể tải danh mục thể loại phim. Vui lòng thử tải lại trang.';
                    status.hidden = false;
                  });
                filter.addEventListener('change', function () {
                  var selected = filter.value.toLocaleLowerCase();
                  var visible = 0;
                  cards.forEach(function (card) {
                    var genres = (card.dataset.genres || '').split('|').map(function (g) {
                      return g.trim().toLocaleLowerCase();
                    });
                    var show = !selected || genres.includes(selected);
                    card.hidden = !show;
                    if (show) visible++;
                  });
                  empty.hidden = visible !== 0;
                });
              });
            </script>
            </body></html>
            """, ctx, ctx, ctx);
    }

    private String renderAccountNavClean(HttpServletRequest request, String ctx) {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return "<a class=\"btn ghost\" href=\"" + ctx + "/login\">&#272;&#259;ng nh&#7853;p</a>"
                    + "<a class=\"btn\" href=\"" + ctx + "/register\">&#272;&#259;ng k&#253;</a>";
        }
        Object value = session.getAttribute("userId");
        if (!(value instanceof Long userId)) return "";
        try {
            User user = userDao.findById(userId).orElse(null);
            String name = user == null ? String.valueOf(session.getAttribute("username")) : user.fullName();
            session.setAttribute("username", name);
            return renderCustomerHeaderNav(ctx, name);
        } catch (Exception e) {
            String fallbackName = String.valueOf(session.getAttribute("username"));
            return renderCustomerHeaderNav(ctx, fallbackName);
        }
    }

    private String renderCustomerHeaderNav(String ctx, String name) {
        String safeName = name == null ? "Customer" : name.trim();
        if (safeName.isEmpty()) safeName = "Customer";
        String initial = safeName.substring(0, 1).toUpperCase(Locale.ROOT);
        return "<span class=\"nav-notif-wrap\">"
                + "<button class=\"nav-bell\" type=\"button\" id=\"notificationBell\" aria-label=\"Thông báo\" aria-expanded=\"false\" title=\"Thông báo\">"
                + "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" aria-hidden=\"true\">"
                + "<path d=\"M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9\"/>"
                + "<path d=\"M13.73 21a2 2 0 0 1-3.46 0\"/>"
                + "</svg>"
                + "<span id=\"notifCount\" class=\"badge\" style=\"display:none\"></span>"
                + "</button>"
                + "<div class=\"notification-popover\" id=\"notificationPopover\" hidden></div>"
                + "</span>"
                + "<a class=\"btn ghost nav-user-btn\" href=\"" + ctx + "/console?module=profile\">"
                + "<span class=\"nav-avatar\">" + escape(initial) + "</span>"
                + "<span class=\"nav-username\">" + escape(safeName) + "</span>"
                + "</a>";
    }

    private void renderMovieClean(PrintWriter out, String ctx, Movie movie) {
        String title = escape(movie.title());
        String poster = movie.posterUrl();
        String image = poster == null || poster.isBlank()
                ? "<div class=\"poster-placeholder\">No poster</div>"
                : "<img data-poster-fallback=\"poster-placeholder\" loading=\"lazy\" referrerpolicy=\"no-referrer\" src=\""
                        + escape(resolveAssetUrl(ctx, poster)) + "\" alt=\"" + title + "\">";
        String genreData = String.join("|", genreTokens(movie.genre())).toLowerCase(Locale.ROOT);
        out.println("<article class=\"movie-card\" data-genres=\"" + escape(genreData) + "\"><div class=\"poster\"><a href=\"" + ctx
                + "/movie-detail?movieId=" + movie.id() + "\">" + image + "</a>"
                + "<span class=\"rating\">&#9733; " + escape(movie.rating()) + "</span>"
                + "<a class=\"quick-book\" href=\"" + ctx + "/booking?movieId=" + movie.id()
                + "\">&#272;&#7863;t v&#233;</a>"
                + "</div><div class=\"movie-info\"><h3>" + title + "</h3><p>"
                + escape(movie.genre()) + " &#183; " + movie.durationMin() + " mins</p></div></article>");
    }

    private List<String> genreTokens(String genre) {
        if (genre == null || genre.isBlank()) return List.of();
        return java.util.Arrays.stream(genre.split("[,;/]"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private void renderMovieDetail(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Long movieId;
        try {
            movieId = Long.valueOf(request.getParameter("movieId"));
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Phim không tồn tại");
            return;
        }

        Movie movie;
        try {
            movie = movieDao.findById(movieId)
                    .filter(Movie::isPublished)
                    .orElse(null);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Không thể tải thông tin phim");
            return;
        }
        if (movie == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Phim không tồn tại");
            return;
        }

        String ctx = request.getContextPath();
        String title = escape(movie.title());
        String poster = movie.posterUrl();
        String image = poster == null || poster.isBlank()
                ? "<div class=\"poster-placeholder\">No poster</div>"
                : "<img data-poster-fallback=\"poster-placeholder\" loading=\"lazy\" referrerpolicy=\"no-referrer\" src=\""
                        + escape(resolveAssetUrl(ctx, poster)) + "\" alt=\"" + title + "\">";

        // Header động: nếu Customer đã login → renderAccountNavClean (Hồ sơ + Đăng xuất).
        // Nếu guest → "Đăng nhập" + "Đăng ký".
        String accountNav = renderAccountNavClean(request, ctx);
        Long currentUserId = currentUserId(request);
        String role = currentRole(request);
        String movieShowtimes = renderMovieShowtimes(ctx, movieId);

        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="ctx" content="%s">
            <meta name="user-role" content="%s">
            <meta name="user-id" content="%s">
            <title>%s - CinemaHub</title>
            <link rel="stylesheet" href="%s/assets/css/cinema.css?v=20261003-discovery-group-1">
            <link rel="stylesheet" href="%s/assets/css/workspace.css?v=20261012-review-ui-2">
            <link rel="stylesheet" href="%s/assets/css/rsc.css?v=20261012-review-ui-2">
            </head><body class="movie-detail-page">
            <header class="nav-wrapper"><div class="nav container">
              <a class="logo" href="%s/"><span class="logo-mark">C</span>Cinema<span class="accent">Hub</span></a>
              <nav><a href="%s/">Trang ch&#7911;</a><a href="%s/discover">Su&#7845;t chi&#7871;u</a>%s</nav>
            </div></header>
            <main class="container section"><div class="panel movie-detail-panel" style="display:grid;grid-template-columns:minmax(180px,280px) 1fr;gap:28px;padding:28px">
              <div class="poster">%s</div><div class="movie-meta">
                <span class="eyebrow">MOVIE DETAIL</span>
                <h1>%s</h1>
                <p class="muted">%s &#183; %d ph&#250;t &#183; Kh&#7899;i chi&#7871;u %s</p>
                <p class="movie-description">%s</p>
                <div class="movie-actions"><a class="btn" href="%s/booking?movieId=%d">&#272;&#7863;t v&#233;</a></div>
              </div>
            </div>
            %s
            <section class="container section reviews-section">
              <div class="section-heading"><div><span class="eyebrow">REVIEWS &amp; RATINGS</span><h2>&#272;&#225;nh gi&#225; &amp; nh&#7853;n x&#233;t</h2></div></div>
              <div id="reviewsRoot" class="ws-reviews-root"></div>
            </section>
            </main>
            <footer><div class="container footer-inner">
              <a class="logo" href="%s/">Cinema<span class="accent">Hub</span></a>
              <span>H&#7879; th&#7889;ng qu&#7843;n l&#253; r&#7841;p phim &#273;a chi nh&#225;nh</span>
            </div></footer>
            <script>window.CINEMA_CONTEXT = '%s';</script>
            <script src="%s/assets/js/app.js?v=20261003-notification-time-2" defer></script>
            <script src="%s/assets/js/modules-rsc.js?v=20261012-review-delete-2" defer></script>
            <script>
              document.addEventListener('DOMContentLoaded', function () {
                var root = document.getElementById('reviewsRoot');
                if (root && window.CinemaHubReviews && typeof window.CinemaHubReviews.renderForMovie === 'function') {
                  window.CinemaHubReviews.renderForMovie(%d, root);
                } else if (root) {
                  root.innerHTML = '<p class="ws-muted">Ch&#7913;c n&#259;ng &#273;ang &#273;&#432;&#7907;c t&#7843;i...</p>';
                }
              });
            </script>
            </body></html>
            """, ctx, role, currentUserId == null ? "" : currentUserId.toString(),
                title,
                ctx, ctx, ctx,
                ctx, ctx, ctx, accountNav,
                image,
                title,
                escape(movie.genre()), movie.durationMin(), movie.releaseDate(),
                escape(movie.description()),
                ctx, movie.id(),
                movieShowtimes,
                ctx,
                ctx,
                ctx, ctx,
                movieId);
    }

    private String renderMovieShowtimes(String ctx, long movieId) {
        StringBuilder html = new StringBuilder("""
                <section class="section movie-venues">
                  <div class="section-heading"><div><span class="eyebrow">SHOWTIMES</span>
                    <h2>Rạp và suất chiếu</h2></div></div>
                """);
        try {
            List<Showtime> showtimes = showtimeService.discover(null, movieId, null);
            if (showtimes.isEmpty()) {
                html.append("<p class=\"empty-state\">Hiện chưa có suất chiếu sắp tới cho phim này.</p>");
            } else {
                for (Showtime showtime : showtimes) {
                    html.append("<article class=\"panel movie-venue-row\"><div><b>")
                            .append(escape(showtime.branchName()))
                            .append("</b><span>")
                            .append(escape(showtime.screenName()))
                            .append(" · ")
                            .append(escape(String.valueOf(showtime.startTime()).replace('T', ' ')))
                            .append("</span></div><strong>")
                            .append(showtime.availableSeats())
                            .append(" ghế trống</strong><a class=\"btn small\" href=\"")
                            .append(ctx).append("/booking?movieId=").append(movieId)
                            .append("\">Đặt vé</a></article>");
                }
            }
        } catch (Exception e) {
            html.append("<p class=\"empty-state\">Không thể tải danh sách rạp và suất chiếu. Vui lòng thử lại.</p>");
        }
        return html.append("</section>").toString();
    }

    /**
     * Helper: lấy currentUserId từ session (Long) — null nếu chưa login.
     */
    private Long currentUserId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute("userId");
        return value instanceof Long l ? l : null;
    }

    private String formatMoneyHtml(long amount) {
        return String.format(Locale.US, "%,d&#273;", amount);
    }

    private void renderHome(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");
        String ctx = request.getContextPath();
        PrintWriter out = response.getWriter();
        String accountNav = renderAccountNav(request, ctx);

        String bannerUrl = siteSettingService.getHomeBannerUrl();
        if (bannerUrl == null || bannerUrl.isBlank()) {
            bannerUrl = "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=1920&q=80";
        }

        out.printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="ctx" content="%s">
            <meta name="user-role" content="%s">
            <title>CinemaHub</title>
            <link rel="stylesheet" href="%s/assets/css/cinema.css"></head><body>
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="%s/"><span class="logo-mark">C</span>Cinema<span class="accent">Hub</span></a>
            <nav><a href="%s/">Trang chÃ¡Â»Â§</a><a href="%s/#movies">Phim</a>
            <a href="%s/discover">SuÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u</a>%s</nav></div></header>
            <main><section class="hero" style="background-image:linear-gradient(90deg,rgba(8,8,10,.78) 0%%,rgba(8,8,10,.45) 50%%,rgba(8,8,10,.12) 100%%),url('%s');background-size:cover;background-position:center;background-repeat:no-repeat"><div class="hero-overlay"></div>
            <div class="hero-content container"><span class="eyebrow">MULTI-BRANCH CINEMA</span>
            <h1>Ã„ÂiÃ¡Â»â€¡n Ã¡ÂºÂ£nh hay.<br><span class="accent">TrÃ¡ÂºÂ£i nghiÃ¡Â»â€¡m tuyÃ¡Â»â€¡t vÃ¡Â»Âi.</span></h1>
            <p>KhÃƒÂ¡m phÃƒÂ¡ phim Ã„â€˜ang chiÃ¡ÂºÂ¿u, suÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u vÃƒÂ  chi nhÃƒÂ¡nh CinemaHub.</p>
            <div class="actions"><a class="btn" href="%s/discover">KhÃƒÂ¡m phÃƒÂ¡ phim</a>
            <a class="btn ghost" href="%s/booking">Ã„ÂÃ¡ÂºÂ·t vÃƒÂ© ngay</a></div></div></section>
            <section id="movies" class="section container"><div class="section-heading">
            <div><span class="eyebrow">NOW SHOWING</span><h2>Phim Ã„â€˜ang chiÃ¡ÂºÂ¿u</h2></div>
            </div><div class="movie-grid">
            """, ctx, currentRole(request), ctx, ctx, ctx, ctx, ctx, accountNav, bannerUrl, ctx, ctx);
        try {
            List<Movie> movies = movieDao.findPublished(LocalDate.now());
            if (movies.isEmpty()) out.println("<p class=\"empty-state\">ChÃ†Â°a cÃƒÂ³ phim Ã„â€˜ang chiÃ¡ÂºÂ¿u.</p>");
            else for (Movie movie : movies) renderMovie(out, ctx, movie);
        } catch (Exception e) {
            out.println("<p class=\"empty-state\">KhÃƒÂ´ng thÃ¡Â»Æ’ tÃ¡ÂºÂ£i thÃƒÂ´ng tin phim.</p>");
        }
        out.printf("""
            </div></section></main><footer><div class="container footer-inner">
            <a class="logo" href="%s/">Cinema<span class="accent">Hub</span></a>
            <span>HÃ¡Â»â€¡ thÃ¡Â»â€˜ng quÃ¡ÂºÂ£n lÃƒÂ½ rÃ¡ÂºÂ¡p phim Ã„â€˜a chi nhÃƒÂ¡nh</span>
            </div></footer>
            <script>window.CINEMA_CONTEXT = '%s';</script>
            <script src="%s/assets/js/app.js?v=20261003-notification-time-2" defer></script>
            </body></html>
            """, ctx, ctx, ctx);
    }

    private String renderAccountNav(HttpServletRequest request, String ctx) {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return "<a class=\"btn ghost\" href=\"" + ctx + "/login\">&#272;&#259;ng nh&#7853;p</a>"
                    + "<a class=\"btn\" href=\"" + ctx + "/register\">&#272;&#259;ng k&#253;</a>";
        }
        Object value = session.getAttribute("userId");
        if (!(value instanceof Long userId)) return "";
        try {
            User user = userDao.findById(userId).orElse(null);
            String name = user == null ? String.valueOf(session.getAttribute("username")) : user.fullName();
            session.setAttribute("username", name);
            return renderCustomerHeaderNav(ctx, name)
                    + "<a class=\"btn ghost\" href=\"" + ctx + "/logout\">Thoát</a>";
        } catch (Exception e) {
            String fallbackName = String.valueOf(session.getAttribute("username"));
            return renderCustomerHeaderNav(ctx, fallbackName)
                    + "<a class=\"btn ghost\" href=\"" + ctx + "/logout\">Thoát</a>";
        }
    }

    private String currentRole(HttpServletRequest request) {
        var session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute("role");
        return role == null ? "GUEST" : escape(String.valueOf(role));
    }

    private void renderMovie(PrintWriter out, String ctx, Movie movie) {
        String title = escape(movie.title());
        String poster = movie.posterUrl();
        String image = poster == null || poster.isBlank()
                ? "<div class=\"poster-placeholder\">No poster</div>"
                : "<img data-poster-fallback=\"poster-placeholder\" loading=\"lazy\" referrerpolicy=\"no-referrer\" src=\""
                        + escape(resolveAssetUrl(ctx, poster)) + "\" alt=\"" + title + "\">";
        out.println("<article class=\"movie-card\"><div class=\"poster\">" + image
                + "<span class=\"rating\">Ã¢Ëœâ€¦ " + escape(movie.rating()) + "</span>"
                + "<a class=\"quick-book\" href=\"" + ctx + "/booking?movieId=" + movie.id()
                + "\">Ã„ÂÃ¡ÂºÂ·t vÃƒÂ©</a>"
                + "</div><div class=\"movie-info\"><h3>" + title + "</h3><p>"
                + escape(movie.genre()) + " Ã‚Â· " + movie.durationMin() + " mins</p></div></article>");
    }

    private String resolveAssetUrl(String ctx, String url) {
        if (url == null || url.isBlank()) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return url.startsWith("/") ? ctx + url : ctx + "/" + url;
    }

    private void updateProfile(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String ctx = request.getContextPath();
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute("userId") instanceof Long userId)) {
            response.sendRedirect(ctx + "/login");
            return;
        }
        try {
            User updated = authService.updateProfile(userId,
                    request.getParameter("fullName"), request.getParameter("phone"));
            session.setAttribute("username", updated.fullName());
            response.sendRedirect(ctx + "/profile?updated=1");
        } catch (Exception e) {
            response.sendRedirect(ctx + "/profile?error=1");
        }
    }

    private void renderGuestBooking(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String ctx = request.getContextPath();
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <link rel="stylesheet" href="%s/assets/css/cinema.css"></head><body>
            <main class="container section"><div class="auth-card"><h1>Vui lÃƒÂ²ng Ã„â€˜Ã„Æ’ng nhÃ¡ÂºÂ­p Ã„â€˜Ã¡Â»Æ’ Ã„â€˜Ã¡ÂºÂ·t vÃƒÂ©</h1>
            <div class="actions"><a class="btn" href="%s/login">Ã„ÂÃ„Æ’ng nhÃ¡ÂºÂ­p</a>
            <a class="btn ghost" href="%s/register">Ã„ÂÃ„Æ’ng kÃƒÂ½</a></div></div></main>
            </body></html>
            """, ctx, ctx, ctx);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private ServiceException testHook(String hook) {
        return switch (hook) {
            case "validation" -> new ServiceException.Validation("ThiÃ¡ÂºÂ¿u tÃƒÂªn khÃƒÂ¡ch hÃƒÂ ng.");
            case "unauthorized" -> new ServiceException.Unauthorized("PhiÃƒÂªn Ã„â€˜Ã„Æ’ng nhÃ¡ÂºÂ­p Ã„â€˜ÃƒÂ£ hÃ¡ÂºÂ¿t hÃ¡ÂºÂ¡n.");
            case "forbidden" -> new ServiceException.Forbidden("BÃ¡ÂºÂ¡n khÃƒÂ´ng cÃƒÂ³ quyÃ¡Â»Ân truy cÃ¡ÂºÂ­p nÃ¡Â»â„¢i dung nÃƒÂ y.");
            case "not-found" -> new ServiceException.NotFound("KhÃƒÂ´ng tÃƒÂ¬m thÃ¡ÂºÂ¥y suÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u.");
            case "conflict" -> new ServiceException.Conflict("GhÃ¡ÂºÂ¿ Ã„â€˜ÃƒÂ£ Ã„â€˜Ã†Â°Ã¡Â»Â£c giÃ¡Â»Â¯ bÃ¡Â»Å¸i yÃƒÂªu cÃ¡ÂºÂ§u khÃƒÂ¡c.");
            case "business-rule" -> new ServiceException.BusinessRule(
                    "BUSINESS_RULE", "KhÃƒÂ´ng thÃ¡Â»Æ’ hÃ¡Â»Â§y vÃƒÂ© sÃƒÂ¡t giÃ¡Â»Â chiÃ¡ÂºÂ¿u.");
            case "boom" -> throw new IllegalStateException("test failure");
            default -> new ServiceException.NotFound("KhÃƒÆ’Ã‚Â´ng tÃƒÆ’Ã‚Â¬m thÃƒÂ¡Ã‚ÂºÃ‚Â¥y test hook.");
        };
    }
}
