<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Khu vực quản lý CinemaHub.
  - ADMIN / BRANCH_MANAGER / BRANCH_STAFF => giao diện quản lý
                                              + nội dung được render trực tiếp bằng JSP theo ?module=
  - CUSTOMER => giao diện khách hàng để giữ nguyên luồng đặt vé
  - GUEST    => yêu cầu đăng nhập quản lý.

  Giao diện quản lý được render trực tiếp bởi Servlet và JSP.
--%>
<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>
<c:set var="currentRole" value="${empty sessionScope.role ? 'GUEST' : sessionScope.role}"/>
<c:set var="isInternal" value="${currentRole == 'ADMIN' or currentRole == 'BRANCH_MANAGER' or currentRole == 'BRANCH_STAFF'}"/>
<c:set var="currentModule" value="${empty param.module ? 'dashboard' : param.module}"/>

<c:choose>
    <%-- ============ INTERNAL USERS (workspace theme) ============ --%>
    <c:when test="${isInternal}">
        <c:set var="pageTitle" value="${empty requestScope.pageTitle ? 'Bảng điều khiển' : requestScope.pageTitle}" scope="request"/>
        <!doctype html>
        <html lang="vi">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="ctx" content="${ctx}">
            <meta name="user-role" content="${currentRole}">
            <meta name="csrf-token" content="${sessionScope.csrfToken}">
            <title><c:out value="${pageTitle}"/> | Khu vực quản lý CinemaHub</title>
            <link rel="stylesheet" href="${ctx}/assets/css/cinema.css">
            <link rel="stylesheet" href="${ctx}/assets/css/workspace.css?v=20261004-branch-pricing-templates-1">
        </head>
        <body class="workspace-page">
            <%@ include file="/WEB-INF/views/layout/workspace-sidebar.jsp" %>
            <main class="ws-main" id="wsMain">
                <%@ include file="/WEB-INF/views/layout/workspace-header.jsp" %>
                <div class="ws-main-inner">
                    <div id="viewRoot" class="ws-server-view"><%-- Client-side JS render --%></div>
                </div>
            </main>
            <script src="${ctx}/assets/js/app.js?v=20261004-showtime-reminders-1" defer></script>
            <script src="${ctx}/assets/js/page-router.js?v=20260925-1" defer></script>
            <script src="${ctx}/assets/js/list-page.js?v=20261003-pricing-target-1" defer></script>
            <script src="${ctx}/assets/js/form-page.js?v=20261004-movie-author-validation-1" defer></script>
            <script src="${ctx}/assets/js/modules-form-pages.js?v=20261004-movie-author-validation-1" defer></script>
            <script src="${ctx}/assets/js/console.js?v=20261004-sidebar-active-1" defer></script>
        </body>
        </html>
    </c:when>

    <%-- ============ CUSTOMER (workspace shell with a customer-only sidebar) ============ --%>
    <c:when test="${currentRole == 'CUSTOMER'}">
        <c:set var="useWorkspaceCss" value="true" scope="page"/>
        <c:set var="loadRscCss" value="true" scope="page"/>
        <c:set var="pageTitle" value="${empty requestScope.pageTitle ? 'Bảng điều khiển' : requestScope.pageTitle}" scope="request"/>
        <c:choose>
            <c:when test="${empty sessionScope.username}">
                <%@ include file="/WEB-INF/views/layout/header.jsp" %>
                <div class="container auth-page">
                    <div class="auth-card">
                        <span class="eyebrow">KHU VỰC KHÁCH HÀNG CINEMAHUB</span>
                        <h1>Cần đăng nhập</h1>
                        <p class="muted">Vui lòng đăng nhập để sử dụng khu vực khách hàng.</p>
                        <div class="actions">
                            <a class="btn" href="${ctx}/login">Đăng nhập</a>
                            <a class="btn ghost" href="${ctx}/register">Tạo tài khoản</a>
                        </div>
                    </div>
                </div>
                <%@ include file="/WEB-INF/views/layout/footer.jsp" %>
            </c:when>
            <c:otherwise>
                <%@ include file="/WEB-INF/views/layout/header.jsp" %>
                <div class="app-shell">
                    <%@ include file="/WEB-INF/views/layout/customer-sidebar.jsp" %>
                    <main class="app-main" id="appMain">
                        <div class="app-topbar">
                            <div>
                                <button class="sb-toggle" type="button" id="customerSidebarToggle"
                                        aria-controls="customerSidebar" aria-expanded="false">
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                        <line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/>
                                        <line x1="3" y1="18" x2="21" y2="18"/>
                                    </svg>
                                    Menu
                                </button>
                                <h1><c:out value="${empty requestScope.pageTitle ? 'Bảng điều khiển' : requestScope.pageTitle}"/></h1>
                                <p class="muted" style="margin:4px 0 0;font-size:.86rem">
                                    <c:out value="${empty requestScope.pageSubtitle ? 'Dữ liệu tài khoản và dịch vụ khách hàng.' : requestScope.pageSubtitle}"/>
                                </p>
                            </div>
                            <div class="topbar-actions" id="topbarActions"></div>
                        </div>
                        <div id="viewRoot" aria-busy="true">
                            <div class="loading-state" role="status" aria-live="polite">
                                <span class="spinner" aria-hidden="true"></span>
                                <span>Đang tải dữ liệu…</span>
                            </div>
                        </div>
                    </main>
                    <div class="sidebar-backdrop" id="customerSidebarBackdrop" hidden></div>
                </div>
                <%@ include file="/WEB-INF/views/layout/appshell-close.jsp" %>
                <script src="${ctx}/assets/js/app.js?v=20261004-showtime-reminders-1" defer></script>
                <script src="${ctx}/assets/js/page-router.js?v=20260925-1" defer></script>
                <script src="${ctx}/assets/js/list-page.js?v=20261003-no-list-ids-1" defer></script>
                <script src="${ctx}/assets/js/form-page.js?v=20261004-movie-author-validation-1" defer></script>
                <script src="${ctx}/assets/js/modules-form-pages.js?v=20261004-movie-author-validation-1" defer></script>
                <script src="${ctx}/assets/js/console.js?v=20261004-sidebar-active-1" defer></script>
                <%@ include file="/WEB-INF/views/layout/footer.jsp" %>
            </c:otherwise>
        </c:choose>
    </c:when>

    <%-- ============ GUEST ============ --%>
    <c:otherwise>
        <c:set var="useWorkspaceCss" value="true" scope="page"/>
        <c:set var="pageTitle" value="Dang nhap quan ly" scope="request"/>
        <%@ include file="/WEB-INF/views/layout/header.jsp" %>
        <div class="container auth-page">
            <div class="auth-card">
                <span class="eyebrow">KHU VỰC QUẢN LÝ CINEMAHUB</span>
                <h1>Cần đăng nhập</h1>
                <p class="muted">Khu vực quản lý yêu cầu tài khoản có quyền vận hành.</p>
                <div class="actions">
                    <a class="btn" href="${ctx}/admin/login">Đăng nhập quản lý</a>
                    <a class="btn ghost" href="${ctx}/">Về trang chủ</a>
                </div>
            </div>
        </div>
        <%@ include file="/WEB-INF/views/layout/footer.jsp" %>
    </c:otherwise>
</c:choose>
