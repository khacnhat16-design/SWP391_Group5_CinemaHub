/**
 * console.js — Renderer cho khu vuc van hanh (/console).
 * Chi goi REST API co san va dung DOM bang CinemaHub helpers (app.js).
 * Module chon theo ?module= khop link trong WEB-INF/views/layout/sidebar.jsp.
 * SU DUNG .then()/.catch() CHAIN — KHONG DUNG async/await.
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    if (!hub) return;

    var viewRoot = document.getElementById('viewRoot');
    var topbarActions = document.getElementById('topbarActions');
    if (!viewRoot) return;

    var params = new URLSearchParams(window.location.search);
    var role = (document.querySelector('meta[name="user-role"]') || {}).content || 'GUEST';
    var defaultModule = role === 'ADMIN' ? 'users'
        : role === 'BRANCH_MANAGER' ? 'showtime'
        : role === 'BRANCH_STAFF' ? 'pos-ticket' : 'discovery';
    var currentModule = params.get('module') || defaultModule;
    if (role === 'ADMIN' && !params.has('module')) {
        params.set('module', defaultModule);
        window.history.replaceState(
            {},
            '',
            window.location.pathname + '?' + params.toString() + window.location.hash
        );
    }

    // ---------- DOM shorthand ----------
    var el = hub.el;

    function card(title, sub) {
        var box = el('section', { class: 'app-card' });
        if (title) box.appendChild(el('h2', {}, [title]));
        if (sub) box.appendChild(el('p', { class: 'sub' }, [sub]));
        return box;
    }

    function loading() {
        viewRoot.setAttribute('aria-busy', 'true');
        viewRoot.replaceChildren(el('div', { class: 'loading-state', role: 'status' }, [
            el('span', { class: 'spinner', 'aria-hidden': 'true' }),
            el('span', {}, ['Dang tai du lieu...'])
        ]));
    }

    function emptyState(title, message, actionLabel, actionHref) {
        var box = el('div', { class: 'empty-state' }, [
            el('h3', {}, [title]),
            el('p', {}, [message])
        ]);
        if (actionLabel) {
            box.appendChild(el('a', { class: 'btn secondary', href: actionHref || '#' }, [actionLabel]));
        }
        viewRoot.replaceChildren(box);
    }

    function errorState(err) {
        var forbidden = err.status === 403 || err.code === 'FORBIDDEN';
        emptyState(
            forbidden ? 'Khong co quyen truy cap' : 'Khong tai duoc du lieu',
            err.message || 'Da xay ra loi. Vui long thu lai.',
            'Thu lai'
        );
        var link = viewRoot.querySelector('a');
        if (link) {
            link.addEventListener('click', function (e) {
                e.preventDefault();
                route(currentModule);
            });
        }
    }

    // ---------- shared helpers ----------
    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    function statusBadge(label) {
        var map = {
            'PAID': 'green', 'CONFIRMED': 'green', 'USED': 'purple', 'SELLING': 'blue',
            'OPEN': 'blue', 'SOLD_OUT': 'red', 'ALMOST_FULL': 'orange', 'PENDING': 'orange',
            'CANCELLED': 'red', 'ENDED': 'gray', 'INACTIVE': 'red', 'ACTIVE': 'green',
            'EXPIRED': 'gray', 'HOLD': 'orange', 'FAILED': 'red'
        };
        var tone = map[label] || 'gray';
        return '<span class="ws-badge ws-status-pill ws-badge-' + tone + '">' + escapeHtml(String(label || '')) + '</span>';
    }

    function debounce(fn, ms) {
        var t;
        return function () { clearTimeout(t); t = setTimeout(fn, ms); };
    }

    function emptyModule(title, message) {
        emptyState(title, message || 'Muc nay hien chua duoc kich hoat cho vai tro cua ban.');
    }

    // ---------- MODULES DISPATCH ----------
    function dispatchBusiness(name) {
        // Ưu tiên CinemaHubFormPages (pattern mới) trước — cho phép các module
        // đăng ký { list, view, create, edit } qua router.register giống admin.
        var formPages = window.CinemaHubFormPages || {};
        if (formPages[name] && typeof formPages[name].list === 'function') {
            return formPages[name].list;
        }
        var pages = window.CinemaHubPages || {};
        var handler = pages[name];
        if (typeof handler === 'function') return handler;
        return null;
    }

    // ============ PROFILE ============
    function moduleProfile() {
        loading();
        // Load /api/session trước để biết role, sau đó chọn endpoint phù hợp.
        // /api/profile chỉ dành cho CUSTOMER (ProfileController.requireCustomer).
        // ADMIN/MANAGER/STAFF dùng /api/session (luôn public, trả role + user info).
        hub.getSession().then(function (session) {
            var endpoint = (session.role === 'CUSTOMER') ? '/api/profile' : '/api/session';
            return hub.api(endpoint).then(function (data) {
                return { endpoint: endpoint, data: data, session: session };
            });
        }).then(function (resp) {
            var data = resp.data;
            if (resp.endpoint === '/api/profile') {
                var box = card('Ho so cua toi', 'Thong tin tai khoan Customer.');
                var tbl = el('table', { class: 'ws-table' });
                tbl.innerHTML = '<thead><tr><th>Truong</th><th>Gia tri</th></tr></thead>';
                var tb = el('tbody');
                var u = (data && data.user) || {};
                ['id', 'email', 'phone', 'fullName', 'role', 'status', 'createdAt'].forEach(function (f) {
                    tb.appendChild(el('tr', {}, [el('td', {}, [f]), el('td', {}, [String(u[f] || '')])]));
                });
                tbl.appendChild(tb);
                box.appendChild(tbl);
                viewRoot.replaceChildren(box);
            } else {
                // ADMIN/MANAGER/STAFF: dùng dữ liệu từ /api/session (csrfToken, userId, username, role)
                var box = card('Ho so dang nhap', 'Tai khoan dang nhap vao khu vuc quan ly.');
                var tbl = el('table', { class: 'ws-table' });
                tbl.innerHTML = '<thead><tr><th>Truong</th><th>Gia tri</th></tr></thead>';
                var tb = el('tbody');
                ['userId', 'email', 'username', 'role', 'csrfToken'].forEach(function (f) {
                    tb.appendChild(el('tr', {}, [
                        el('td', {}, [f]),
                        el('td', {}, [String(data[f] || '')])
                    ]));
                });
                tbl.appendChild(tb);
                box.appendChild(el('p', { class: 'muted', style: 'margin-top:12px;font-size:0.85rem' },
                    ['Tai khoan quan ly su dung endpoint /api/session (cong khai). De sua thong tin ca nhan hay lien he Admin.']));
                box.appendChild(tbl);
                viewRoot.replaceChildren(box);
            }
        }).catch(errorState);
    }

    function moduleProfileInternal() {
        // Giữ alias cho code cũ, delegate sang moduleProfile (auto-detect role).
        return moduleProfile();
    }

    // ============ DISCOVERY (customer) ============
    function moduleDiscovery() {
        var box = card('Lich chieu dang mo ban', 'Suat chieu thuoc chi nhanh hoat dong, phim dang phat hanh, khach khong can dang nhap.');
        viewRoot.replaceChildren(box);

        var bar = el('div', { class: 'toolbar' });
        var branchSel = el('select', { name: 'branchId' }, [el('option', { value: '' }, ['Tat ca chi nhanh'])]);
        var movieSel = el('select', { name: 'movieId' }, [el('option', { value: '' }, ['Tat ca phim'])]);
        var dateInput = el('input', { type: 'date', name: 'date', value: params.get('date') || '' });
        var applyBtn = el('button', { class: 'btn', type: 'button' }, ['Tim suat chieu']);
        var resetBtn = el('a', { class: 'btn secondary', href: hub.ctx + '/console?module=discovery' }, ['Xoa loc']);

        bar.appendChild(el('label', {}, [el('span', {}, ['Chi nhanh']), branchSel]));
        bar.appendChild(el('label', {}, [el('span', {}, ['Phim']), movieSel]));
        bar.appendChild(el('label', {}, [el('span', {}, ['Ngay']), dateInput]));
        bar.appendChild(applyBtn);
        bar.appendChild(resetBtn);
        box.appendChild(bar);

        var results = el('div', { id: 'discoveryResults' });
        box.appendChild(results);
        results.appendChild(el('div', { class: 'empty-state' }, [
            el('p', {}, ['Chon chi nhanh/phim/ngay roi bam "Tim suat chieu".'])
        ]));

        hub.api('/branch').then(function (br) {
            (Array.isArray(br) ? br : (br.items || [])).forEach(function (b) {
                branchSel.appendChild(el('option', { value: String(b.id) }, [b.name || ('#' + b.id)]));
            });
        }).catch(function () { /* ignore */ });

        // Customer accounts cannot access the internal movie catalog endpoint.
        // The public discovery endpoint still supports browsing without this
        // optional filter, so avoid generating expected 403 console errors.
        if (role !== 'CUSTOMER') {
            hub.api('/movie').then(function (mv) {
                var list = (Array.isArray(mv) ? mv : (mv.items || []));
                // Newest first: release_date DESC, id DESC (same as MovieDAO.findAll).
                list.sort(function (a, b) {
                    var ad = a && a.releaseDate ? new Date(a.releaseDate).getTime() : 0;
                    var bd = b && b.releaseDate ? new Date(b.releaseDate).getTime() : 0;
                    if (ad !== bd) return bd - ad;
                    return (b && b.id ? b.id : 0) - (a && a.id ? a.id : 0);
                });
                list.forEach(function (m) {
                    movieSel.appendChild(el('option', { value: String(m.id) }, [m.title || ('#' + m.id)]));
                });
            }).catch(function () { /* ignore */ });
        }

        function load() {
            results.replaceChildren(el('div', { class: 'loading-state' }, [el('p', {}, ['Dang tai...'])]));
            var qs = new URLSearchParams();
            if (branchSel.value) qs.set('branchId', branchSel.value);
            if (movieSel.value) qs.set('movieId', movieSel.value);
            if (dateInput.value) qs.set('date', dateInput.value);
            hub.api('/showtime/discovery' + (qs.toString() ? '?' + qs.toString() : '')).then(function (list) {
                var rows = Array.isArray(list) ? list : [];
                results.replaceChildren();
                if (!rows.length) {
                    results.appendChild(el('div', { class: 'empty-state' }, [el('h3', {}, ['Khong co suat chieu']), el('p', {}, ['Thu bo loc khac.'])]));
                    return;
                }
                rows.forEach(function (s) {
                    var row = el('article', { class: 'show-row' });
                    row.innerHTML = '<b>' + escapeHtml(s.movieTitle || '-') + '</b>'
                        + '<div><span>' + escapeHtml(s.branchName || '-') + ' - ' + escapeHtml(s.screenName || '-') + ' - ' + hub.fmtDateTime(s.startTime) + '</span></div>';
                    row.appendChild(el('strong', {}, [String(s.availableSeats != null ? s.availableSeats : '-') + ' ghe trong']));
                    if (role === 'CUSTOMER') {
                        if (s.showtimePassed) {
                            row.appendChild(el('span', { class: 'btn small disabled', 'aria-disabled': 'true' }, ['Da qua gio chieu']));
                        } else {
                            row.appendChild(el('a', { class: 'btn small', href: hub.ctx + '/booking?showtimeId=' + encodeURIComponent(s.id) }, ['Dat ve']));
                        }
                    }
                    results.appendChild(row);
                });
            }).catch(function (e) {
                results.replaceChildren(el('div', { class: 'flash flash-err' }, [e.message || 'Loi']));
            });
        }

        applyBtn.addEventListener('click', function () { load(); });
        if (dateInput.value) load();
    }

    // ============ SEAT (customer) ============
    function moduleSeat() {
        var showtimeId = params.get('showtimeId');
        if (!showtimeId) {
            emptyState('Chua chon suat chieu', 'Vui long chon mot suat chieu tu trang Lich chieu.',
                'Quay lai lich chieu', hub.ctx + '/console?module=discovery');
            return;
        }
        // The public booking page contains the complete hold/payment flow.
        // Keep this legacy route as a compatibility redirect instead of exposing
        // an incomplete seat-only screen.
        window.location.replace(hub.ctx + '/booking?showtimeId=' + encodeURIComponent(showtimeId));
        return;
        var box = card('Chon ghe', 'Chon ghe ngoi cho suat chieu cua ban.');
        viewRoot.replaceChildren(box);
        var seatArea = el('div', { class: 'seat-area' });
        box.appendChild(seatArea);
        seatArea.appendChild(el('div', { class: 'loading-state' }, [el('p', {}, ['Dang tai so do ghe...'])]));
        hub.api('/showtime/' + showtimeId).then(function (showtimeData) {
            var st = showtimeData.showtime;
            var seats = showtimeData.seats || [];
            seatArea.replaceChildren();
            seatArea.appendChild(el('div', {}, [
                el('h3', {}, [st.movieTitle || 'Phim']),
                el('p', {}, [(st.branchName || '') + ' - ' + (st.screenName || '') + ' - ' + hub.fmtDateTime(st.startTime)])
            ]));
            var grid = el('div', { class: 'seat-grid' });
            grid.appendChild(el('div', { class: 'screen-indicator' }, ['MAN HINH']));
            var rows = {};
            seats.forEach(function (s) {
                var r = s.rowLabel || 'A';
                if (!rows[r]) rows[r] = [];
                rows[r].push(s);
            });
            Object.keys(rows).sort().forEach(function (r) {
                var rowEl = el('div', { class: 'seat-row' });
                rowEl.appendChild(el('small', {}, [r]));
                var wrap = el('div');
                rows[r].sort(function (a, b) { return (a.colNo || 0) - (b.colNo || 0); }).forEach(function (seat) {
                    var btn = el('button', { class: 'seat', type: 'button' }, [String(seat.rowLabel) + seat.colNo]);
                    btn.disabled = seat.status !== 'AVAILABLE';
                    if (seat.seatType === 'VIP') btn.classList.add('vip');
                    if (seat.seatType === 'COUPLE') btn.classList.add('couple');
                    wrap.appendChild(btn);
                });
                rowEl.appendChild(wrap);
                grid.appendChild(rowEl);
            });
            seatArea.appendChild(grid);
        }).catch(function (e) {
            seatArea.replaceChildren(el('div', { class: 'flash flash-err' }, [e.message || 'Loi']));
        });
    }

    // ============ POS (delegate to pos-staff.js) ============
    function modulePosTicket() {
        if (window.modulePosTicket) return window.modulePosTicket();
        emptyModule('Ban ve tai quay', 'Module POS chua san sang.');
    }

    // ============ MODULE DISPATCH MAP ============
    var MODULES = {
        profile: function () {
            if (role !== 'CUSTOMER') return moduleProfileInternal();
            var handler = dispatchBusiness('profile');
            if (handler) return handler();
            return moduleProfile();
        },
        discovery: moduleDiscovery,
        reviews: function () {
            var handler = dispatchBusiness('reviews');
            return (handler && handler()) || emptyModule('Đánh giá phim', 'Chức năng đánh giá chưa sẵn sàng.');
        },
        branch: function () { var h = dispatchBusiness('branch'); return (h && h()) || emptyModule('Chi nhanh', 'Chuc nang chua sang'); },
        movie: function () { var h = dispatchBusiness('movie'); return (h && h()) || emptyModule('Phim', 'Chuc nang chua sang'); },
        genres: function () { var h = dispatchBusiness('genres'); return (h && h()) || emptyModule('Thể loại phim', 'Chức năng chưa sẵn sàng'); },
        screen: function () { var h = dispatchBusiness('screen'); return (h && h()) || emptyModule('Phong chieu', 'Chuc nang chua sang'); },
        showtime: function () { var h = dispatchBusiness('showtime'); return (h && h()) || emptyModule('Lich chieu', 'Chuc nang chua sang'); },
        booking: function () { var h = dispatchBusiness('booking'); return (h && h()) || emptyModule('Dat ve', 'Chuc nang chua sang'); },
        ticket: function () {
            if (typeof window.moduleTicketStaff === 'function') return window.moduleTicketStaff();
            return emptyModule('Ve', 'Chuc nang chua sang');
        },
        pricing: function () { var h = dispatchBusiness('pricing'); return (h && h()) || emptyModule('Khung gia', 'Chuc nang chua sang'); },
        notification: function () { var h = dispatchBusiness('notification'); return (h && h()) || emptyModule('Thong bao', 'Chuc nang chua sang'); },
        'pos-ticket': modulePosTicket,
        seat: moduleSeat,
        'my-tickets': function () {
            // Customer tickets are registered as a form-page renderer, not in the
            // legacy CinemaHubPages registry used by the other console modules.
            var formPages = window.CinemaHubFormPages || {};
            if (formPages.booking && typeof formPages.booking.list === 'function') {
                formPages.booking.list();
                return;
            }
            emptyModule('Ve cua toi', 'Chuc nang chua sang');
        },
        // Reviews.
    };

    // ============ ROUTER ============
    function updateActiveNavigation(moduleName) {
        document.querySelectorAll('a.ws-menu-link, a.sb-link').forEach(function (link) {
            var href = link.getAttribute('href');
            if (!href) return;
            var linkedModule = new URL(href, window.location.href).searchParams.get('module') || defaultModule;
            var active = linkedModule === moduleName;
            link.classList.toggle('active', active);
            if (active) {
                link.setAttribute('aria-current', 'page');
            } else {
                link.removeAttribute('aria-current');
            }
        });
    }

    function route(mod) {
        currentModule = mod;
        updateActiveNavigation(mod);
        if (topbarActions) topbarActions.replaceChildren();

        // Uu tien CinemaHubRouter (form-page modules): xu ly ?module=X&action=Y&id=Z
        if (window.CinemaHubRouter && window.CinemaHubRouter.autoRoute()) {
            viewRoot.setAttribute('aria-busy', 'false');
            return;
        }

        loading();
        var fn = MODULES[mod] || moduleDiscovery;
        try {
            var result = fn();
            // Neu tra ve cleanup function, luu lai
            if (typeof result === 'function') {
                window.__currentCleanup = result;
            } else if (result instanceof Node) {
                // Auto-mount modules registered outside this file.
                // trả về DOM node mà không tự gắn vào viewRoot — gắn giúp chúng render.
                viewRoot.replaceChildren(result);
            }
        } catch (e) {
            errorState(e);
        }
        viewRoot.setAttribute('aria-busy', 'false');
    }

    // Cleanup khi chuyen module
    window.addEventListener('popstate', function () {
        if (typeof window.__currentCleanup === 'function') {
            window.__currentCleanup();
            window.__currentCleanup = null;
        }
        var activeModule = new URLSearchParams(window.location.search).get('module') || defaultModule;
        route(activeModule);
    });

    // ---------- customer sidebar ----------
    var customerSidebar = document.getElementById('customerSidebar');
    var customerSidebarToggle = document.getElementById('customerSidebarToggle');
    var customerSidebarBackdrop = document.getElementById('customerSidebarBackdrop');

    function setCustomerSidebarOpen(open) {
        if (!customerSidebar || !customerSidebarToggle || !customerSidebarBackdrop) return;
        customerSidebar.classList.toggle('open', open);
        customerSidebarBackdrop.classList.toggle('open', open);
        customerSidebarBackdrop.hidden = !open;
        customerSidebarToggle.setAttribute('aria-expanded', String(open));
    }

    if (customerSidebarToggle) {
        customerSidebarToggle.addEventListener('click', function () {
            setCustomerSidebarOpen(!customerSidebar.classList.contains('open'));
        });
    }
    if (customerSidebarBackdrop) {
        customerSidebarBackdrop.addEventListener('click', function () {
            setCustomerSidebarOpen(false);
        });
    }

    // ---------- sidebar nav click ----------
    document.addEventListener('click', function (e) {
        var a = e.target.closest ? e.target.closest('a.ws-menu-link, a.sb-link') : null;
        if (!a) return;
        var href = a.getAttribute('href');
        if (!href) return;
        var m = href.match(/[?&]module=([^&]+)/);
        if (m) {
            e.preventDefault();
            history.pushState({}, '', href);
            route(m[1]);
            setCustomerSidebarOpen(false);
        }
    });

    // Defer the initial route so all deferred scripts (including modules-schedule.js,
    // modules-form-pages.js, etc.) have had a chance to register their pages first.
    document.addEventListener('DOMContentLoaded', function () {
        route(currentModule);
    });
})();
