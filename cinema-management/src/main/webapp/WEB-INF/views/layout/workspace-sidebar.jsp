<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%--
  Thanh bên khu vực quản lý — Tối ưu cho phạm vi phụ trách:
  F&B (Bắp nước, Combo), Bán hàng tại quầy (POS F&B), Quản lý kho hàng & Báo cáo doanh thu chi nhánh.
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
                    <div class="ws-menu-group-label">Quản lý F&amp;B</div>
                    <a class="ws-menu-link ${wsActive == 'product' ? 'active' : ''}" href="${wsCtx}/console?module=product">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Đồ ăn và thức uống
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý kho</div>
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
                    <div class="ws-menu-group-label">Báo cáo</div>
                    <a class="ws-menu-link ${wsActive == 'report' ? 'active' : ''}" href="${wsCtx}/console?module=report">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        Báo cáo
                    </a>
                </div>
            </c:when>

            <%-- ============================================================
                 BRANCH_MANAGER
                 ============================================================ --%>
            <c:when test="${wsRole == 'BRANCH_MANAGER'}">
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý F&amp;B</div>
                    <a class="ws-menu-link ${wsActive == 'product' ? 'active' : ''}" href="${wsCtx}/console?module=product">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Đồ ăn và thức uống
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Quản lý kho</div>
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
                    <div class="ws-menu-group-label">Báo cáo</div>
                    <a class="ws-menu-link ${wsActive == 'report' ? 'active' : ''}" href="${wsCtx}/console?module=report">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
                        Báo cáo
                    </a>
                </div>
            </c:when>

            <%-- ============================================================
                 BRANCH_STAFF
                 ============================================================ --%>
            <c:when test="${wsRole == 'BRANCH_STAFF'}">
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Vận hành F&amp;B</div>
                    <a class="ws-menu-link ${wsActive == 'pos-concession' ? 'active' : ''}" href="${wsCtx}/console?module=pos-concession">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Bán bắp nước tại quầy
                    </a>
                    <a class="ws-menu-link ${wsActive == 'concession-orders' ? 'active' : ''}" href="${wsCtx}/console?module=concession-orders">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="16" x2="15" y2="16"/></svg>
                        Đơn F&amp;B
                    </a>
                </div>

                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Kho hàng</div>
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
                 CUSTOMER (fallback)
                 ============================================================ --%>
            <c:otherwise>
                <div class="ws-menu-group">
                    <div class="ws-menu-group-label">Bắp &amp; Nước</div>
                    <a class="ws-menu-link ${wsActive == 'fnb-catalog' ? 'active' : ''}" href="${wsCtx}/console?module=fnb-catalog">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72"/></svg>
                        Thực đơn F&amp;B
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
