<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%--
  Thanh bên của khu vực quản lý.
  Phong cách: trắng, accent blue/indigo, group labels uppercase.
  Role-based: ẩn/hiện item theo sessionScope.role.
  Active state: ?module= hoặc requestScope.activeNav.
  Width: 256px (sidebar fixed), responsive thu về drawer ở mobile.
--%>
<c:set var="wsRole" value="${empty sessionScope.role ? 'GUEST' : sessionScope.role}"/>
<c:set var="wsName" value="${empty sessionScope.username ? '' : sessionScope.username}"/>
<c:set var="wsInitial" value="${fn:length(wsName) == 0 ? '?' : fn:toUpperCase(fn:substring(wsName, 0, 1))}"/>
<c:set var="wsActive" value="${not empty param.module ? param.module : (empty requestScope.activeNav ? '' : requestScope.activeNav)}"/>
<c:set var="wsCtx" value="${pageContext.request.contextPath}"/>
<c:set var="wsBranch" value="${not empty sessionScope.branchName ? sessionScope.branchName : requestScope.branchScopeName}"/>

<aside class="ws-sidebar" id="wsSidebar" aria-label="Sidebar quản lý CinemaHub">
    <div class="ws-sidebar-brand">
        <span class="logo-mark" aria-hidden="true">C</span>
        <span class="logo-text">
            <strong>CinemaHub</strong>
            <small>Quản lý</small>
        </span>
    </div>

    <nav class="ws-sidebar-menu" aria-label="Menu chính">
        <%-- Bảng điều khiển luôn có ở mọi vai trò --%>
        <div class="ws-menu-group">
            <a class="ws-menu-link ${wsActive == 'dashboard' ? 'active' : ''}"
               href="${wsCtx}/console?module=dashboard">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                    <rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/>
                    <rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/>
                </svg>
                Bảng điều khiển
            </a>
        </div>

        <c:choose>
            <%-- ============================================================
                 ADMIN
                 ============================================================ --%>
            <c:when test="${wsRole == 'ADMIN'}">
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý hệ thống</div>
                    <a class="ws-menu-link ${wsActive == 'users' ? 'active' : ''}" href="${wsCtx}/console?module=users">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                        Người dùng
                    </a>
                    <a class="ws-menu-link ${wsActive == 'branch' ? 'active' : ''}" href="${wsCtx}/console?module=branch">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
                        Chi nhánh
                    </a>
                    <a class="ws-menu-link ${wsActive == 'movie' ? 'active' : ''}" href="${wsCtx}/console?module=movie">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/></svg>
                        Phim
                    </a>
                    <a class="ws-menu-link ${wsActive == 'screen' ? 'active' : ''}" href="${wsCtx}/console?module=screen">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/></svg>
                        Phòng chiếu
                    </a>
                    <a class="ws-menu-link ${wsActive == 'showtime' ? 'active' : ''}" href="${wsCtx}/console?module=showtime">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                        Suất chiếu
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Vận hành</div>
                    <a class="ws-menu-link ${wsActive == 'booking' ? 'active' : ''}" href="${wsCtx}/console?module=booking">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 9a3 3 0 0 1 3-3h14a3 3 0 0 1 3 3v2a3 3 0 0 0 0 6v2a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3v-2a3 3 0 0 0 0-6z"/><line x1="13" y1="6" x2="13" y2="12"/></svg>
                        Đặt vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'ticket' ? 'active' : ''}" href="${wsCtx}/console?module=ticket">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16v4H4z"/><path d="M4 8v12h16V8"/></svg>
                        Vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'product' ? 'active' : ''}" href="${wsCtx}/console?module=product">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Đồ ăn và thức uống
                    </a>
                    <a class="ws-menu-link ${wsActive == 'inventory' ? 'active' : ''}" href="${wsCtx}/console?module=inventory">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
                        Kho hàng
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Kinh doanh</div>
                    <a class="ws-menu-link ${wsActive == 'pricing' ? 'active' : ''}" href="${wsCtx}/console?module=pricing">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                        Bảng giá
                    </a>
                    <a class="ws-menu-link ${wsActive == 'wallet' ? 'active' : ''}" href="${wsCtx}/console?module=wallet">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12V8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v4"/><path d="M2 12h20v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z"/><circle cx="17" cy="15" r="1.5"/></svg>
                        Giao dịch ví
                    </a>
                    <a class="ws-menu-link ${wsActive == 'report' ? 'active' : ''}" href="${wsCtx}/console?module=report">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        Báo cáo
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Hệ thống</div>
                    <%--
                      BỎ "Thông báo" + "Hồ sơ" khỏi shell ADMIN.
                      Workspace admin đã có các trang quản trị riêng, không cần
                      2 menu này (giống yêu cầu: xóa xử lý notification + profile
                      ở admin).
                    --%>
                    <a class="ws-menu-link" href="${wsCtx}/logout">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                        Đăng xuất
                    </a>
                </div>
            </c:when>

            <%-- ============================================================
                 BRANCH_MANAGER
                 ============================================================ --%>
            <c:when test="${wsRole == 'BRANCH_MANAGER'}">
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý chi nhánh</div>
                    <a class="ws-menu-link ${wsActive == 'branch' ? 'active' : ''}" href="${wsCtx}/console?module=branch">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>
                        Tổng quan chi nhánh
                    </a>
                    <a class="ws-menu-link ${wsActive == 'movie' ? 'active' : ''}" href="${wsCtx}/console?module=movie">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/></svg>
                        Phim
                    </a>
                    <a class="ws-menu-link ${wsActive == 'screen' ? 'active' : ''}" href="${wsCtx}/console?module=screen">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/></svg>
                        Phòng và ghế
                    </a>
                    <a class="ws-menu-link ${wsActive == 'showtime' ? 'active' : ''}" href="${wsCtx}/console?module=showtime">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                        Suất chiếu
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Vận hành</div>
                    <a class="ws-menu-link ${wsActive == 'booking' ? 'active' : ''}" href="${wsCtx}/console?module=booking">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 9a3 3 0 0 1 3-3h14a3 3 0 0 1 3 3v2a3 3 0 0 0 0 6v2a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3v-2a3 3 0 0 0 0-6z"/></svg>
                        Đặt vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'ticket' ? 'active' : ''}" href="${wsCtx}/console?module=ticket">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16v4H4z"/><path d="M4 8v12h16V8"/></svg>
                        Vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'product' ? 'active' : ''}" href="${wsCtx}/console?module=product">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Đồ ăn và thức uống
                    </a>
                    <a class="ws-menu-link ${wsActive == 'inventory' ? 'active' : ''}" href="${wsCtx}/console?module=inventory">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
                        Kho hàng
                    </a>
                    <a class="ws-menu-link ${wsActive == 'shift' ? 'active' : ''}" href="${wsCtx}/console?module=shift">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                        Ca làm việc của nhân viên
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý F&B</div>
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-pickup' ? 'active' : ''}" href="${wsCtx}/console?module=concession-pickup">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 7h3v3H7z"/><path d="M14 7h3v3h-3z"/><path d="M7 14h3v3H7z"/><path d="M14 14h3v3h-3z"/></svg>
                        Quét mã F&amp;B
                    </a>
                    <a class="ws-menu-link ${wsActive == 'customer-points' ? 'active' : ''}" href="${wsCtx}/console?module=customer-points">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                        Điều chỉnh điểm
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Kinh doanh</div>
                    <a class="ws-menu-link ${wsActive == 'report' ? 'active' : ''}" href="${wsCtx}/console?module=report">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        Báo cáo
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Hệ thống</div>
                    <a class="ws-menu-link ${wsActive == 'notification' ? 'active' : ''}" href="${wsCtx}/console?module=notification">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
                        Thông báo
                    </a>
                    <a class="ws-menu-link ${wsActive == 'profile' ? 'active' : ''}" href="${wsCtx}/console?module=profile">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="7" r="4"/></svg>
                        Hồ sơ
                    </a>
                    <a class="ws-menu-link" href="${wsCtx}/logout">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                        Đăng xuất
                    </a>
                </div>
            </c:when>

            <%-- ============================================================
                 BRANCH_STAFF
                 ============================================================ --%>
            <c:when test="${wsRole == 'BRANCH_STAFF'}">
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Bán hàng</div>
                    <a class="ws-menu-link ${wsActive == 'pos-ticket' ? 'active' : ''}" href="${wsCtx}/console?module=pos-ticket">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 12V8a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v4"/><path d="M2 12h20"/><path d="M4 12v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6"/></svg>
                        Bán vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'pos-concession' ? 'active' : ''}" href="${wsCtx}/console?module=pos-concession">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Đồ ăn và thức uống
                    </a>
                    <a class="ws-menu-link ${wsActive == 'pickup' ? 'active' : ''}" href="${wsCtx}/console?module=pickup">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
                        Nhận đơn trực tuyến
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý F&B</div>
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-pickup' ? 'active' : ''}" href="${wsCtx}/console?module=concession-pickup">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 7h3v3H7z"/><path d="M14 7h3v3h-3z"/><path d="M7 14h3v3H7z"/><path d="M14 14h3v3h-3z"/></svg>
                        Quét mã F&amp;B
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Khách hàng</div>
                    <a class="ws-menu-link ${wsActive == 'customer-points' ? 'active' : ''}" href="${wsCtx}/console?module=customer-points">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                        Điều chỉnh điểm
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Vé</div>
                    <a class="ws-menu-link ${wsActive == 'validate' ? 'active' : ''}" href="${wsCtx}/console?module=validate">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                        Kiểm tra vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'ticket' ? 'active' : ''}" href="${wsCtx}/console?module=ticket">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16v4H4z"/><path d="M4 8v12h16V8"/></svg>
                        Lịch sử vé
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Ca làm việc</div>
                    <a class="ws-menu-link ${wsActive == 'shift' ? 'active' : ''}" href="${wsCtx}/console?module=shift">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                        Ca hiện tại
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Hệ thống</div>
                    <a class="ws-menu-link ${wsActive == 'notification' ? 'active' : ''}" href="${wsCtx}/console?module=notification">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
                        Thông báo
                    </a>
                    <a class="ws-menu-link ${wsActive == 'profile' ? 'active' : ''}" href="${wsCtx}/console?module=profile">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="7" r="4"/></svg>
                        Hồ sơ
                    </a>
                    <a class="ws-menu-link" href="${wsCtx}/logout">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                        Đăng xuất
                    </a>
                </div>
            </c:when>

            <%-- ============================================================
                 CUSTOMER (không nên thấy nhưng fallback)
                 ============================================================ --%>
            <c:otherwise>
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Bắp & Nước</div>
                    <a class="ws-menu-link ${wsActive == 'fnb-catalog' ? 'active' : ''}" href="${wsCtx}/console?module=fnb-catalog">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Thực đơn F&amp;B
                    </a>
                    <a class="ws-menu-link ${wsActive == 'fnb-cart' ? 'active' : ''}" href="${wsCtx}/console?module=fnb-cart">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Giỏ hàng
                        <span id="fnb-cart-badge" class="ws-badge ws-badge-red" hidden>0</span>
                    </a>
                    <a class="ws-menu-link ${wsActive == 'fnb-orders' ? 'active' : ''}" href="${wsCtx}/console?module=fnb-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B của tôi
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Tài khoản</div>
                    <a class="ws-menu-link ${wsActive == 'profile' ? 'active' : ''}" href="${wsCtx}/console?module=profile">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="7" r="4"/></svg>
                        Hồ sơ
                    </a>
                    <a class="ws-menu-link" href="${wsCtx}/logout">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/></svg>
                        Đăng xuất
                    </a>
                </div>
            </c:otherwise>
        </c:choose>
    </nav>

    <div class="ws-sidebar-footer">
        <div class="ws-user-card">
            <span class="ws-user-avatar" aria-hidden="true">${wsInitial}</span>
            <div class="ws-user-info">
                <strong><c:out value="${fn:length(wsName) == 0 ? 'Người dùng' : wsName}"/></strong>
                <small>${wsRole}</small>
            </div>
        </div>
        <c:if test="${not empty wsBranch}">
            <div style="margin-top:10px;font-size:0.72rem;color:var(--ws-text-muted)">
                🏢 <strong>${wsBranch}</strong>
            </div>
        </c:if>
    </div>
</aside>
<div class="ws-backdrop" id="wsBackdrop"></div>
