/**
 * inventory-main.js — Bộ điều hướng Router (3 Màn hình Kho hàng CinemaHub).
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var router = window.CinemaHubRouter;
    var inv = window.CinemaHubInventory;
    if (!hub || !router || !inv) return;

    var notifyError = inv.notifyError;
    var branchListApiPath = inv.branchListApiPath;
    var renderProductInventoryPage = inv.renderProductInventoryPage;
    var renderMaterialInventoryPage = inv.renderMaterialInventoryPage;
    var renderRecipeProductionPage = inv.renderRecipeProductionPage || function () {};
    var showProductRestockModal = inv.showProductRestockModal;
    var showProductAdjustModal = inv.showProductAdjustModal;

    router.register('inventory', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            var isAdmin = roleMeta === 'ADMIN';
            var isStaff = roleMeta === 'STAFF' || roleMeta === 'BRANCH_STAFF';
            var viewRoot = document.getElementById('viewRoot');
            if (!viewRoot) return;

            if (isAdmin) {
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty', style: 'padding: 48px 24px; text-align: center;' }, [
                    hub.el('h3', {}, ['Không có quyền truy cập']),
                    hub.el('p', { class: 'muted', style: 'margin: 8px 0 16px;' }, ['Admin hệ thống không quản lý trực tiếp kho hàng chi nhánh. Kho hàng do Quản lý và Nhân viên chi nhánh vận hành.'])
                ]));
                return;
            }

            viewRoot.replaceChildren(hub.el('div', { class: 'ws-list-loading', style: 'padding: 48px 24px; text-align: center;' }, [
                hub.el('div', { class: 'ws-spinner' }),
                hub.el('span', { style: 'display: block; margin-top: 12px; color: #64748b; font-weight: 500;' }, ['Đang tải dữ liệu kho hàng…'])
            ]));

            hub.api(branchListApiPath(roleMeta)).then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var rawTab = params.get('tab');
                var activeTab = rawTab === 'material' ? 'material' : 'product';
                var branchId = params.get('branchId') || (branches && branches[0] ? branches[0].id : null);

                function syncSidebarSubmenu(tab) {
                    var acc = document.querySelector('.ws-menu-accordion');
                    if (acc) {
                        acc.classList.add('open');
                        var toggle = acc.querySelector('.ws-menu-accordion-toggle');
                        if (toggle) {
                            toggle.classList.add('active');
                            toggle.setAttribute('aria-expanded', 'true');
                        }
                    }
                    document.querySelectorAll('.ws-menu-sub-link').forEach(function (sub) {
                        if (sub.getAttribute('data-tab') === tab) {
                            sub.classList.add('active');
                        } else {
                            sub.classList.remove('active');
                        }
                    });
                }

                function switchTab(newTab) {
                    var safeTab = (newTab === 'material') ? 'material' : 'product';
                    activeTab = safeTab;
                    var newUrl = window.location.pathname + '?module=inventory&tab=' + activeTab + (branchId ? '&branchId=' + branchId : '');
                    window.history.replaceState(null, '', newUrl);
                    syncSidebarSubmenu(activeTab);
                    if (activeTab === 'material') {
                        renderMaterialInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
                    } else if (activeTab === 'recipe') {
                        renderRecipeProductionPage(viewRoot, branchId, branches, isStaff, switchTab);
                    } else {
                        renderProductInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
                    }
                }

                window.CinemaHubSwitchInventoryTab = switchTab;
                syncSidebarSubmenu(activeTab);

                if (activeTab === 'material') {
                    renderMaterialInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
                } else if (activeTab === 'recipe' && typeof renderRecipeProductionPage === 'function') {
                    renderRecipeProductionPage(viewRoot, branchId, branches, isStaff, switchTab);
                } else {
                    renderProductInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
                }
            }).catch(function (err) {
                notifyError(err);
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty', style: 'padding: 48px 24px; text-align: center;' }, [
                    hub.el('h3', {}, ['Không thể tải dữ liệu kho hàng']),
                    hub.el('p', { class: 'muted', style: 'margin: 8px 0 16px;' }, [(err && err.message) || 'Vui lòng kiểm tra lại kết nối mạng hoặc thử tải lại.']),
                    hub.el('button', {
                        class: 'ws-btn primary',
                        type: 'button',
                        onClick: function () {
                            if (window.CinemaHubRouter) window.CinemaHubRouter.autoRoute();
                        }
                    }, ['Tải lại trang'])
                ]));
            });
        },
        create: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta === 'ADMIN' || roleMeta === 'STAFF' || roleMeta === 'BRANCH_STAFF') {
                hub.notify('Chỉ Quản lý chi nhánh mới có quyền nhập kho.', 'warn');
                return;
            }
            var params = new URLSearchParams(window.location.search);
            var branchId = params.get('branchId');
            Promise.all([
                hub.api(branchListApiPath(roleMeta)).catch(function () { return []; }),
                branchId ? hub.api('/inventory?branchId=' + branchId).catch(function () { return []; }) : Promise.resolve([])
            ]).then(function (results) {
                var branches = results[0] || [];
                var activeBranch = branchId || (branches[0] && branches[0].id);
                var items = results[1] || [];
                showProductRestockModal(activeBranch, items, function () {
                    var branchSelect = document.querySelector('select[name="branchId"]');
                    if (branchSelect) branchSelect.dispatchEvent(new Event('change'));
                });
            }).catch(notifyError);
        },
        edit: function (id) {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta === 'ADMIN' || roleMeta === 'STAFF' || roleMeta === 'BRANCH_STAFF') {
                hub.notify('Chỉ Quản lý chi nhánh mới có quyền điều chỉnh kho.', 'warn');
                return;
            }
            var params = new URLSearchParams(window.location.search);
            var branchId = params.get('branchId');
            var productId = params.get('productId') || id;
            Promise.all([
                hub.api(branchListApiPath(roleMeta)).catch(function () { return []; }),
                branchId ? hub.api('/inventory?branchId=' + branchId).catch(function () { return []; }) : Promise.resolve([])
            ]).then(function (results) {
                var branches = results[0] || [];
                var activeBranch = branchId || (branches[0] && branches[0].id);
                var items = results[1] || [];
                var selected = items.find(function (it) { return String(it.productId || it.id) === String(productId); });
                showProductAdjustModal(activeBranch, selected || { productId: productId }, items, function () {
                    var branchSelect = document.querySelector('select[name="branchId"]');
                    if (branchSelect) branchSelect.dispatchEvent(new Event('change'));
                });
            }).catch(notifyError);
        }
    });

})();
