<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%--
  Sidebar dùng chung cho khu vực quản trị (app shell).
  Phân menu theo role: ADMIN / BRANCH_MANAGER / BRANCH_STAFF / CUSTOMER.
  Reuse sessionScope.role, sessionScope.username; requestScope.branchScopeName (nếu có).
  Active state: ưu tiên ?module= (URL console), fallback requestScope.activeNav.
--%>
<c:set var="role" value="${empty sessionScope.role ? 'GUEST' : sessionScope.role}"/>
<c:set var="activeNav" value="${not empty param.module ? param.module : (empty requestScope.activeNav ? '' : requestScope.activeNav)}"/>

<aside class="app-sidebar" id="appSidebar" aria-label="Điều hướng quản trị">
    <div class="sb-section">
        <div class="sb-title">Tổng quan</div>
        <a class="sb-link ${activeNav == 'discovery' ? 'active' : ''}" href="${ctx}/console?module=discovery">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
            Lịch chiếu
        </a>
    </div>

    <c:choose>
        <c:when test="${role == 'ADMIN'}">
            <div class="sb-section">
                <div class="sb-title">Quản trị tập trung</div>
                <a class="sb-link ${activeNav == 'branch' ? 'active' : ''}" href="${ctx}/console?module=branch">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>
                    Chi nhánh
                </a>
                <a class="sb-link ${activeNav == 'movie' ? 'active' : ''}" href="${ctx}/console?module=movie">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/></svg>
                    Phim
                </a>
            </div>
        </c:when>
        <c:when test="${role == 'BRANCH_MANAGER'}">
            <div class="sb-section">
                <div class="sb-title">Chi nhánh của tôi</div>
                <a class="sb-link ${activeNav == 'screen' ? 'active' : ''}" href="${ctx}/console?module=screen">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/></svg>
                    Phòng &amp; ghế
                </a>
                <a class="sb-link ${activeNav == 'showtime' ? 'active' : ''}" href="${ctx}/console?module=showtime">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
                    Lịch chiếu
                </a>
            </div>
        </c:when>
        <c:when test="${role == 'BRANCH_STAFF'}">
            <div class="sb-section">
                <div class="sb-title">Vé</div>
                <a class="sb-link ${activeNav == 'ticket' ? 'active' : ''}" href="${ctx}/console?module=ticket">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12V8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v4"/><path d="M2 12h20"/><path d="M4 12v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6"/></svg>
                    Lịch sử vé
                </a>
            </div>
        </c:when>
        <c:otherwise>
            <div class="sb-section">
                <div class="sb-title">Tài khoản</div>
                <a class="sb-link ${activeNav == 'profile' ? 'active' : ''}" href="${ctx}/console?module=profile">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M20 21a8 8 0 0 0-16 0"/>
                        <circle cx="12" cy="7" r="4"/>
                    </svg>
                    Hồ sơ
                </a>
                <a class="sb-link ${activeNav == 'my-tickets' ? 'active' : ''}" href="${ctx}/console?module=my-tickets">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16v4H4z"/><path d="M4 8v12h16V8"/></svg>
                    Vé của tôi
                </a>
            </div>
        </c:otherwise>
    </c:choose>

    <div class="sb-footer">
        <c:if test="${not empty requestScope.branchScopeName}">
            <div style="margin-bottom:8px">
                <span class="badge">Chi nhánh</span>
                <div style="color:#fff;margin-top:4px;font-weight:700"><c:out value="${requestScope.branchScopeName}"/></div>
            </div>
        </c:if>
        <div>Đăng nhập: <strong style="color:#fff"><c:out value="${sessionScope.username}"/></strong></div>
        <div style="margin-top:2px;color:#8b8b96"><c:out value="${role}"/></div>
    </div>
</aside>
<div class="sidebar-backdrop" id="sbBackdrop" aria-hidden="true"></div>
