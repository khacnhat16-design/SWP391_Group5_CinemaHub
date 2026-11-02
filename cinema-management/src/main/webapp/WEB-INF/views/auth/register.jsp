<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ include file="/WEB-INF/views/layout/header.jsp" %>

<div class="container section">
    <div class="page-heading">
        <div>
            <span class="eyebrow">CINEMAHUB ACCOUNT</span>
            <h1>Đăng ký tài khoản khách hàng</h1>
            <p class="muted">Tạo tài khoản để tích điểm và nhận ưu đãi.</p>
        </div>
    </div>

    <div class="auth-card">
        <%-- Lỗi (requestScope.error) đã được header.jsp render thành .flash-err dùng chung. --%>
        <form method="post" action="${ctx}/register" class="auth-form">
            <input type="hidden" name="_csrf" value="${requestScope.csrfToken}">

            <div class="form-card">
                <h1>Thông tin tài khoản</h1>

                <div class="form-grid">
                    <div>
                        <label for="email">Email *</label>
                        <input type="email" id="email" name="email" required>
                    </div>

                    <div>
                        <label for="phone">Số điện thoại (0xxxxxxxxx) *</label>
                        <input type="tel" id="phone" name="phone" pattern="0[0-9]{9}" required>
                    </div>

                    <div class="full">
                        <label for="fullName">Họ tên *</label>
                        <input type="text" id="fullName" name="fullName" required>
                    </div>

                    <div class="full">
                        <label for="password">Mật khẩu (tối thiểu 8 ký tự, có chữ và số) *</label>
                        <input type="password" id="password" name="password" required>
                    </div>
                </div>

                <button type="submit" class="btn" style="margin-top: 16px;">Đăng ký</button>
            </div>
        </form>

        <p style="margin-top: 24px;">Đã có tài khoản? <a href="${ctx}/login">Đăng nhập</a></p>
    </div>
</div>

<%@ include file="/WEB-INF/views/layout/footer.jsp" %>
