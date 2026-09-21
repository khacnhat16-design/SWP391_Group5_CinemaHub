<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Trang đăng nhập (AuthController: GET/POST /login).

  Chỉ dùng class có sẵn trong cinema.css: .auth-page, .auth-card, .eyebrow, .demo-hint.
  Form action/method, tên field (email/password) và param _csrf giữ nguyên backend.

  Lưu ý: thông báo lỗi requestScope.error đã được header.jsp render thành .flash-err
  dùng chung — không lặp lại ở đây để tránh hiện 2 lần.
--%>
<%@ include file="/WEB-INF/views/layout/header.jsp" %>

<main class="auth-page">
    <div class="auth-card">
        <span class="eyebrow">CINEMAHUB ACCOUNT</span>
        <h1>Đăng nhập</h1>
        <p class="muted">Truy cập khu vực vận hành và vé của bạn.</p>

        <form method="post" action="${ctx}/login" autocomplete="on">
            <input type="hidden" name="_csrf" value="${requestScope.csrfToken}">

            <label for="email">Email *</label>
            <input type="email" id="email" name="email" autocomplete="username" required autofocus>

            <label for="password">Mật khẩu *</label>
            <input type="password" id="password" name="password"
                   autocomplete="current-password" required minlength="8">

            <button type="submit" class="btn full">Đăng nhập</button>
        </form>

        <div class="demo-hint">
            Chưa có tài khoản? <a href="${ctx}/register">Đăng ký ngay</a> để tích điểm và nhận ưu đãi.
            <br>
            <a href="${ctx}/forgot-password">Quên mật khẩu?</a>
        </div>
    </div>
</main>

<%@ include file="/WEB-INF/views/layout/footer.jsp" %>
