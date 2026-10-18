<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="customerCtx" value="${pageContext.request.contextPath}"/>
<c:set var="customerName" value="${empty sessionScope.username ? '' : sessionScope.username}"/>
<c:set var="customerInitial" value="${fn:length(customerName) == 0 ? '?' : fn:toUpperCase(fn:substring(customerName, 0, 1))}"/>
<c:set var="customerActive" value="${empty param.module ? 'discovery' : param.module}"/>

<aside class="app-sidebar" id="customerSidebar" aria-label="Menu khách hàng">
    <nav class="sb-section" aria-label="Điều hướng tài khoản">
        <div class="sb-title">Lịch chiếu</div>
        <a class="sb-link ${customerActive == 'discovery' ? 'active' : ''}" href="${customerCtx}/console?module=discovery">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
            </svg>
            Lịch chiếu
        </a>

        <div class="sb-title">Tài khoản</div>
        <a class="sb-link ${customerActive == 'profile' ? 'active' : ''}" href="${customerCtx}/console?module=profile">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="7" r="4"/>
            </svg>
            Hồ sơ
        </a>
        <a class="sb-link ${customerActive == 'my-tickets' ? 'active' : ''}" href="${customerCtx}/console?module=my-tickets">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M2 9a3 3 0 0 1 3-3h14a3 3 0 0 1 3 3v2a3 3 0 0 0 0 6v2a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3v-2a3 3 0 0 0 0-6z"/>
                <line x1="13" y1="6" x2="13" y2="12"/>
            </svg>
            Vé của tôi
        </a>
        <a class="sb-link ${customerActive == 'reviews' ? 'active' : ''}" href="${customerCtx}/console?module=reviews">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.4l6.1-.9L12 3Z"/>
            </svg>
            Đánh giá chất lượng
        </a>
    </nav>

    <div class="sb-footer">
        <div>Đăng nhập: <strong><c:out value="${customerName}"/></strong></div>
        <div>CUSTOMER</div>
    </div>
</aside>
