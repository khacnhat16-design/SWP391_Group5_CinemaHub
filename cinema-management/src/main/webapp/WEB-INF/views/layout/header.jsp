<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="ctx" value="${pageContext.request.contextPath}" scope="request"/>
<!doctype html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta name="ctx" content="${ctx}">
    <meta name="user-role" content="${empty sessionScope.role ? 'GUEST' : sessionScope.role}">
    <meta name="user-id" content="${sessionScope.role == 'CUSTOMER' || sessionScope.role == 'ADMIN' || sessionScope.role == 'BRANCH_MANAGER' || sessionScope.role == 'BRANCH_STAFF' ? sessionScope.userId : ''}">
    <title>${empty pageTitle ? 'CinemaHub - Quản lý rạp phim' : pageTitle} | CinemaHub</title>
    <link rel="stylesheet" href="${ctx}/assets/css/cinema.css?v=20261011-user-id-meta-1">
    <c:if test="${useWorkspaceCss}">
        <%-- App shell + workspace light theme. Chỉ nạp cho trang quản trị:
             dashboard.css định nghĩa .empty-state/.loading-state nền trắng, sẽ phá
             giao diện dark của trang public (discovery.jsp). --%>
        <link rel="stylesheet" href="${ctx}/assets/css/dashboard.css?v=20260927-4">
            <link rel="stylesheet" href="${ctx}/assets/css/workspace.css?v=20261013-bsap-4">
    </c:if>
    <c:if test="${loadRscCss}">
        <%-- CSS riêng cho module hỗ trợ và đánh giá.
             Customer area cũng cần vì các module này dùng ws-card/ws-label/ws-btn.
             workspace.css toàn phần sẽ phá theme discovery (background dark) và
             làm sidebar admin bị include — chỉ load rsc.css vừa đủ. --%>
        <link rel="stylesheet" href="${ctx}/assets/css/rsc.css?v=20261012-review-ui-2">
    </c:if>
</head>
<body>
<c:set var="currentRole" value="${sessionScope.role}"/>
<header class="nav-wrapper">
    <div class="nav container">
        <a class="logo" href="${ctx}/"><span class="logo-mark">C</span>Cinema<span class="accent">Hub</span></a>
        <nav aria-label="Điều hướng chính">
            <a href="${ctx}/">Trang chủ</a>
            <%--
              Nav trỏ về các trang JSP thực sự tồn tại (PageController /console/{domain}/list
              hoặc /console?module=...). Không trỏ vào endpoint REST trả JSON như /branch,
              /booking… vì chúng trả application/json thô, không phải HTML.
            --%>
            <c:choose>
                <c:when test="${currentRole == 'ADMIN'}">
                    <a href="${ctx}/console?module=discovery">Lịch chiếu</a>
                    <a href="${ctx}/console/branch/list">Chi nhánh</a>
                </c:when>
                <c:when test="${currentRole == 'BRANCH_MANAGER'}">
                    <a href="${ctx}/console?module=discovery">Lịch chiếu</a>
                </c:when>
                <c:when test="${currentRole == 'BRANCH_STAFF'}">
                    <a href="${ctx}/console?module=ticket">Lịch sử vé</a>
                </c:when>
                <c:when test="${currentRole == 'CUSTOMER'}">
                    <a href="${ctx}/console?module=discovery">Lịch chiếu</a>
                </c:when>
                <c:otherwise>
                    <a href="${ctx}/console?module=discovery">Lịch chiếu</a>
                    <a href="${ctx}/register">Đăng ký</a>
                </c:otherwise>
            </c:choose>
            <c:choose>
                <c:when test="${empty sessionScope.username}">
                    <a class="btn" href="${ctx}/login">Đăng nhập</a>
                </c:when>
                <c:otherwise>
                    <c:if test="${currentRole == 'CUSTOMER'}">
                        <span class="nav-notif-wrap">
                            <button class="nav-bell" type="button" id="notificationBell"
                                    aria-label="Thông báo" aria-expanded="false" title="Thông báo">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                    <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
                                    <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
                                </svg>
                                <span id="notifCount" class="badge" style="display:none"></span>
                            </button>
                            <div class="notification-popover" id="notificationPopover" hidden></div>
                        </span>
                    </c:if>
                    <c:choose>
                        <c:when test="${currentRole == 'CUSTOMER'}">
                            <a class="btn ghost nav-user-btn" href="${ctx}/console?module=profile">
                                <c:set var="userInitial" value="${fn:substring(sessionScope.username, 0, 1)}"/>
                                <span class="nav-avatar">${fn:toUpperCase(userInitial)}</span>
                                <span class="nav-username"><c:out value="${sessionScope.username}"/></span>
                            </a>
                            <a class="btn ghost" href="${ctx}/logout">Thoát</a>
                        </c:when>
                        <c:otherwise>
                            <a href="${ctx}/console">Quản lý</a>
                            <a class="btn ghost" href="${ctx}/logout">Thoát (<c:out value="${sessionScope.username}"/>)</a>
                        </c:otherwise>
                    </c:choose>
                </c:otherwise>
            </c:choose>
        </nav>
    </div>
</header>
<c:if test="${not empty branchScopeId}">
    <div class="container" role="status">
        <div class="scope-banner" aria-live="polite">
            <span aria-hidden="true">🏢</span>
            <span>Bạn đang thao tác cho <strong><c:out value="${branchScopeName}"/></strong> — mọi truy vấn danh sách chỉ trả dữ liệu thuộc chi nhánh này.</span>
        </div>
    </div>
</c:if>
<c:if test="${not empty requestScope.notice}">
    <div class="container" role="status"><div class="flash flash-ok" role="alert"><c:out value="${requestScope.notice}"/></div></div>
</c:if>
<c:if test="${not empty requestScope.error}">
    <div class="container" role="alert"><div class="flash flash-err"><c:out value="${requestScope.error}"/></div></div>
</c:if>
<c:if test="${not empty sessionScope.csrfToken}">
    <meta name="csrf-token" content="${sessionScope.csrfToken}">
</c:if>
