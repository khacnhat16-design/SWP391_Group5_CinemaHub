<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Landing page công khai (welcome-file của WAR, route "/" của embedded CinemaServlet).
  Trước đây file forward sang /cinema — servlet không tồn tại → 404 ở WAR mode.

  Trang tĩnh, presentation-only: chỉ điều hướng tới các route đã có
  (/console?module=discovery — lịch chiếu công khai, /login, /register).
  Không scriptlet, không EL gọi Service/DAO, không backend data.
--%>
<c:set var="pageTitle" value="Điện ảnh hay, trải nghiệm tuyệt vời" scope="request"/>
<%@ include file="/WEB-INF/views/layout/header.jsp" %>

<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>

<main>
    <section class="hero">
        <div class="hero-overlay"></div>
        <div class="hero-content container">
            <span class="eyebrow">MULTI-BRANCH CINEMA</span>
            <h1>Điện ảnh hay.<br><span class="accent">Trải nghiệm tuyệt vời.</span></h1>
            <p>Khám phá lịch chiếu toàn hệ thống CinemaHub, chọn chi nhánh và suất chiếu phù hợp với bạn.</p>
            <div class="actions">
                <a class="btn" href="${ctx}/console?module=discovery">Xem lịch chiếu</a>
                <c:choose>
                    <c:when test="${empty sessionScope.username}">
                        <a class="btn ghost" href="${ctx}/register">Tạo tài khoản</a>
                    </c:when>
                    <c:otherwise>
                        <a class="btn ghost" href="${ctx}/console">Vào khu vực khách hàng</a>
                    </c:otherwise>
                </c:choose>
            </div>
        </div>
    </section>

    <section class="section container" id="nowShowingSection" aria-label="Phim đang chiếu">
        <div class="section-heading">
            <div>
                <span class="eyebrow">PHIM ĐANG CHIẾU</span>
                <h2>Phim mới nhất đang được chiếu</h2>
            </div>
            <a class="btn ghost small" href="${ctx}/console?module=discovery">Xem tất cả</a>
        </div>
        <div class="movie-grid" id="nowShowingGrid" aria-busy="true">
            <div class="empty-state"><p>Đang tải danh sách phim…</p></div>
        </div>
    </section>

    <section class="section container">
        <div class="section-heading">
            <div>
                <span class="eyebrow">WHY CINEMAHUB</span>
                <h2>Đặt vé thông minh trên toàn chuỗi</h2>
            </div>
        </div>
        <div class="stat-grid">
            <div class="stat">
                <span>Chi nhánh</span>
                <strong class="accent">Đa điểm</strong>
                <small>Mở rộng khắp hệ thống</small>
            </div>
            <div class="stat">
                <span>Tài khoản</span>
                <strong class="accent">Cá nhân hóa</strong>
                <small>Quản lý hồ sơ và vé đã đặt</small>
            </div>
            <div class="stat">
                <span>Suất chiếu</span>
                <strong class="accent">Cập nhật</strong>
                <small>Lịch chiếu mở bán realtime</small>
            </div>
            <div class="stat">
                <span>Đặt vé</span>
                <strong class="accent">Trực tuyến</strong>
                <small>Chọn suất và ghế thuận tiện</small>
            </div>
        </div>
    </section>

    <c:if test="${empty sessionScope.username}">
        <section class="section container">
            <div class="promo">
                <div>
                    <span class="eyebrow">CINEMAHUB ACCOUNT</span>
                    <h2>Tạo tài khoản CinemaHub</h2>
                    <p>Quản lý hồ sơ, vé đã đặt và nhận thông báo về suất chiếu mới.</p>
                </div>
                <div class="actions">
                    <a class="btn" href="${ctx}/register">Tạo tài khoản</a>
                    <a class="btn ghost" href="${ctx}/login">Đăng nhập</a>
                </div>
            </div>
        </section>
    </c:if>
</main>

<script>window.CINEMA_CONTEXT = '${ctx}';</script>
<script src="${ctx}/assets/js/app.js?v=20261004-header-notifications-1" defer></script>
<script src="${ctx}/assets/js/home.js?v=20261007-now-showing-1" defer></script>
<%@ include file="/WEB-INF/views/layout/footer.jsp" %>
