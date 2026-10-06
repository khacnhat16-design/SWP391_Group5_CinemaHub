package com.cinema.web;

import com.cinema.auth.CustomerProfile;
import com.cinema.auth.CustomerProfileDAO;
import com.cinema.auth.AuthService;
import com.cinema.auth.User;
import com.cinema.auth.UserDAO;
import com.cinema.common.ServiceException;
import com.cinema.movie.Movie;
import com.cinema.movie.MovieDAO;
import com.cinema.site.SiteSettingService;
import com.cinema.wallet.Wallet;
import com.cinema.wallet.WalletDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Public home page for the embedded development server. */
public final class CinemaServlet extends HttpServlet {
    private final MovieDAO movieDao = new MovieDAO();
    private final UserDAO userDao = new UserDAO();
    private final CustomerProfileDAO profileDao = new CustomerProfileDAO();
    private final AuthService authService = new AuthService(userDao, profileDao);
    private final WalletDAO walletDao = new WalletDAO();
    private final SiteSettingService siteSettingService = new SiteSettingService();

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
        if (path.equals("/console") || path.startsWith("/console/")) {
            renderConsoleShell(request, response);
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
        // Admin/Manager/Staff đã đăng nhập vào trang chủ → đẩy thẳng vào Dashboard.
        var session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            Object roleObj = session.getAttribute("role");
            String role = roleObj == null ? "" : String.valueOf(roleObj);
            if ("ADMIN".equals(role) || "BRANCH_MANAGER".equals(role) || "BRANCH_STAFF".equals(role)) {
                response.sendRedirect(request.getContextPath() + "/console?module=dashboard");
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
            <link rel="stylesheet" href="%s/assets/css/cinema.css?v=20260917-1"></head><body>
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="%s/"><span class="logo-mark">C</span>Cinema<span class="accent">Hub</span></a>
            <nav><a href="%s/">Trang ch&#7911;</a><a href="%s/#movies">Phim</a>
            <a href="%s/discover">Su&#7845;t chi&#7871;u</a>%s</nav></div></header>
            <main><section class="hero" style="background-image:url('%s')"><div class="hero-overlay"></div>
            <div class="hero-content container"><span class="eyebrow">MULTI-BRANCH CINEMA</span>
            <h1>&#272;i&#7879;n &#7843;nh hay.<br><span class="accent">Tr&#7843;i nghi&#7879;m tuy&#7879;t v&#7901;i.</span></h1>
            <p>Kh&#225;m ph&#225; phim &#273;ang chi&#7871;u, su&#7845;t chi&#7871;u v&#224; chi nh&#225;nh CinemaHub.</p>
            <div class="actions"><a class="btn" href="%s/discover">Kh&#225;m ph&#225; phim</a>
            <a class="btn ghost" href="%s/booking">&#272;&#7863;t v&#233; ngay</a></div></div></section>
            <section id="movies" class="section container"><div class="section-heading">
            <div><span class="eyebrow">NOW SHOWING</span><h2>Phim &#273;ang chi&#7871;u</h2></div>
            </div><div class="movie-grid">
            """, ctx, currentRole(request), ctx, ctx, ctx, ctx, ctx, accountNav, bannerUrl, ctx, ctx);
        try {
            List<Movie> movies = movieDao.findPublished(LocalDate.now());
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
            <script src="%s/assets/js/app.js?v=20260914-6" defer></script>
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
            CustomerProfile profile = profileDao.findByUserId(userId).orElse(null);
            walletDao.ensureWallet(userId);
            Wallet wallet = walletDao.findByUser(userId).orElse(null);
            String name = user == null ? String.valueOf(session.getAttribute("username")) : user.fullName();
            int points = profile == null ? 0 : profile.points();
            long balance = wallet == null ? 0 : wallet.balance();
            session.setAttribute("username", name);
            return "<span class=\"account-summary\"><strong>" + escape(name)
                    + "</strong><small>" + points + " &#273;i&#7875;m &#183; " + formatMoneyHtml(balance)
                    + "</small></span>"
                    + "<button id=\"notificationBell\" class=\"nav-bell\" type=\"button\" aria-label=\"Thong bao\">"
                    + "<span aria-hidden=\"true\">&#128276;</span><span id=\"notifCount\" class=\"nav-badge\" hidden>0</span>"
                    + "</button><div id=\"notificationPopover\" class=\"notification-popover\" hidden></div>"
                    + "<a class=\"btn ghost\" href=\"" + ctx + "/console?module=profile\">H&#7891; s&#417;</a>"
                    + "<a class=\"btn\" href=\"" + ctx + "/logout\">&#272;&#259;ng xu&#7845;t</a>";
        } catch (Exception e) {
            return "<span class=\"account-summary\"><strong>"
                    + escape(String.valueOf(session.getAttribute("username")))
                    + "</strong></span><a class=\"btn\" href=\"" + ctx + "/logout\">&#272;&#259;ng xu&#7845;t</a>";
        }
    }

    private void renderMovieClean(PrintWriter out, String ctx, Movie movie) {
        String title = escape(movie.title());
        String poster = movie.posterUrl();
        String image = poster == null || poster.isBlank()
                ? "<div class=\"poster-placeholder\">No poster</div>"
                : "<img src=\"" + escape(resolveAssetUrl(ctx, poster)) + "\" alt=\"" + title + "\">";
        out.println("<article class=\"movie-card\"><div class=\"poster\">" + image
                + "<span class=\"rating\">&#9733; " + escape(movie.rating()) + "</span>"
                + "<a class=\"quick-book\" href=\"" + ctx + "/booking?movieId=" + movie.id()
                + "\">&#272;&#7863;t v&#233;</a>"
                + "</div><div class=\"movie-info\"><h3>" + title + "</h3><p>"
                + escape(movie.genre()) + " &#183; " + movie.durationMin() + " mins</p></div></article>");
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
            <main><section class="hero" style="background-image:url('%s')"><div class="hero-overlay"></div>
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
            <script src="%s/assets/js/app.js?v=20260914-1" defer></script>
            </body></html>
            """, ctx, ctx, ctx);
    }

    private void renderConsoleShell(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.sendRedirect(request.getContextPath() + "/admin/login");
            return;
        }
        String ctx = request.getContextPath();
        String role = escape(String.valueOf(session.getAttribute("role")));
        String name = escape(String.valueOf(session.getAttribute("username")));
        String notificationNav = "CUSTOMER".equals(role)
                ? "<button id=\"notificationBell\" class=\"nav-bell\" type=\"button\" aria-label=\"Notifications\">"
                    + "<span aria-hidden=\"true\">&#128276;</span><span id=\"notifCount\" class=\"nav-badge\" hidden>0</span>"
                    + "</button><div id=\"notificationPopover\" class=\"notification-popover\" hidden></div>"
                : "";
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="ctx" content="%s">
            <meta name="user-role" content="%s">
            <title>CinemaHub Workspace</title>
            <link rel="stylesheet" href="%s/assets/css/cinema.css?v=20260914-4">
            <link rel="stylesheet" href="%s/assets/css/dashboard.css?v=20260914-4">
            <link rel="stylesheet" href="%s/assets/css/workspace.css?v=20260917-1"></head><body class="admin-page">
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="%s/">Cinema<span class="accent">Hub</span></a>
            <nav><span class="account-summary"><strong>%s</strong><small>%s</small></span>%s
            <a class="btn ghost" href="%s/">Trang ch&#7911;</a>
            <a class="btn" href="%s/logout">&#272;&#259;ng xu&#7845;t</a></nav></div></header>
            <main class="app-shell">
              <div class="app-main">
                <div class="container dashboard"><div id="viewRoot"></div></div>
              </div>
            </main>
            <script>window.CINEMA_CONTEXT = '%s';</script>
            <script src="%s/assets/js/app.js?v=20260914-8" defer></script>
            <script src="%s/assets/js/rest-page.js?v=20260914-8" defer></script>
            <script src="%s/assets/js/console.js?v=20260914-8" defer></script>
            </body></html>
            """, ctx, role, ctx, ctx, ctx, name, role, notificationNav,
                ctx, ctx, ctx, ctx, ctx, ctx);
    }

    private String renderAccountNav(HttpServletRequest request, String ctx) {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            return "<a class=\"btn ghost\" href=\"" + ctx + "/login\">Ã„ÂÃ„Æ’ng nhÃ¡ÂºÂ­p</a>"
                    + "<a class=\"btn\" href=\"" + ctx + "/register\">Ã„ÂÃ„Æ’ng kÃƒÂ½</a>";
        }
        Object value = session.getAttribute("userId");
        if (!(value instanceof Long userId)) return "";
        try {
            User user = userDao.findById(userId).orElse(null);
            CustomerProfile profile = profileDao.findByUserId(userId).orElse(null);
            walletDao.ensureWallet(userId);
            Wallet wallet = walletDao.findByUser(userId).orElse(null);
            String name = user == null ? String.valueOf(session.getAttribute("username")) : user.fullName();
            int points = profile == null ? 0 : profile.points();
            long balance = wallet == null ? 0 : wallet.balance();
            session.setAttribute("username", name);
            return "<span class=\"account-summary\"><strong>" + escape(name)
                    + "</strong><small>" + points + " Ã„â€˜iÃ¡Â»Æ’m Ã‚Â· " + formatMoney(balance)
                    + "</small></span>"
                    + "<button id=\"notificationBell\" class=\"nav-bell\" type=\"button\" aria-label=\"Notifications\">"
                    + "<span aria-hidden=\"true\">&#128276;</span><span id=\"notifCount\" class=\"nav-badge\" hidden>0</span>"
                    + "</button><div id=\"notificationPopover\" class=\"notification-popover\" hidden></div>"
                    + "<a class=\"btn ghost\" href=\"" + ctx
                    + "/console?module=profile\">HÃ¡Â»â€œ sÃ†Â¡</a><a class=\"btn\" href=\"" + ctx
                    + "/logout\">Ã„ÂÃ„Æ’ng xuÃ¡ÂºÂ¥t</a>";
        } catch (Exception e) {
            return "<span class=\"account-summary\"><strong>"
                    + escape(String.valueOf(session.getAttribute("username")))
                    + "</strong></span><a class=\"btn\" href=\"" + ctx + "/logout\">Ã„ÂÃ„Æ’ng xuÃ¡ÂºÂ¥t</a>";
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
                : "<img src=\"" + escape(resolveAssetUrl(ctx, poster)) + "\" alt=\"" + title + "\">";
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

    private String formatMoney(long amount) {
        return String.format(Locale.US, "%,dÃ„â€˜", amount);
    }

    private void renderProfile(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String ctx = request.getContextPath();
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute("userId") instanceof Long userId)) {
            response.sendRedirect(ctx + "/login");
            return;
        }
        try {
            User user = userDao.findById(userId).orElseThrow();
            CustomerProfile profile = profileDao.findByUserId(userId).orElse(null);
            walletDao.ensureWallet(userId);
            Wallet wallet = walletDao.findByUser(userId).orElse(null);
            String profileForm = "<section class=\"panel\"><h2>Chinh sua ho so</h2>"
                    + "<form class=\"profile-edit-form\" method=\"post\" action=\"" + ctx + "/profile\">"
                    + "<label><span>Ho ten</span><input name=\"fullName\" value=\""
                    + escape(user.fullName()) + "\" required minlength=\"2\"></label>"
                    + "<label><span>So dien thoai</span><input name=\"phone\" value=\""
                    + escape(user.phone()) + "\" required pattern=\"0[0-9]{9}\"></label>"
                    + "<button class=\"btn\" type=\"submit\">Cap nhat ho so</button>"
                    + "</form></section>";
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().printf("""
                <!doctype html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <link rel="stylesheet" href="%s/assets/css/cinema.css">
                <title>HÃ¡Â»â€œ sÃ†Â¡ - CinemaHub</title></head><body>
                <header class="nav-wrapper"><div class="nav container">
                <a class="logo" href="%s/">Cinema<span class="accent">Hub</span></a>
                <nav><a href="%s/">Trang chÃ¡Â»Â§</a><a href="%s/discover">SuÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u</a>
                <a class="btn" href="%s/logout">Ã„ÂÃ„Æ’ng xuÃ¡ÂºÂ¥t</a></nav></div></header>
                <main class="container section"><div class="page-heading">
                <div><span class="eyebrow">MY ACCOUNT</span><h1>HÃ¡Â»â€œ sÃ†Â¡ ngÃ†Â°Ã¡Â»Âi dÃƒÂ¹ng</h1>
                <p class="muted">ThÃƒÂ´ng tin tÃƒÂ i khoÃ¡ÂºÂ£n, Ã„â€˜iÃ¡Â»Æ’m thÃƒÂ nh viÃƒÂªn vÃƒÂ  vÃƒÂ­ CinemaHub.</p></div>
                </div><div class="dashboard-grid">
                <section class="panel"><h2>ThÃƒÂ´ng tin tÃƒÂ i khoÃ¡ÂºÂ£n</h2>
                <div class="summary-line"><span>HÃ¡Â»Â tÃƒÂªn</span><strong>%s</strong></div>
                <div class="summary-line"><span>Email</span><strong>%s</strong></div>
                <div class="summary-line"><span>SÃ¡Â»â€˜ Ã„â€˜iÃ¡Â»â€¡n thoÃ¡ÂºÂ¡i</span><strong>%s</strong></div>
                <div class="summary-line"><span>Vai trÃƒÂ²</span><strong>%s</strong></div></section>
                <section class="panel"><h2>ThÃƒÂ nh viÃƒÂªn vÃƒÂ  vÃƒÂ­</h2>
                <div class="summary-line"><span>HÃ¡ÂºÂ¡ng thÃƒÂ nh viÃƒÂªn</span><strong>%s</strong></div>
                <div class="summary-line"><span>Ã„ÂiÃ¡Â»Æ’m tÃƒÂ­ch lÃ…Â©y</span><strong>%d Ã„â€˜iÃ¡Â»Æ’m</strong></div>
                <div class="summary-line total"><span>SÃ¡Â»â€˜ dÃ†Â° vÃƒÂ­</span><strong>%s</strong></div>
                </section>%s</div></main>
                <script>window.CINEMA_CONTEXT = '%s';</script>
                <script src="%s/assets/js/app.js?v=20260914-3" defer></script>
                </body></html>
                """, ctx, ctx, ctx, ctx, ctx, escape(user.fullName()), escape(user.email()),
                    escape(user.phone()), escape(user.role().name()),
                    profile == null ? "STANDARD" : escape(profile.tier()),
                    profile == null ? 0 : profile.points(),
                    formatMoney(wallet == null ? 0 : wallet.balance()),
                    profileForm, ctx, ctx);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "KhÃƒÂ´ng thÃ¡Â»Æ’ tÃ¡ÂºÂ£i hÃ¡Â»â€œ sÃ†Â¡");
        }
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
