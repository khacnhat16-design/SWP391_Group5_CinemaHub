package com.cinema.auth;

import com.cinema.common.CsrfUtil;
import com.cinema.common.ServiceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Customer login/registration and management login controller. */
public class AuthController extends HttpServlet {
    private static final Logger LOG = Logger.getLogger(AuthController.class.getName());
    private AuthService authService;

    @Override
    public void init() throws ServletException {
        this.authService = new AuthService(new UserDAO(), new CustomerProfileDAO());
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        if (path.endsWith("/logout")) {
            HttpSession session = req.getSession(false);
            if (session != null) session.invalidate();
            resp.sendRedirect(req.getContextPath() + "/");
        } else if (path.endsWith("/admin/login")) {
            renderForm(req, resp, true, true, null);
        } else if (path.endsWith("/register")) {
            renderForm(req, resp, false, false, null);
        } else if (path.endsWith("/login")) {
            renderForm(req, resp, true, false, null);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        boolean managementLogin = path.endsWith("/admin/login");
        try {
            if (path.endsWith("/register")) {
                User user = authService.registerCustomer(new AuthService.RegisterRequest(
                        req.getParameter("email"), req.getParameter("phone"),
                        req.getParameter("password"), req.getParameter("fullName")));
                loginSession(req, user);
                resp.sendRedirect(req.getContextPath() + "/");
                return;
            }
            if (path.endsWith("/login") || managementLogin) {
                User user = authService.login(req.getParameter("email"), req.getParameter("password"));
                if (managementLogin && user.role() == Role.CUSTOMER) {
                    throw new ServiceException.Forbidden(
                            "Tài khoản khách hàng không thể đăng nhập khu vực quản lý.");
                }
                if (!managementLogin && user.role() != Role.CUSTOMER) {
                    throw new ServiceException.Forbidden(
                            "Tài khoản quản lý hãy dùng trang đăng nhập quản lý.");
                }
                loginSession(req, user);
                // Tài khoản nội bộ (ADMIN / BRANCH_MANAGER / BRANCH_STAFF) luôn vào Dashboard quản lý.
                // Chỉ CUSTOMER mới được phép vào trang khách hàng (/) hoặc /booking.
                String target = (user.role() == Role.CUSTOMER)
                        ? (managementLogin ? "/console?module=profile" : "/")
                        : "/console?module=dashboard";
                resp.sendRedirect(req.getContextPath() + target);
            }
        } catch (ServiceException e) {
            renderForm(req, resp, !path.endsWith("/register"), managementLogin, e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unexpected authentication error", e);
            renderForm(req, resp, !path.endsWith("/register"), managementLogin,
                    "Không thể xử lý tài khoản lúc này.");
        }
    }

    private void loginSession(HttpServletRequest req, User user) {
        HttpSession session = req.getSession(true);
        session.setAttribute("userId", user.id());
        session.setAttribute("email", user.email());
        session.setAttribute("username", user.fullName());
        session.setAttribute("role", user.role().name());
        CsrfUtil.token(req);
    }

    private void renderForm(HttpServletRequest req, HttpServletResponse resp,
                            boolean login, boolean management, String error) throws IOException {
        String ctx = req.getContextPath();
        String action = management ? "/admin/login" : (login ? "/login" : "/register");
        String title = management ? "Đăng nhập quản lý" : (login ? "Đăng nhập" : "Tạo tài khoản");
        String description = management
                ? "Dành cho Admin, Branch Manager và Branch Staff."
                : (login ? "Truy cập tài khoản CinemaHub." : "Đăng ký tài khoản khách hàng.");
        String errorHtml = error == null ? "" :
                "<div class=\"flash flash-err\">" + escape(error) + "</div>";
        String registerFields = login ? "" :
                "<label>Họ tên<input type=\"text\" name=\"fullName\" required></label>"
                + "<label>Số điện thoại<input type=\"tel\" name=\"phone\" pattern=\"0[0-9]{9}\" required></label>";
        String alternate = management
                ? "<a href=\"" + ctx + "/login\">Đăng nhập khách hàng</a>"
                : (login ? "<a href=\"" + ctx + "/register\">Đăng ký ngay</a>"
                        + " · <a href=\"" + ctx + "/forgot-password\">Quên mật khẩu?</a>"
                        : "<a href=\"" + ctx + "/login\">Đăng nhập</a>");

        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().printf("""
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <link rel="stylesheet" href="%s/assets/css/cinema.css">
            <title>%s - CinemaHub</title></head><body><!-- cinema-layout -->
            <main class="auth-page"><div class="auth-card">
            <span class="eyebrow">%s</span><h1>%s</h1><p class="muted">%s</p>%s
            <form method="post" action="%s%s">
            <input type="hidden" name="_csrf" value="%s">
            <label>Email<input type="email" name="email" required></label>%s
            <label>Mật khẩu<input type="password" name="password" required minlength="8"></label>
            <button class="btn full" type="submit">%s</button></form>
            <p><a href="%s/">Trang chủ</a> · %s</p>
            </div></main></body></html>
            """, ctx, title, management ? "CINEMAHUB MANAGEMENT" : "CINEMAHUB ACCOUNT",
                title, description, errorHtml, ctx, action, escape(CsrfUtil.token(req)),
                registerFields, title, ctx, alternate);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

}
