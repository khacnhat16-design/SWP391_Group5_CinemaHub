/**
 * page-router.js — Auto-dispatch ?module=X&action=Y&id=Z tới renderer phù hợp.
 *
 * Convention URL:
 *   /console?module=users             → render List (pageUsers)
 *   /console?module=users&action=create → render Create form
 *   /console?module=users&action=edit&id=123 → render Edit form (load data by id)
 *   /console?module=users&action=assign&id=123 → assign branches to a staff user
 *
 * Mỗi module khai báo renderer qua window.CinemaHubFormPages[name] = {
 *     list: function() { ... },          // list page (render trực tiếp vào viewRoot)
 *     create: function() { ... },        // create form page
 *     edit: function(id) { ... },        // edit form page (load data trước khi render)
 *     assign: function(id) { ... }       // optional assignment page
 * }
 *
 * Module nào không có handler → fallback CinemaHubPages[name]() (giữ backward compat).
 */
(function () {
    'use strict';

    const hub = window.CinemaHub;
    if (!hub) return;

    const el = hub.el;

    function getParams() {
        return new URLSearchParams(window.location.search);
    }

    function currentRoute() {
        const p = getParams();
        return {
            module: p.get('module') || 'dashboard',
            action: p.get('action') || 'list',
            id: p.get('id') || null
        };
    }

    function buildUrl(module, action, id) {
        const params = new URLSearchParams();
        params.set('module', module);
        if (action && action !== 'list') params.set('action', action);
        if (id) params.set('id', id);
        return window.location.pathname + '?' + params.toString();
    }

    function go(module, action, id) {
        window.location.href = buildUrl(module, action, id);
    }

    function replace(module, action, id) {
        window.history.replaceState({}, '', buildUrl(module, action, id));
    }

    function renderNotConfigured(module, action) {
        const viewRoot = document.getElementById('viewRoot');
        if (!viewRoot) return;
        viewRoot.replaceChildren(
            el('div', { class: 'ws-empty' }, [
                el('h3', {}, [`Trang ${action} chưa được cấu hình cho module "${module}"`]),
                el('p', {}, ['Liên hệ admin hoặc quay lại danh sách.']),
                el('a', {
                    href: buildUrl(module, 'list'),
                    class: 'ws-btn primary'
                }, ['← Quay lại danh sách'])
            ])
        );
    }

    /**
     * Đăng ký form-pages cho 1 module.
     * pages = { list?, create?, edit? }
     */
    function register(module, pages) {
        window.CinemaHubFormPages = window.CinemaHubFormPages || {};
        window.CinemaHubFormPages[module] = pages;
    }

    /**
     * Auto-route: parse URL và dispatch.
     * Nếu form-page không đăng ký handler → fallback CinemaHubPages[name]().
     */
    function autoRoute() {
        const route = currentRoute();
        const viewRoot = document.getElementById('viewRoot');
        if (!viewRoot) return;

        const formPages = window.CinemaHubFormPages || {};
        const pages = formPages[route.module];

        // Trang dashboard và các module đặc biệt không có form-page → console.js dispatch
        const noFormPage = ['dashboard', 'discovery', 'seat',
            'ticket', 'my-tickets',
            // Reviews use the console.js module registry.
            'reviews',
            // Profile — route tới console.js moduleProfile() để hỗ trợ mọi role
            // (form-pages profile chỉ dành cho CUSTOMER, các role khác sẽ bị 403
            // từ ProfileController.requireCustomer).
            'profile'];

        if (!pages || noFormPage.includes(route.module)) {
            // fallback: console.js đã tự dispatch qua route(currentModule)
            return false;
        }

        // LIST
        if (route.action === 'list') {
            if (typeof pages.list === 'function') {
                pages.list();
                return true;
            }
            renderNotConfigured(route.module, 'list');
            return true;
        }

        // CREATE
        if (route.action === 'create') {
            if (typeof pages.create === 'function') {
                pages.create();
                return true;
            }
            renderNotConfigured(route.module, 'create');
            return true;
        }

        // EDIT
        if (route.action === 'edit') {
            if (!route.id) {
                renderNotConfigured(route.module, 'edit (thiếu id)');
                return true;
            }
            if (typeof pages.edit === 'function') {
                pages.edit(route.id);
                return true;
            }
            renderNotConfigured(route.module, 'edit');
            return true;
        }

        // ASSIGN (branch assignments for an existing user)
        if (route.action === 'assign') {
            if (!route.id) {
                renderNotConfigured(route.module, 'assign (thiếu id)');
                return true;
            }
            if (typeof pages.assign === 'function') {
                pages.assign(route.id);
                return true;
            }
            renderNotConfigured(route.module, 'assign');
            return true;
        }

        // VIEW (read-only)
        if (route.action === 'view') {
            if (typeof pages.view === 'function') {
                pages.view(route.id);
                return true;
            }
            renderNotConfigured(route.module, 'view');
            return true;
        }

        renderNotConfigured(route.module, route.action);
        return true;
    }

    window.CinemaHubRouter = {
        getParams,
        currentRoute,
        buildUrl,
        go,
        replace,
        register,
        autoRoute
    };
})();
