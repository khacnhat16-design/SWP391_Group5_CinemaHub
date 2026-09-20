<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>
<c:set var="pageTitle" value="Đặt mật khẩu mới" scope="request"/>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>${pageTitle} | CinemaHub</title>
    <link rel="stylesheet" href="${ctx}/assets/css/cinema.css">
    <link rel="stylesheet" href="${ctx}/assets/css/dashboard.css">
</head>
<body class="auth-page">
<header class="nav-wrapper">
    <div class="nav container">
        <a class="logo" href="${ctx}/"><span class="logo-mark">C</span>Cinema<span class="accent">Hub</span></a>
        <nav><a href="${ctx}/login">Đăng nhập</a></nav>
    </div>
</header>

<main class="auth-main">
    <section class="auth-card">
        <h1>Đặt mật khẩu mới</h1>
        <p class="muted">Mật khẩu phải có ít nhất 8 ký tự.</p>

        <div id="reset-message" class="flash" hidden></div>

        <form id="reset-form" autocomplete="off">
            <label style="position:relative">
                Mật khẩu mới
                <span class="required-mark">*</span>
                <input type="password" name="newPassword" id="newPassword" minlength="8" required placeholder="Tối thiểu 8 ký tự">
                <button type="button" class="toggle-password" id="toggle-pw">Hiện</button>
            </label>
            <div class="password-strength weak" id="pw-strength">
                <span></span><span></span><span></span>
            </div>

            <label>
                Xác nhận mật khẩu
                <span class="required-mark">*</span>
                <input type="password" name="confirmPassword" id="confirmPassword" minlength="8" required placeholder="Nhập lại mật khẩu mới">
            </label>

            <button type="submit" class="btn full" id="submit-btn">Đặt lại mật khẩu</button>
        </form>
    </section>
</main>

<script>
    (function() {
        const params = new URLSearchParams(location.search);
        const form = document.getElementById('reset-form');
        const submitBtn = document.getElementById('submit-btn');
        const msg = document.getElementById('reset-message');
        const pw = document.getElementById('newPassword');
        const cf = document.getElementById('confirmPassword');
        const strength = document.getElementById('pw-strength');
        const togglePw = document.getElementById('toggle-pw');

        function flash(text, type) {
            msg.className = 'flash flash-' + (type || 'ok');
            msg.textContent = text;
            msg.hidden = false;
        }

        togglePw.addEventListener('click', () => {
            const hidden = pw.type === 'password';
            pw.type = hidden ? 'text' : 'password';
            togglePw.textContent = hidden ? 'Ẩn' : 'Hiện';
        });

        pw.addEventListener('input', () => {
            const len = pw.value.length;
            strength.className = 'password-strength '
                + (len < 6 ? 'weak' : len < 10 ? 'medium' : 'strong');
        });

        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (pw.value !== cf.value) { flash('Mật khẩu xác nhận không khớp', 'warn'); return; }
            if (pw.value.length < 8) { flash('Mật khẩu phải có ít nhất 8 ký tự', 'warn'); return; }

            submitBtn.disabled = true;
            submitBtn.textContent = 'Đang đặt lại...';
            try {
                const res = await fetch('${ctx}/forgot-password/reset', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: new URLSearchParams({
                        newPassword: pw.value,
                        confirmPassword: cf.value
                    })
                });
                const data = await res.json();
                if (res.ok) {
                    flash('Đặt lại mật khẩu thành công! Đang chuyển sang trang đăng nhập...', 'ok');
                    setTimeout(() => { window.location.href = '${ctx}/login'; }, 1500);
                } else {
                    flash((data && data.message) || 'Đặt lại mật khẩu thất bại', 'err');
                    submitBtn.disabled = false;
                    submitBtn.textContent = 'Đặt lại mật khẩu';
                }
            } catch (err) {
                flash('Lỗi kết nối: ' + err.message, 'err');
                submitBtn.disabled = false;
                submitBtn.textContent = 'Đặt lại mật khẩu';
            }
        });
    })();
</script>
</body>
</html>
