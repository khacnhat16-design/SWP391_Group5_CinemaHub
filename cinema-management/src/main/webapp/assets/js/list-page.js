/**
 * list-page.js — Renderer chuẩn cho List page với:
 *   - Toolbar: search, filter, sort, add button
 *   - Status badges
 *   - Loading state
 *   - Empty state
 *   - Pagination
 *   - Row actions
 *
 * Sử dụng:
 *   CinemaHubList.render({
 *       title: 'Người dùng',
 *       subtitle: 'CRUD tài khoản nội bộ',
 *       fetcher: async (params) => ({ items: [...], total: N }),
 *       columns: [
 *           { label: 'ID', key: 'id' },
 *           { label: 'Email', key: 'email' },
 *           { label: 'Trạng thái', render: (r) => hub.statusBadge(r.status) }
 *       ],
 *       actions: (row) => [
 *           { label: 'Sửa', class: 'secondary', onClick: () => goEdit(row.id) },
 *           { label: 'Xóa', class: 'danger', onClick: () => doDelete(row.id) }
 *       ],
 *       filters: [
 *           { name: 'role', label: 'Role', options: [{value:'ADMIN', label:'Admin'}, ...] }
 *       ],
 *       searchable: true,
 *       pageSize: 20,
 *       addUrl: '/console?module=users&action=create',
 *       addLabel: 'Tạo người dùng'
 *   });
 */
(function () {
    'use strict';

    const hub = window.CinemaHub;
    if (!hub) return;

    const el = hub.el;

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    function debounce(fn, ms) {
        let t;
        return function () {
            clearTimeout(t);
            t = setTimeout(fn, ms);
        };
    }

    function buildToolbar(opts) {
        const bar = el('div', { class: 'ws-list-toolbar' });
        const left = el('div', { class: 'ws-list-toolbar-left' });

        if (opts.searchable !== false) {
            // Ô search riêng, lưu tham chiếu vào bar._searchInput để debounce submit sau.
            const searchWrap = el('div', { class: 'ws-search' });
            searchWrap.appendChild(el('span', { 'aria-hidden': 'true', innerHTML:
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>'
            }));
            const input = el('input', {
                type: 'search',
                placeholder: opts.searchPlaceholder || 'Tìm kiếm…',
                'aria-label': 'Tìm kiếm',
                value: opts.initialSearch || ''
            });
            input.className = '';
            searchWrap.appendChild(input);
            left.appendChild(searchWrap);
            bar._searchInput = input;
        }

        // Mỗi filter tạo 1 select; option đầu là "Tất cả" (value='') để clear filter nhanh.
        (opts.filters || []).forEach(filter => {
            const sel = el('select', {
                'aria-label': filter.label,
                class: 'ws-select',
                name: filter.name
            });
            sel.appendChild(el('option', { value: '' }, [filter.placeholder || ('Tất cả ' + filter.label.toLowerCase())]));
            (filter.options || []).forEach(o => {
                const value = typeof o === 'string' ? o : o.value;
                const label = typeof o === 'string' ? o : o.label;
                sel.appendChild(el('option', { value: value }, [label]));
            });
            if (filter.initialValue) sel.value = filter.initialValue;
            left.appendChild(sel);
            if (!bar._filters) bar._filters = {};
            bar._filters[filter.name] = sel;
        });

        if (opts.sortable && opts.sortOptions && opts.sortOptions.length) {
            const sel = el('select', { 'aria-label': 'Sắp xếp', class: 'ws-select', name: 'sortBy' });
            sel.appendChild(el('option', { value: '' }, ['Sắp xếp mặc định']));
            opts.sortOptions.forEach(o => {
                sel.appendChild(el('option', { value: o.value }, [o.label]));
            });
            if (opts.initialSort) sel.value = opts.initialSort;
            left.appendChild(sel);
            bar._sortSel = sel;
        }

        bar.appendChild(left);

        // Bên phải toolbar: nút Thêm mới + custom node (vd. export). Add btn
        // dùng href (không button) để browser xử lý SPA navigation thống nhất.
        const right = el('div', { class: 'ws-list-toolbar-right' });
        if (opts.toolbarRight) {
            opts.toolbarRight.forEach(node => right.appendChild(node));
        }
        if (opts.addUrl) {
            const link = el('a', {
                href: opts.addUrl,
                class: 'ws-btn primary'
            }, ['+ ' + (opts.addLabel || 'Thêm mới')]);
            right.appendChild(link);
        }
        bar.appendChild(right);

        return bar;
    }

    function buildPagination(state, total, onPageChange) {
        const totalPages = Math.max(1, Math.ceil(total / state.pageSize));
        const cur = Math.min(state.page + 1, totalPages);

        const wrap = el('div', { class: 'ws-pagination' });
        const info = el('div', { class: 'ws-pagination-info' });
        if (total === 0) {
            info.textContent = 'Không có dữ liệu';
        } else {
            const from = state.page * state.pageSize + 1;
            const to = Math.min((state.page + 1) * state.pageSize, total);
            info.textContent = `${from}–${to} / ${total} mục`;
        }
        wrap.appendChild(info);

        const controls = el('div', { class: 'ws-pagination-controls' });

        const prevBtn = el('button', {
            class: 'ws-pagination-btn',
            type: 'button',
            'aria-label': 'Trang trước'
        }, ['←']);
        prevBtn.disabled = state.page === 0;
        prevBtn.addEventListener('click', () => onPageChange(state.page - 1));
        controls.appendChild(prevBtn);

        // Window of 5 page numbers: luôn hiển thị tối đa 5 nút quanh current page
        // để không phải render hết khi totalPages > 100 (gây lag DOM).
        const start = Math.max(0, Math.min(state.page - 2, totalPages - 5));
        const end = Math.min(totalPages, start + 5);
        if (start > 0) {
            const first = el('button', { class: 'ws-pagination-btn', type: 'button' }, ['1']);
            first.addEventListener('click', () => onPageChange(0));
            controls.appendChild(first);
            if (start > 1) controls.appendChild(el('span', { style: 'padding:0 6px;color:#9ca3af' }, ['…']));
        }
        for (let p = start; p < end; p++) {
            const isActive = p === state.page;
            const btn = el('button', {
                class: 'ws-pagination-btn' + (isActive ? ' active' : ''),
                type: 'button',
                'aria-label': 'Trang ' + (p + 1),
                'aria-current': isActive ? 'page' : undefined
            }, [String(p + 1)]);
            btn.addEventListener('click', () => onPageChange(p));
            controls.appendChild(btn);
        }
        if (end < totalPages) {
            if (end < totalPages - 1) controls.appendChild(el('span', { style: 'padding:0 6px;color:#9ca3af' }, ['…']));
            const last = el('button', { class: 'ws-pagination-btn', type: 'button' }, [String(totalPages)]);
            last.addEventListener('click', () => onPageChange(totalPages - 1));
            controls.appendChild(last);
        }

        // Next
        const nextBtn = el('button', {
            class: 'ws-pagination-btn',
            type: 'button',
            'aria-label': 'Trang sau'
        }, ['→']);
        nextBtn.disabled = state.page >= totalPages - 1;
        nextBtn.addEventListener('click', () => onPageChange(state.page + 1));
        controls.appendChild(nextBtn);

        wrap.appendChild(controls);

        // Dropdown đổi page size; reset về page 0 vì trang cũ có thể không còn tồn tại.
        const sizeSel = el('select', { 'aria-label': 'Số mục / trang', class: 'ws-select' });
        [10, 20, 50, 100].forEach(n => {
            const o = el('option', { value: String(n), selected: n === state.pageSize ? 'selected' : undefined }, [String(n)]);
            sizeSel.appendChild(o);
        });
        sizeSel.addEventListener('change', () => {
            state.pageSize = parseInt(sizeSel.value);
            state.page = 0;
            onPageChange(0, true);
        });
        const sizeWrap = el('label', { class: 'ws-page-size' }, ['Mỗi trang:', sizeSel]);
        wrap.appendChild(sizeWrap);

        return wrap;
    }

    function buildEmptyState(opts) {
        const wrap = el('div', { class: 'ws-empty' });
        wrap.appendChild(el('div', { class: 'ws-empty-icon', innerHTML:
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="28" height="28"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>'
        }));
        wrap.appendChild(el('h3', {}, [opts.emptyTitle || 'Chưa có dữ liệu']));
        wrap.appendChild(el('p', {}, [opts.emptyMessage || 'Danh sách đang trống. Hãy thêm mới để bắt đầu.']));
        if (opts.addUrl && opts.addLabel) {
            wrap.appendChild(el('a', { href: opts.addUrl, class: 'ws-btn primary' }, ['+ ' + opts.addLabel]));
        }
        return wrap;
    }

    function buildTable(rows, columns, actionsBuilder) {
        const table = el('table', { class: 'ws-table' });
        const thead = el('thead');
        const tr = el('tr');
        columns.forEach(c => {
            const th = el('th', { scope: 'col' }, [c.label || '']);
            if (c.width) th.style.width = c.width;
            tr.appendChild(th);
        });
        if (actionsBuilder) {
            const th = el('th', { scope: 'col', class: 'ws-th-actions' }, ['']);
            tr.appendChild(th);
        }
        thead.appendChild(tr);
        table.appendChild(thead);

        const tbody = el('tbody');
        rows.forEach(row => {
            const trEl = el('tr');
            columns.forEach(c => {
                const td = el('td');
                if (c.render) {
                    const result = c.render(row);
                    if (result instanceof Node) td.appendChild(result);
                    else if (result != null) td.innerHTML = escapeHtml(String(result));
                } else if (c.key) {
                    const val = row[c.key];
                    td.textContent = (val == null || val === '') ? '—' : String(val);
                }
                trEl.appendChild(td);
            });
            if (actionsBuilder) {
                const td = el('td', { class: 'row-actions' });
                const actions = actionsBuilder(row) || [];
                actions.forEach(a => {
                    if (!a) return;
                    if (a.href) {
                        td.appendChild(el('a', {
                            href: a.href,
                            class: 'ws-btn ws-btn-sm ' + (a.class || 'secondary'),
                            'aria-label': a.label
                        }, [a.label]));
                    } else if (a.onClick) {
                        const btn = el('button', {
                            class: 'ws-btn ws-btn-sm ' + (a.class || 'secondary'),
                            type: 'button',
                            'aria-label': a.label
                        }, [a.label]);
                        btn.addEventListener('click', a.onClick);
                        td.appendChild(btn);
                    } else if (a.node) {
                        td.appendChild(a.node);
                    }
                });
                if (!td.children.length) td.appendChild(el('span', { class: 'sub muted' }, ['—']));
                trEl.appendChild(td);
            }
            tbody.appendChild(trEl);
        });
        table.appendChild(tbody);

        const wrap = el('div', { class: 'ws-table-wrap' });
        wrap.appendChild(table);
        return wrap;
    }

    function render(opts) {
        const viewRoot = document.getElementById('viewRoot');
        if (!viewRoot) return;

        // Đồng bộ topbar `<h1>` + `<title>` với title của list — fix trường hợp
        // module không có JSP riêng (vd. /console?module=screen) và topbar JSP
        // chỉ hiển thị default "Bảng điều khiển".
        if (hub && typeof hub.setWorkspaceHeader === 'function' && opts.title) {
            hub.setWorkspaceHeader(opts.title);
        }

        const state = {
            page: opts.initialPage || 0,
            pageSize: opts.pageSize || 20,
            search: opts.initialSearch || '',
            filters: {},
            sortBy: opts.initialSort || ''
        };

        // Page wrapper
        const page = el('div', { class: 'ws-page' });

        // Header
        const header = el('div', { class: 'ws-page-header' });
        header.appendChild(el('h1', { class: 'ws-page-title' }, [opts.title || 'Danh sách']));
        if (opts.subtitle) header.appendChild(el('p', { class: 'ws-page-subtitle' }, [opts.subtitle]));
        page.appendChild(header);

        // Toolbar
        const toolbar = buildToolbar(opts);
        page.appendChild(toolbar);

        // Table region
        const tableRegion = el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
        page.appendChild(tableRegion);

        // Pagination region
        const paginationRegion = el('div');
        page.appendChild(paginationRegion);

        viewRoot.replaceChildren(page);

        function reload() {
            // Show loading
            tableRegion.replaceChildren(el('div', { class: 'ws-list-loading' }, [
                el('div', { class: 'ws-spinner' }),
                el('span', {}, ['Đang tải dữ liệu…'])
            ]));
            paginationRegion.replaceChildren();

            // Chỉ thêm filter/sort vào URL khi user đã chọn giá trị (truthy) — giữ
            // URL sạch và tránh backend parse giá trị rỗng thành filter ngầm định.
            var params = new URLSearchParams();
            if (state.search) params.set(opts.searchParam || 'q', state.search);
            if (opts.filters) {
                opts.filters.forEach(function (f) {
                    if (toolbar._filters && toolbar._filters[f.name]) {
                        var v = toolbar._filters[f.name].value;
                        if (v) params.set(f.name, v);
                    }
                });
            }
            if (state.sortBy) params.set('sortBy', state.sortBy);
            params.set('offset', String(state.page * state.pageSize));
            params.set('limit', String(state.pageSize));

            try {
                opts.fetcher(params).then(function (result) {
                    var items = (result && Array.isArray(result.items)) ? result.items
                        : (Array.isArray(result) ? result : []);
                    var total = (result && typeof result.total === 'number') ? result.total : items.length;

                    if (!items.length) {
                        tableRegion.replaceChildren(buildEmptyState(opts));
                        return;
                    }

                    tableRegion.replaceChildren(buildTable(items, opts.columns, opts.actions));
                    if (opts.showPagination !== false) {
                        paginationRegion.replaceChildren(
                            buildPagination(state, total, function (p) {
                                state.page = p;
                                reload();
                            })
                        );
                    }
                }).catch(function (e) {
                    tableRegion.replaceChildren(el('div', { class: 'ws-empty' }, [
                        el('h3', {}, ['Lỗi tải dữ liệu']),
                        el('p', {}, [e.message || 'Không thể tải dữ liệu. Vui lòng thử lại.'])
                    ]));
                    hub.notify(e.message || 'Không thể tải dữ liệu.', 'err');
                });
            } catch (e) {
                tableRegion.replaceChildren(el('div', { class: 'ws-empty' }, [
                    el('h3', {}, ['Lỗi tải dữ liệu']),
                    el('p', {}, [e.message || 'Không thể tải dữ liệu. Vui lòng thử lại.'])
                ]));
                hub.notify(e.message || 'Không thể tải dữ liệu.', 'err');
            }
        }

        // Bind events
        if (toolbar._searchInput) {
            toolbar._searchInput.addEventListener('input', debounce(() => {
                state.search = toolbar._searchInput.value.trim();
                state.page = 0;
                reload();
            }, 350));
        }
        if (toolbar._filters) {
            Object.keys(toolbar._filters).forEach(name => {
                toolbar._filters[name].addEventListener('change', () => {
                    state.page = 0;
                    reload();
                });
            });
        }
        if (toolbar._sortSel) {
            toolbar._sortSel.addEventListener('change', () => {
                state.sortBy = toolbar._sortSel.value;
                state.page = 0;
                reload();
            });
        }

        reload();
    }

    window.CinemaHubList = { render };
})();
