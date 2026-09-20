<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%--
  Thanh đầu trang của khu vực quản lý.
  Tìm kiếm bên trái, thông báo và tài khoản bên phải.
  Menu tài khoản gồm hồ sơ và đăng xuất.
--%>
<c:set var="wsCtx" value="${pageContext.request.contextPath}"/>
<c:set var="wsRole" value="${empty sessionScope.role ? 'GUEST' : sessionScope.role}"/>
<c:set var="wsName" value="${empty sessionScope.username ? '' : sessionScope.username}"/>
<c:set var="wsInitial" value="${fn:length(wsName) == 0 ? '?' : fn:toUpperCase(fn:substring(wsName, 0, 1))}"/>
<c:set var="headerTitle" value="${empty requestScope.pageTitle ? 'Bảng điều khiển' : requestScope.pageTitle}"/>
<c:set var="headerEyebrow" value="${empty requestScope.pageEyebrow ? 'KHU VỰC QUẢN LÝ CINEMAHUB' : requestScope.pageEyebrow}"/>

<header class="ws-header">
    <button type="button" class="ws-sidebar-toggle" id="wsSidebarToggle" aria-label="Mở menu" aria-controls="wsSidebar">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
    </button>

    <div class="ws-header-title">
        <span class="eyebrow"><c:out value="${headerEyebrow}"/></span>
        <h1><c:out value="${headerTitle}"/></h1>
    </div>

    <div class="ws-header-search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="search" placeholder="Tìm phim, đặt vé, mã vé..." aria-label="Tìm kiếm">
    </div>

    <div class="ws-header-actions" style="position:relative">
        <%--
          BỎ BELL NOTIFICATION + DROPDOWN HỒ SƠ/THÔNG BÁO CHO WORKSPACE.
          Lý do: ADMIN / BRANCH_MANAGER / BRANCH_STAFF đã có workspace chuyên trách;
          bell notification UI chỉ phục vụ shell khách hàng (CUSTOMER).
          Workspace chỉ giữ nút "Đăng xuất" và avatar user để biết ai đang thao tác.
        --%>
        <%-- User info (read-only, không có dropdown) --%>
        <span class="ws-user-info ws-user-info-static" aria-hidden="true">
            <span class="ws-user-avatar ws-user-avatar-static">${wsInitial}</span>
            <span class="ws-user-detail">
                <strong><c:out value="${fn:length(wsName) == 0 ? 'Nguoi dung' : wsName}"/></strong>
                <small><c:out value="${wsRole}"/></small>
            </span>
        </span>
        <%-- Logout button --%>
        <a class="ws-btn ws-btn-sm ws-btn-logout" href="${wsCtx}/logout" role="button" aria-label="Đăng xuất">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
                <polyline points="16 17 21 12 16 7"/>
                <line x1="21" y1="12" x2="9" y2="12"/>
            </svg>
            <span>Đăng xuất</span>
        </a>
    </div>
</header>
