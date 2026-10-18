/**
 * workspace.js — chỉ render layout chrome (sidebar toggle, user dropdown, bell,
 *                notification polling cho header). Không render nội dung dashboard.
 * Pham vi: khu vuc quan tri (workspace-page body).
 */
(function () {
    'use strict';

    if (!document.body.classList.contains('workspace-page')) return;

    const ctx = (document.querySelector('meta[name="ctx"]') || {}).content || '';

    // ---- Sidebar toggle (mobile) ----
    const sidebar = document.getElementById('wsSidebar');
    const toggle = document.getElementById('wsSidebarToggle');
    const backdrop = document.getElementById('wsBackdrop');

    function closeSidebar() {
        if (!sidebar) return;
        sidebar.classList.remove('open');
        if (backdrop) backdrop.classList.remove('open');
    }
    if (toggle && sidebar) {
        toggle.addEventListener('click', function () {
            sidebar.classList.toggle('open');
            if (backdrop) backdrop.classList.toggle('open');
        });
    }
    if (backdrop) backdrop.addEventListener('click', closeSidebar);

    // ---- User dropdown ----
    const chip = document.getElementById('wsUserChip');
    const dropdown = document.getElementById('wsUserDropdown');
    function closeDropdown() {
        if (!dropdown) return;
        dropdown.classList.remove('open');
        if (chip) chip.setAttribute('aria-expanded', 'false');
    }
    if (chip && dropdown) {
        chip.addEventListener('click', function (e) {
            e.stopPropagation();
            const isOpen = dropdown.classList.toggle('open');
            chip.setAttribute('aria-expanded', String(isOpen));
        });
        document.addEventListener('click', function (e) {
            if (!dropdown.contains(e.target) && !chip.contains(e.target)) closeDropdown();
        });
    }

    // ---- Bell (notification count + popover) ----
    const bellBtn = document.getElementById('wsBellBtn');
    const bellPopover = document.getElementById('wsBellPopover');
    const bellList = document.getElementById('wsBellList');
    const bellBadge = document.getElementById('wsBellBadge');

    function closePopover() {
        if (!bellPopover) return;
        bellPopover.classList.remove('open');
    }

    function loadBell() {
        if (!bellList || !bellBadge || !ctx) return Promise.resolve();
        return fetch(ctx + '/api/notification/unread-count', {
            credentials: 'same-origin'
        }).then(function (r) { return r.json(); }).catch(function () { return null; }).then(function (data) {
            const count = (data && typeof data.count === 'number') ? data.count
                : (data && typeof data.unread === 'number' ? data.unread : null);
            if (typeof count === 'number' && count > 0) {
                bellBadge.textContent = String(count);
                bellBadge.classList.add('has-count');
            } else {
                bellBadge.classList.remove('has-count');
                bellBadge.textContent = '0';
            }
            // Render items
            const items = (data && Array.isArray(data.recent)) ? data.recent : [];
            if (items.length === 0) {
                bellList.innerHTML = '<div class="ws-popover-empty">Khong co thong bao moi.</div>';
            } else {
                bellList.innerHTML = items.map(function (it) {
                    const title = String(it.title || it.message || 'Thong bao');
                    const type = String(it.type || 'info');
                    const time = it.createdAt || it.time || '';
                    return '<div class="ws-popover-item">' +
                        '<div class="type">' + escapeHtml(type) + '</div>' +
                        '<div class="title">' + escapeHtml(title) + '</div>' +
                        '<div class="time">' + escapeHtml(time) + '</div>' +
                        '</div>';
                }).join('');
            }
        }).catch(function (e) {
            bellList.innerHTML = '<div class="ws-popover-empty">Khong tai duoc thong bao.</div>';
        });
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    if (bellBtn && bellPopover) {
        bellBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            const opened = bellPopover.classList.toggle('open');
            if (opened) loadBell();
        });
        document.addEventListener('click', function (e) {
            if (!bellPopover.contains(e.target) && !bellBtn.contains(e.target)) closePopover();
        });
    }

    // Initial load (badge only) — silent fail OK
    if (bellBadge && ctx) loadBell();
})();
