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
<c:set var="wsTab" value="${not empty param.tab ? param.tab : (empty requestScope.activeTab ? 'product' : requestScope.activeTab)}"/>

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
                    <a class="ws-menu-link ${wsActive == 'genres' ? 'active' : ''}" href="${wsCtx}/console?module=genres">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.59 13.41 11 3H4v7l9.59 9.59a2 2 0 0 0 2.82 0l4.18-4.18a2 2 0 0 0 0-2.82Z"/><circle cx="7.5" cy="7.5" r=".5"/></svg>
                        Thể loại phim
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
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-refunds' ? 'active' : ''}" href="${wsCtx}/console?module=concession-refunds">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/><path d="M12 7v5l3 2"/></svg>
                        Hoàn tiền F&amp;B
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Kinh doanh</div>
                    <a class="ws-menu-link ${wsActive == 'pricing-templates' ? 'active' : ''}"
                       href="${wsCtx}/console?module=pricing-templates">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 8h10M7 12h6M7 16h4"/>
                        </svg>
                        Mẫu giá vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'voucher' ? 'active' : ''}"
                       href="${wsCtx}/console?module=voucher">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z"/>
                            <path d="M12 6v12" stroke-dasharray="2 2"/>
                        </svg>
                        Voucher
                    </a>
                    <a class="ws-menu-link ${wsActive == 'report' ? 'active' : ''}" href="${wsCtx}/console?module=report">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        Báo cáo
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Phản hồi khách hàng</div>
                    <a class="ws-menu-link ${wsActive == 'review-branch' ? 'active' : ''}" href="${wsCtx}/console?module=review-branch">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
                        Đánh giá phim theo rạp
                    </a>
                </div>
            </c:when>

            <%-- ============================================================
                 BRANCH_MANAGER
                 ============================================================ --%>
            <c:when test="${wsRole == 'BRANCH_MANAGER'}">
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý chi nhánh</div>
                    <a class="ws-menu-link ${wsActive == 'branch-staff' ? 'active' : ''}" href="${wsCtx}/console?module=branch-staff">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                        Nhân viên chi nhánh
                    </a>
                    <a class="ws-menu-link ${wsActive == 'movie' ? 'active' : ''}" href="${wsCtx}/console?module=movie">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/></svg>
                        Phim
                    </a>
                    <a class="ws-menu-link ${wsActive == 'screen' ? 'active' : ''}" href="${wsCtx}/console?module=screen">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/></svg>
                        Phòng và ghế
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Vận hành</div>
                    <a class="ws-menu-link ${wsActive == 'showtime' ? 'active' : ''}" href="${wsCtx}/console?module=showtime">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                        Suất chiếu 
                    </a>
                    <a class="ws-menu-link ${wsActive == 'booking' ? 'active' : ''}" href="${wsCtx}/console?module=booking">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 9a3 3 0 0 1 3-3h14a3 3 0 0 1 3 3v2a3 3 0 0 0 0 6v2a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3v-2a3 3 0 0 0 0-6z"/></svg>
                        Đặt vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'refunds' ? 'active' : ''}" href="${wsCtx}/console?module=refunds">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7"/><polyline points="3 4 3 10 9 10"/><path d="M12 7v5l3 2"/></svg>
                        Hoàn tiền
                    </a>
                    <a class="ws-menu-link ${wsActive == 'ticket' ? 'active' : ''}" href="${wsCtx}/console?module=ticket">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16v4H4z"/><path d="M4 8v12h16V8"/></svg>
                        Vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'product' ? 'active' : ''}" href="${wsCtx}/console?module=product">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Đồ ăn và thức uống
                    </a>
                    <div class="ws-menu-accordion ${wsActive == 'inventory' ? 'open' : ''}">
                        <button type="button" 
                                class="ws-menu-accordion-toggle ${wsActive == 'inventory' ? 'active' : ''}" 
                                aria-expanded="${wsActive == 'inventory' ? 'true' : 'false'}"
                                onclick="toggleWsMenuAccordion(this, event)">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                            </svg>
                            <span class="ws-menu-title">Kho hàng</span>
                            <svg class="ws-accordion-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <polyline points="9 18 15 12 9 6"></polyline>
                            </svg>
                        </button>
                        <div class="ws-menu-sub" role="region" aria-label="Menu con Kho hàng">
                            <a class="ws-menu-sub-link ${wsActive == 'inventory' and (wsTab == 'product' or empty param.tab) ? 'active' : ''}" 
                               href="${wsCtx}/console?module=inventory&tab=product"
                               data-tab="product">
                                Kho thành phẩm
                            </a>
                            <a class="ws-menu-sub-link ${wsActive == 'inventory' and wsTab == 'material' ? 'active' : ''}" 
                               href="${wsCtx}/console?module=inventory&tab=material"
                               data-tab="material">
                                Kho nguyên liệu
                            </a>
                            <a class="ws-menu-sub-link ${wsActive == 'inventory' and wsTab == 'recipe' ? 'active' : ''}" 
                               href="${wsCtx}/console?module=inventory&tab=recipe"
                               data-tab="recipe">
                                Công thức &amp; Sản xuất
                            </a>
                        </div>
                    </div>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý F&B</div>
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-refunds' ? 'active' : ''}" href="${wsCtx}/console?module=concession-refunds">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/><path d="M12 7v5l3 2"/></svg>
                        Hoàn tiền F&amp;B
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Kinh doanh</div>
                    <a class="ws-menu-link ${wsActive == 'pricing-templates' ? 'active' : ''}"
                       href="${wsCtx}/console?module=pricing-templates">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 8h10M7 12h6M7 16h4"/>
                        </svg>
                        Mẫu giá vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'voucher' ? 'active' : ''}"
                       href="${wsCtx}/console?module=voucher">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z"/>
                            <path d="M12 6v12" stroke-dasharray="2 2"/>
                        </svg>
                        Voucher
                    </a>
                    <a class="ws-menu-link ${wsActive == 'report' ? 'active' : ''}" href="${wsCtx}/console?module=report">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        Báo cáo
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Khiếu nại khách hàng</div>
                    <a class="ws-menu-link ${wsActive == 'complaints' ? 'active' : ''}" href="${wsCtx}/console?module=complaints">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                        Khiếu nại khách hàng
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
                        Bán vé tại quầy
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
                    <div class="ws-menu-group-label">Vé</div>
                    <a class="ws-menu-link ${wsActive == 'validate' ? 'active' : ''}" href="${wsCtx}/console?module=validate">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                        Kiểm tra vé
                    </a>
                    <a class="ws-menu-link ${wsActive == 'ticket' ? 'active' : ''}" href="${wsCtx}/console?module=ticket">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4h16v4H4z"/><path d="M4 8v12h16V8"/></svg>
                        Lịch sử vé
                    </a>
                    <div class="ws-menu-accordion ${wsActive == 'inventory' ? 'open' : ''}">
                        <button type="button" 
                                class="ws-menu-accordion-toggle ${wsActive == 'inventory' ? 'active' : ''}" 
                                aria-expanded="${wsActive == 'inventory' ? 'true' : 'false'}"
                                onclick="toggleWsMenuAccordion(this, event)">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
                            </svg>
                            <span class="ws-menu-title">Kho hàng</span>
                            <svg class="ws-accordion-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <polyline points="9 18 15 12 9 6"></polyline>
                            </svg>
                        </button>
                        <div class="ws-menu-sub" role="region" aria-label="Menu con Kho hàng">
                            <a class="ws-menu-sub-link ${wsActive == 'inventory' and (wsTab == 'product' or empty param.tab) ? 'active' : ''}" 
                               href="${wsCtx}/console?module=inventory&tab=product"
                               data-tab="product">
                                Kho thành phẩm
                            </a>
                            <a class="ws-menu-sub-link ${wsActive == 'inventory' and wsTab == 'material' ? 'active' : ''}" 
                               href="${wsCtx}/console?module=inventory&tab=material"
                               data-tab="material">
                                Kho nguyên liệu
                            </a>
                        </div>
                    </div>
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
                    <div class="ws-menu-group-label">Hỗ trợ</div>
                    <a class="ws-menu-link ${wsActive == 'support' ? 'active' : ''}" href="${wsCtx}/console?module=support">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>
                        Yêu cầu hỗ trợ
                    </a>
                    <a class="ws-menu-link ${wsActive == 'complaints' ? 'active' : ''}" href="${wsCtx}/console?module=complaints">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
                        Khiếu nại
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
        <%-- Thông báo và Đăng xuất — luôn hiển thị ở footer cho ADMIN và BRANCH_MANAGER --%>
        <c:if test="${wsRole == 'ADMIN' || wsRole == 'BRANCH_MANAGER'}">
            <div style="margin-top:16px;padding-top:12px;border-top:1px solid var(--ws-line);display:flex;flex-direction:column;gap:4px">
                <a class="ws-menu-link" href="${wsCtx}/console?module=notification" style="padding:8px 0">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;flex-shrink:0"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
                    Thông báo
                </a>
                <a class="ws-menu-link" href="${wsCtx}/logout" style="padding:8px 0">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px;flex-shrink:0"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
                    Đăng xuất
                </a>
            </div>
        </c:if>
    </div>
</aside>
<div class="ws-backdrop" id="wsBackdrop"></div>

<script>
    function toggleWsMenuAccordion(btn, evt) {
        if (evt) {
            evt.preventDefault();
            evt.stopPropagation();
        }
        var accordion = btn.closest('.ws-menu-accordion');
        if (!accordion) return;
        var willOpen = !accordion.classList.contains('open');
        accordion.classList.toggle('open', willOpen);
        btn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    }

    // Khi người dùng bấm vào một menu con của Kho hàng:
    // Nếu đang ở sẵn trong module=inventory, gọi router để chuyển view mượt mà
    document.addEventListener('click', function (e) {
        var subLink = e.target.closest('.ws-menu-sub-link');
        if (!subLink) return;
        var tab = subLink.getAttribute('data-tab');
        var curMod = new URLSearchParams(window.location.search).get('module');
        if (curMod === 'inventory' && tab && typeof window.CinemaHubSwitchInventoryTab === 'function') {
            e.preventDefault();
            e.stopPropagation();
            window.CinemaHubSwitchInventoryTab(tab);
        }
    });
</script>
