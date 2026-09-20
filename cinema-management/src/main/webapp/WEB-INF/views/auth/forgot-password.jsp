<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
    <%@ taglib prefix="c" uri="jakarta.tags.core" %>
        <c:set var="ctx" value="${pageContext.request.contextPath}" scope="request" />
        <c:set var="pageTitle" value="Quên mật khẩu" scope="request" />
        <!doctype html>
        <html lang="vi">

        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${pageTitle} | CinemaHub</title>
            <link rel="stylesheet" href="${ctx}/assets/css/cinema.css">
            <link rel="stylesheet" href="${ctx}/assets/css/dashboard.css">
            <link rel="stylesheet" href="${ctx}/assets/css/auth-pages.css">
        </head>

        <body class="auth-page">
            <header class="nav-wrapper">
                <div class="nav container">
                    <a class="logo" href="${ctx}/"><span class="logo-mark">C</span>Cinema<span
                            class="accent">Hub</span></a>
                    <nav>
                        <a href="${ctx}/login">Đăng nhập</a>
                        <a href="${ctx}/register">Đăng ký</a>
                    </nav>
                </div>
            </header>

            <main class="auth-main">
                <section class="auth-card forgot-card">
                    <h1>Quên mật khẩu?</h1>
                    <p class="muted">Nhập email đăng ký — chúng tôi sẽ gửi mã OTP 6 số để bạn đặt lại mật khẩu.</p>

                    <div id="forgot-message" class="flash" hidden></div>

                    <form id="forgot-form" autocomplete="off">
                        <label>
                            Email
                            <span class="required-mark">*</span>
                            <input type="email" name="email" id="email" required placeholder="you@example.com">
                        </label>
                        <button type="submit" class="btn full" id="submit-btn">Gửi mã OTP</button>
                    </form>

                    <p class="muted small" style="margin-top:14px">
                        <a href="${ctx}/login">← Quay lại đăng nhập</a>
                    </p>
                </section>
            </main>

            <script>
                (function () {
                    const ctx = document.querySelector('meta[name="context"]')?.content
                        || '${ctx}';
                    const form = document.getElementById('forgot-form');
                    const msg = document.getElementById('forgot-message');
                    const btn = document.getElementById('submit-btn');

                    function flash(text, type) {
                        msg.className = 'flash flash-' + (type || 'ok');
                        msg.textContent = text;
                        msg.hidden = false;
                    }

                    form.addEventListener('submit', async (e) => {
                        e.preventDefault();
                        btn.disabled = true;
                        btn.textContent = 'Đang gửi...';
                        try {
                            const res = await fetch('${ctx}/forgot-password', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'email=' + encodeURIComponent(document.getElementById('email').value)
                            });
                            const data = await res.json();
                            if (res.ok) {
                                flash(data.message || 'Đã gửi mã OTP. Vui lòng kiểm tra email.', 'ok');
                                setTimeout(() => {
                                    window.location.href = '${ctx}/forgot-password/verify?email='
                                        + encodeURIComponent(document.getElementById('email').value);
                                }, 1200);
                            } else {
                                flash((data && data.message) || 'Gửi OTP thất bại', 'err');
                                btn.disabled = false;
                                btn.textContent = 'Gửi mã OTP';
                            }
                        } catch (err) {
                            flash('Lỗi kết nối: ' + err.message, 'err');
                            btn.disabled = false;
                            btn.textContent = 'Gửi mã OTP';
                        }
                    });
                })();
            </script>
        </body>

        </html>