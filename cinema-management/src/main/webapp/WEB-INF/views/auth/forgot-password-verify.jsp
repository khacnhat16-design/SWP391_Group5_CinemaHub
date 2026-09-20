<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>
<c:set var="pageTitle" value="Xác nhận OTP" scope="request"/>
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
        <h1>Nhập mã OTP</h1>
        <p class="muted">Mã xác nhận 6 số đã được gửi đến email của bạn.</p>

        <div id="otp-message" class="flash" hidden></div>

        <div class="otp-input" id="otp-inputs">
            <input type="text" inputmode="numeric" maxlength="1" data-idx="0">
            <input type="text" inputmode="numeric" maxlength="1" data-idx="1">
            <input type="text" inputmode="numeric" maxlength="1" data-idx="2">
            <input type="text" inputmode="numeric" maxlength="1" data-idx="3">
            <input type="text" inputmode="numeric" maxlength="1" data-idx="4">
            <input type="text" inputmode="numeric" maxlength="1" data-idx="5">
        </div>

        <div class="otp-timer" id="otp-timer">Mã có hiệu lực 5 phút</div>

        <div class="form-actions">
            <button class="btn ghost" id="resend-btn" disabled>Gửi lại OTP (60s)</button>
            <button class="btn" id="verify-btn">Xác nhận</button>
        </div>

        <p class="muted small" style="margin-top:14px"><a href="${ctx}/forgot-password">← Đổi email khác</a></p>
    </section>
</main>

<script>
    (function() {
        const params = new URLSearchParams(location.search);
        const email = params.get('email') || '';
        const inputs = [...document.querySelectorAll('#otp-inputs input')];
        const timer = document.getElementById('otp-timer');
        const resendBtn = document.getElementById('resend-btn');
        const verifyBtn = document.getElementById('verify-btn');
        const msg = document.getElementById('otp-message');

        function flash(text, type) {
            msg.className = 'flash flash-' + (type || 'ok');
            msg.textContent = text;
            msg.hidden = false;
        }
        function getOtp() { return inputs.map(i => i.value).join(''); }

        // Auto-focus first input + auto-tab to next on type
        inputs.forEach((inp, i) => {
            inp.addEventListener('input', () => {
                inp.value = inp.value.replace(/\D/g, '').slice(0, 1);
                if (inp.value && i < inputs.length - 1) inputs[i + 1].focus();
            });
            inp.addEventListener('keydown', (e) => {
                if (e.key === 'Backspace' && !inp.value && i > 0) inputs[i - 1].focus();
            });
        });
        inputs[0].focus();

        // Countdown + resend cooldown
        let secondsLeft = 60;
        const tick = setInterval(() => {
            secondsLeft -= 1;
            if (secondsLeft <= 0) {
                clearInterval(tick);
                timer.classList.add('expired');
                timer.textContent = 'Bạn có thể gửi lại OTP ngay bây giờ';
                resendBtn.disabled = false;
                resendBtn.textContent = 'Gửi lại OTP';
            } else {
                resendBtn.textContent = 'Gửi lại OTP (' + secondsLeft + 's)';
            }
        }, 1000);

        resendBtn.addEventListener('click', async () => {
            if (resendBtn.disabled) return;
            resendBtn.disabled = true;
            try {
                await fetch('${ctx}/forgot-password', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'email=' + encodeURIComponent(email)
                });
                flash('Đã gửi lại OTP. Vui lòng kiểm tra email.', 'ok');
                secondsLeft = 60;
                resendBtn.textContent = 'Gửi lại OTP (60s)';
                const tick2 = setInterval(() => {
                    secondsLeft -= 1;
                    if (secondsLeft <= 0) {
                        clearInterval(tick2);
                        resendBtn.disabled = false;
                        resendBtn.textContent = 'Gửi lại OTP';
                    } else {
                        resendBtn.textContent = 'Gửi lại OTP (' + secondsLeft + 's)';
                    }
                }, 1000);
            } catch (e) {
                flash('Lỗi khi gửi lại OTP: ' + e.message, 'err');
                resendBtn.disabled = false;
            }
        });

        verifyBtn.addEventListener('click', async () => {
            const otp = getOtp();
            if (otp.length !== 6) { flash('Vui lòng nhập đủ 6 số OTP', 'warn'); return; }
            verifyBtn.disabled = true;
            verifyBtn.textContent = 'Đang xác nhận...';
            try {
                const res = await fetch('${ctx}/forgot-password/verify', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'email=' + encodeURIComponent(email) + '&otp=' + encodeURIComponent(otp)
                });
                const data = await res.json();
                if (res.ok) {
                    flash('OTP hợp lệ! Chuyển sang đặt mật khẩu mới...', 'ok');
                    setTimeout(() => {
                        window.location.href = '${ctx}/forgot-password/reset';
                    }, 800);
                } else {
                    flash((data && data.message) || 'OTP không hợp lệ', 'err');
                    verifyBtn.disabled = false;
                    verifyBtn.textContent = 'Xác nhận';
                }
            } catch (e) {
                flash('Lỗi kết nối: ' + e.message, 'err');
                verifyBtn.disabled = false;
                verifyBtn.textContent = 'Xác nhận';
            }
        });
    })();
</script>
</body>
</html>
