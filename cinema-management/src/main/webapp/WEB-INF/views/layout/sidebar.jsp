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
        <a class="sb-link ${activeNav == 'dashboard' ? 'active' : ''}" href="${ctx}/console?module=dashboard">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
            Dashboard
        </a>
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
                <a class="sb-link ${activeNav == 'pricing' ? 'active' : ''}" href="${ctx}/console?module=pricing">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                    Khung giá
                </a>
                <a class="sb-link ${activeNav == 'product' ? 'active' : ''}" href="${ctx}/console?module=product">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                    Bắp nước
                </a>
            </div>
            <div class="sb-section">
                <div class="sb-title">Phân tích</div>
                <a class="sb-link ${activeNav == 'report' ? 'active' : ''}" href="${ctx}/console?module=report">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                    Báo cáo toàn chuỗi
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
                <a class="sb-link ${activeNav == 'inventory' ? 'active' : ''}" href="${ctx}/console?module=inventory">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
                    Tồn kho
                </a>
            </div>
            <div class="sb-section">
                <div class="sb-title">Vận hành</div>
                <a class="sb-link ${activeNav == 'shift' ? 'active' : ''}" href="${ctx}/console?module=shift">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                    Đối soát ca
                </a>
                <a class="sb-link ${activeNav == 'report' ? 'active' : ''}" href="${ctx}/console?module=report">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                    Báo cáo chi nhánh
                </a>
            </div>
        </c:when>
        <c:when test="${role == 'BRANCH_STAFF'}">
            <div class="sb-section">
                <div class="sb-title">Bán hàng</div>
                <a class="sb-link ${activeNav == 'pos-ticket' ? 'active' : ''}" href="${ctx}/console?module=pos-ticket">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12V8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v4"/><path d="M2 12h20"/><path d="M4 12v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6"/></svg>
                    Bán vé tại quầy
                </a>
                <a class="sb-link ${activeNav == 'validate' ? 'active' : ''}" href="${ctx}/console?module=validate">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                    Soát vé
                </a>
                <a class="sb-link ${activeNav == 'pos-concession' ? 'active' : ''}" href="${ctx}/console?module=pos-concession">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                    POS bắp nước
                </a>
                <a class="sb-link ${activeNav == 'pickup' ? 'active' : ''}" href="${ctx}/console?module=pickup">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
                    Nhận đơn online
                </a>
            </div>
            <div class="sb-section">
                <div class="sb-title">Ca làm việc</div>
                <a class="sb-link ${activeNav == 'shift' ? 'active' : ''}" href="${ctx}/console?module=shift">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                    Mở / đóng ca
                </a>
            </div>
        </c:when>
        <c:otherwise>
            <div class="sb-section">
                <div class="sb-title">Bắp &amp; Nước</div>
                <a class="sb-link ${activeNav == 'fnb-catalog' ? 'active' : ''}" href="${ctx}/console?module=fnb-catalog">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                    Thực đơn F&amp;B
                </a>
                <a class="sb-link ${activeNav == 'fnb-orders' ? 'active' : ''}" href="${ctx}/console?module=fnb-orders">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/></svg>
                    Đơn F&amp;B của tôi
                </a>
            </div>
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
