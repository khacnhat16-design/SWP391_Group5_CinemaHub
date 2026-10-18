<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/views/layout/header.jsp" %>

<main class="container section">
    <div class="auth-card">
        <span class="eyebrow">BOOKING</span>
        <h1>Vui lòng đăng nhập để đặt vé</h1>
        <p class="muted">Bạn vẫn có thể xem phim và suất chiếu. Hãy đăng nhập hoặc đăng ký trước khi giữ ghế và thanh toán.</p>
        <div class="actions">
            <a class="btn" href="${ctx}/login">Đăng nhập</a>
            <a class="btn ghost" href="${ctx}/register">Đăng ký</a>
            <a class="btn ghost" href="${ctx}/">Quay lại</a>
        </div>
    </div>
</main>

<%@ include file="/WEB-INF/views/layout/footer.jsp" %>
