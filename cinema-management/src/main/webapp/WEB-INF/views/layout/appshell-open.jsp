<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Mở app shell cho khu vực quản trị. Include SAU header.jsp.
  Request attrs tùy chọn: pageTitle, breadcrumb, activeNav, branchScopeName.
<<<<<<< HEAD
  Yêu cầu: app-shell styles được thêm trong header.jsp.
=======
  Yêu cầu: <link> tới dashboard.css được thêm trong header.jsp.
>>>>>>> nhatnk
--%>
<div class="app-shell">
    <%@ include file="/WEB-INF/views/layout/workspace-sidebar.jsp" %>
    <main class="app-main" id="appMain">
        <div class="app-topbar">
            <div>
                <button class="sb-toggle" type="button" id="sbToggle" aria-controls="appSidebar" aria-expanded="false">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
                    Menu
                </button>
                <c:if test="${not empty requestScope.breadcrumb}">
                    <div class="breadcrumb">${requestScope.breadcrumb}</div>
                </c:if>
                <h1>${empty requestScope.pageTitle ? 'CinemaHub' : requestScope.pageTitle}</h1>
                <p class="muted" style="margin:4px 0 0;font-size:.86rem">
                    <c:out value="${empty requestScope.pageSubtitle ? 'Dữ liệu được phân quyền theo phạm vi chi nhánh.' : requestScope.pageSubtitle}"/>
                </p>
            </div>
            <div class="topbar-actions" id="topbarActions"></div>
        </div>

        <c:if test="${not empty requestScope.branchScopeName}">
            <div class="scope-banner" role="status">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>
                <span>Phạm vi: <strong><c:out value="${requestScope.branchScopeName}"/></strong> — mọi truy vấn và ghi dữ liệu chỉ áp dụng cho chi nhánh này.</span>
            </div>
        </c:if>

        <div id="viewRoot" aria-busy="true">
            <div class="loading-state" role="status" aria-live="polite">
                <span class="spinner" aria-hidden="true"></span>
                <span>Đang tải dữ liệu…</span>
            </div>
        </div>
