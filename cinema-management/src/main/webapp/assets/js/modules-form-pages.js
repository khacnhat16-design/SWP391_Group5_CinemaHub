/**
 * modules-form-pages.js — Đăng ký form-pages cho các module có source (Promise-chain style).
 *
 * Mỗi module khai báo:
 *   - list: render list page với search/filter/sort/pagination
 *   - create: render form page create
 *   - edit: render form page edit (load data trước)
 *
 * Đăng ký qua window.CinemaHubRouter.register(module, { list, create, edit }).
 *
 * Pattern: hub.api() trả Promise → dùng .then()/.catch() chain.
 * KHÔNG dùng async/await (giữ code style đồng nhất với Java Servlet phía server).
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var router = window.CinemaHubRouter;
    var list = window.CinemaHubList;
    var form = window.CinemaHubForm;
    if (!hub || !router || !list || !form) return;

    var ctx = hub.ctx || '';
    var STATUS_MAP = {
        'PAID': 'green', 'CONFIRMED': 'green', 'USED': 'purple', 'SELLING': 'blue',
        'OPEN': 'blue', 'SOLD_OUT': 'red', 'ALMOST_FULL': 'orange', 'PENDING': 'orange',
        'CANCELLED': 'red', 'ENDED': 'gray', 'INACTIVE': 'red', 'ACTIVE': 'green',
        'EXPIRED': 'gray', 'HOLD': 'orange', 'FAILED': 'red',
        'PENDING_PAYMENT': 'orange', 'FULFILLED': 'purple', 'READY_FOR_PICKUP': 'green',
        'EXPIRED_NO_SHOW': 'gray', 'LOCKED': 'red', 'REJECTED': 'red', 'APPROVED': 'green',
        'CLOSED': 'gray', 'DRAFT': 'gray', 'PUBLISHED': 'green', 'ARCHIVED': 'gray'
    };

    function statusBadge(label) {
        var tone = STATUS_MAP[label] || 'gray';
        var wrap = document.createElement('span');
        wrap.className = 'ws-badge ws-status-pill ws-badge-' + tone;
        wrap.textContent = label || '';
        return wrap;
    }

    function confirmAction(message, onYes) {
        if (window.confirm(message)) onYes();
    }

    function notifyError(err) {
        hub.notify(err.message || 'Đã xảy ra lỗi.', 'err');
    }

    // ================================================================
    //  SHOWTIME (Admin + Manager)
    // ================================================================
    router.register('showtime', {
        list: function () {
            Promise.all([
                hub.api('/branch').catch(function () { return []; }),
                hub.api('/movie').catch(function () { return []; })
            ]).then(function (results) {
                var branches = results[0] || [];
                var movies = results[1] || [];
                list.render({
                    title: 'Suất chiếu',
                    subtitle: 'Quản lý lịch chiếu phim theo chi nhánh và phòng.',
                    addUrl: ctx + '/console?module=showtime&action=create',
                    addLabel: 'Tạo suất chiếu',
                    pageSize: 20,
                    filters: [
                        { name: 'branchId', label: 'Chi nhánh',
                          options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
                        { name: 'movieId', label: 'Phim',
                          options: (movies || []).map(function (m) { return { value: String(m.id), label: m.title }; }) },
                        { name: 'status', label: 'Trạng thái', options: [
                            { value: 'OPEN', label: 'Mở bán' },
                            { value: 'ENDED', label: 'Đã kết thúc' },
                            { value: 'CANCELLED', label: 'Đã hủy' }
                        ]}
                    ],
                    sortable: true,
                    sortOptions: [
                        { value: 'startTime', label: 'Giờ bắt đầu' },
                        { value: 'createdAt', label: 'Mới nhất' }
                    ],
                    fetcher: function (qs) { return hub.api('/showtime/manage/search?' + qs.toString()); },
                    emptyTitle: 'Chưa có suất chiếu',
                    emptyMessage: 'Hãy tạo suất chiếu đầu tiên cho chi nhánh.',
                    columns: [
                        { label: 'Phim', key: 'movieTitle' },
                        { label: 'Chi nhánh', key: 'branchName' },
                        { label: 'Phòng', key: 'screenName' },
                        { label: 'Bắt đầu', render: function (s) { return s.startTime ? hub.fmtDateTime(s.startTime) : '—'; } },
                        { label: 'Kết thúc', render: function (s) { return s.endTime ? hub.fmtDateTime(s.endTime) : '—'; } },
                        { label: 'Trạng thái', render: function (s) { return statusBadge(s.status); } }
                    ],
                    actions: function (row) {
                        if (row.status === 'CANCELLED') {
                            return [{
                                label: 'Khôi phục', class: 'primary', onClick: function () {
                                    confirmAction('Khôi phục suất chiếu này?', function () {
                                        hub.api('/showtime/' + row.id,
                                            { method: 'PUT', body: { action: 'restore' } })
                                            .then(function () {
                                                hub.notify('Đã khôi phục suất chiếu.', 'ok');
                                                router.go('showtime');
                                            })
                                            .catch(notifyError);
                                    });
                                }
                            }];
                        }
                        if (row.status !== 'OPEN') return [];
                        return [
                            { label: 'Sửa giờ', class: 'secondary',
                              href: ctx + '/console?module=showtime&action=edit&id=' + row.id },
                            { label: 'Hủy', class: 'danger', onClick: function () {
                                confirmAction('Hủy suất chiếu này?', function () {
                                    hub.api('/showtime/' + row.id, { method: 'PUT', body: { action: 'cancel' } })
                                        .then(function () {
                                            hub.notify('Đã hủy suất chiếu.', 'ok');
                                            router.go('showtime');
                                        })
                                        .catch(notifyError);
                                });
                            }}
                        ];
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            var params = new URLSearchParams(window.location.search);
            var requestedBranchId = params.get('branchId') || '';
            var requestedMovieId = params.get('movieId') || '';
            function getVietnamDateTimeParts() {
                return new Intl.DateTimeFormat('en-CA', {
                    timeZone: 'Asia/Ho_Chi_Minh',
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit',
                    hourCycle: 'h23'
                }).formatToParts(new Date()).reduce(function (parts, part) {
                    if (part.type !== 'literal') parts[part.type] = part.value;
                    return parts;
                }, {});
            }
            var currentDateTime = getVietnamDateTimeParts();
            var today = currentDateTime.year + '-' + currentDateTime.month + '-' + currentDateTime.day;
            Promise.all([
                hub.api('/branch').catch(function () { return []; }),
                requestedMovieId ? hub.api('/movie/' + encodeURIComponent(requestedMovieId))
                    : hub.api('/movie')
            ]).then(function (results) {
                var branches = results[0] || [];
                var movies = requestedMovieId
                    ? (results[1] ? [results[1]] : [])
                    : (results[1] || []);
                var initialBranch = branches.find(function (branch) {
                    return String(branch.id) === String(requestedBranchId);
                }) || branches[0];
                var initialBranchId = initialBranch ? String(initialBranch.id) : '';
                var screensPromise = initialBranchId
                    ? hub.api('/screen?branchId=' + encodeURIComponent(initialBranchId))
                        .then(function (items) { return Array.isArray(items) ? items : []; })
                    : Promise.resolve([]);
                return screensPromise.then(function (screens) {
                    return {
                        branches: branches,
                        movies: movies,
                        screens: screens,
                        initialBranchId: initialBranchId
                    };
                });
            }).then(function (data) {
                var branches = data.branches;
                var movies = data.movies;
                var screens = data.screens;
                var updateMovieLoadingButton = function () {};
                var requestedMovie = requestedMovieId
                    ? movies.find(function (movie) {
                        return String(movie.id) === String(requestedMovieId);
                    })
                    : null;
                var showDateMin = requestedMovie && requestedMovie.releaseDate > today
                    ? requestedMovie.releaseDate
                    : today;
                var initialShowDate = '';
                if (requestedMovie && requestedMovie.releaseDate && requestedMovie.endDate) {
                    if (showDateMin <= requestedMovie.endDate) {
                        initialShowDate = showDateMin;
                    }
                }
                form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Suất chiếu', href: ctx + '/console?module=showtime' }, 'Tạo mới'],
                    title: 'Tạo suất chiếu mới',
                    subtitle: 'Lên lịch chiếu phim cho phòng và khung giờ cụ thể.',
                    submitLabel: 'Tạo suất chiếu',
                    sections: [{
                        title: 'Thông tin suất chiếu',
                        fields: [
                            { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true,
                              value: data.initialBranchId,
                              options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }),
                              },
                            { name: 'movieId', label: 'Phim', type: 'select', required: true,
                              value: requestedMovieId,
                              options: (movies || []).filter(function (m) { return m.status === 'PUBLISHED'; }).map(function (m) {
                                  return { value: String(m.id), label: m.title + ' (' + (m.durationMin || '?') + 'p)' };
                              }) },
                            { name: 'screenId', label: 'Phòng chiếu', type: 'select', required: true,
                              options: (screens || []).map(function (s) { return { value: String(s.id), label: (s.name || 'Phòng #' + s.id) }; }) },
                            { name: 'showDate', label: 'Ngày chiếu', type: 'date', required: true,
                              value: initialShowDate, min: showDateMin,
                              max: requestedMovie ? requestedMovie.endDate : undefined,

                              customValidate: function (value) {
                                  var now = getVietnamDateTimeParts();
                                  var currentDate = now.year + '-' + now.month + '-' + now.day;
                                  if (value < currentDate) return 'Không thể chọn ngày trong quá khứ.';
                                  if (requestedMovie && (value < requestedMovie.releaseDate || value > requestedMovie.endDate)) {
                                      return 'Ngày chiếu phải nằm trong thời hạn hiệu lực của phim.';
                                  }
                                  return '';
                              } },
                            { name: 'startHour', label: 'Giờ bắt đầu', type: 'time', required: true,
                              customValidate: function (value) {
                                  var showDateInput = document.querySelector('input[name="showDate"]');
                                  var now = getVietnamDateTimeParts();
                                  var currentDate = now.year + '-' + now.month + '-' + now.day;
                                  var currentTimeValue = now.hour + ':' + now.minute;
                                  if (showDateInput && showDateInput.value === currentDate && value < currentTimeValue) {
                                      return 'Giờ bắt đầu phải nằm trong tương lai.';
                                  }
                                  return '';
                              } },
                            { name: 'startTime', type: 'hidden' },
                            { name: 'cleaningBufferMin', label: 'Thời gian vệ sinh giữa ca (phút)', type: 'number', min: 0, max: 60, value: 15,required: true
                               }
                        ]
                    }],
                    // Khi đổi chi nhánh → refetch /screen?branchId=X và repopulate dropdown "Phòng chiếu".
                    renderFooter: function (formEl) {
                        var branchSelect = formEl.querySelector('select[name="branchId"]');
                        var screenSelect = formEl.querySelector('select[name="screenId"]');
                        if (!branchSelect || !screenSelect) return;

                        function populateScreens(list) {
                            // Lưu giá trị đang chọn (nếu còn tồn tại trong list mới)
                            var prev = screenSelect.value;
                            screenSelect.innerHTML = '';
                            var placeholder = document.createElement('option');
                            placeholder.value = '';
                            placeholder.textContent = (list && list.length)
                                ? '— Chọn phòng chiếu —'
                                : '— Chi nhánh này chưa có phòng chiếu —';
                            screenSelect.appendChild(placeholder);
                            (list || []).forEach(function (s) {
                                var opt = document.createElement('option');
                                opt.value = String(s.id);
                                opt.textContent = s.name || ('Phòng #' + s.id);
                                screenSelect.appendChild(opt);
                            });
                            // Khôi phục lựa chọn nếu vẫn hợp lệ, nếu không thì reset về rỗng.
                            if (prev && (list || []).some(function (s) { return String(s.id) === prev; })) {
                                screenSelect.value = prev;
                            } else {
                                screenSelect.value = '';
                            }
                        }

                        function loadScreensForBranch(branchId) {
                            if (!branchId) {
                                populateScreens([]);
                                return;
                            }
                            hub.api('/screen?branchId=' + encodeURIComponent(branchId))
                                .then(function (list) { populateScreens(Array.isArray(list) ? list : []); })
                                .catch(function () { populateScreens([]); });
                        }

                        var scopedBranchId = screens.length ? String(screens[0].branchId) : '';
                        var scopedBranch = (branches || []).find(function (branch) {
                            return String(branch.id) === scopedBranchId;
                        });
                        if (scopedBranch) {
                            branchSelect.innerHTML = '';
                            var branchOption = document.createElement('option');
                            branchOption.value = scopedBranchId;
                            branchOption.textContent = scopedBranch.name;
                            branchSelect.appendChild(branchOption);
                            branchSelect.value = scopedBranchId;
                        }

                        branchSelect.addEventListener('change', function () {
                            loadScreensForBranch(branchSelect.value);
                        });

                        // Khởi tạo dropdown theo branchId đang được chọn (nếu có)
                        loadScreensForBranch(branchSelect.value);

                        var movieSelect = formEl.querySelector('select[name="movieId"]');
                        var showDateInput = formEl.querySelector('input[name="showDate"]');
                        if (movieSelect && showDateInput) {
                            var movieRequestId = 0;

                            function setMovieLoading(loading) {
                                movieSelect.disabled = loading;
                                updateMovieLoadingButton(loading);
                            }

                            function populateMovies(items, emptyMessage) {
                                var previous = movieSelect.value || requestedMovieId;
                                movieSelect.innerHTML = '';
                                var placeholder = document.createElement('option');
                                placeholder.value = '';
                                placeholder.textContent = items.length
                                    ? '— Chọn phim —'
                                    : (emptyMessage || '— Ngày này không có phim đang phát hành —');
                                movieSelect.appendChild(placeholder);
                                items.forEach(function (movie) {
                                    var option = document.createElement('option');
                                    option.value = String(movie.id);
                                    option.textContent = movie.title + ' (' + (movie.durationMin || '?') + 'p)';
                                    movieSelect.appendChild(option);
                                });
                                movieSelect.value = items.some(function (movie) {
                                    return String(movie.id) === previous;
                                }) ? previous : '';
                            }

                            function loadMoviesForStartDate() {
                                if (requestedMovieId) {
                                    return;
                                }
                                var requestId = ++movieRequestId;
                                var date = showDateInput.value || '';
                                var query = date ? '?date=' + encodeURIComponent(date) : '';
                                setMovieLoading(true);
                                hub.api('/movie' + query)
                                    .then(function (items) {
                                        if (requestId !== movieRequestId) return;
                                        var selectable = (Array.isArray(items) ? items : [])
                                            .filter(function (movie) {
                                                return movie.status === 'PUBLISHED'
                                                    && (!date || (movie.releaseDate && movie.endDate
                                                        && movie.releaseDate <= date
                                                        && movie.endDate >= date));
                                            });
                                        populateMovies(selectable);
                                        setMovieLoading(false);
                                    })
                                    .catch(function (error) {
                                        if (requestId !== movieRequestId) return;
                                        populateMovies([], '— Không tải được danh sách phim —');
                                        setMovieLoading(false);
                                        notifyError(error);
                                    });
                            }

                            showDateInput.addEventListener('change', loadMoviesForStartDate);
                        }
                    },
                    onReady: function (formEl) {
                        var submitButton = formEl.querySelector('button[type="submit"]');
                        if (submitButton) {
                            updateMovieLoadingButton = function (loading) {
                                submitButton.disabled = loading;
                            };
                        }
                    },
                    onSubmit: function (data) {
                        data.startTime = data.showDate + 'T' + data.startHour;
                        delete data.showDate;
                        delete data.startHour;
                        hub.api('/showtime', { method: 'POST', body: data })
                            .then(function () { router.go('showtime'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('showtime'); }
                });
            }).catch(notifyError);
        },
        edit: function (id) {
            hub.api('/showtime/' + id).then(function (detail) {
                var showtime = detail.showtime || detail;
                form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Suất chiếu', href: ctx + '/console?module=showtime' }, 'Chỉnh sửa'],
                    title: 'Chỉnh sửa suất chiếu',
                    subtitle: showtime.movieTitle && showtime.branchName
                        ? showtime.movieTitle + ' — ' + showtime.branchName
                        : 'Chỉ thay đổi giờ bắt đầu; phim, phòng và chi nhánh được giữ nguyên.',
                    submitLabel: 'Lưu thay đổi',
                    sections: [{
                        title: 'Thông tin suất chiếu',
                        fields: [
                            { name: 'startTime', label: 'Giờ bắt đầu', type: 'datetime-local', required: true,
                              value: showtime.startTime ? showtime.startTime.replace(' ', 'T').substring(0, 16) : '' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/showtime/' + id, { method: 'PUT', body: data })
                            .then(function () { router.go('showtime'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('showtime'); }
                });
            }).catch(notifyError);
        }
    });

    //  NOTIFICATION
    // ================================================================
    router.register('notification', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Thông báo']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Thông báo in-app của bạn.'])
            ]));
            var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
            var left = hub.el('div', { class: 'ws-list-toolbar-left' });
            var filterSel = hub.el('select', { class: 'ws-select', 'aria-label': 'Lọc' }, [
                hub.el('option', { value: '' }, ['Tất cả']),
                hub.el('option', { value: 'unread' }, ['Chưa đọc'])
            ]);
            left.appendChild(filterSel);
            toolbar.appendChild(left);
            var right = hub.el('div', { class: 'ws-list-toolbar-right' });
            var markAllBtn = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Đánh dấu tất cả đã đọc']);
            right.appendChild(markAllBtn);
            toolbar.appendChild(right);
            page.appendChild(toolbar);

            var tableWrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
            page.appendChild(tableWrap);
            viewRoot.replaceChildren(page);

            function load() {
                tableWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                var qs = filterSel.value === 'unread' ? '?unreadOnly=true' : '';
                hub.api('/notification' + qs).then(function (data) {
                    var list = (data && data.notifications) || [];
                    if (!list.length) {
                        tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Không có thông báo'])
                        ]));
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Nội dung</th><th>Thời điểm</th><th>Trạng thái</th><th></th></tr></thead>';
                    var tbody = hub.el('tbody');
                    list.forEach(function (n) {
                        var tr = hub.el('tr');
                        tr.appendChild(hub.el('td', {}, [n.body || n.title || '—']));
                        tr.appendChild(hub.el('td', {}, [
                            n.createdAt ? hub.fmtNotificationDateTime(n.createdAt) : '—'
                        ]));
                        tr.appendChild(hub.el('td', {}, [statusBadge(n.read ? 'USED' : 'PENDING')]));
                        var actions = hub.el('td', { class: 'row-actions' });
                        if (!n.read) {
                            var btn = hub.el('button', { class: 'ws-btn ws-btn-sm secondary', type: 'button' }, ['Đã đọc']);
                            btn.addEventListener('click', function () {
                                hub.api('/notification/' + n.id + '/read', { method: 'POST' })
                                    .then(load)
                                    .catch(notifyError);
                            });
                            actions.appendChild(btn);
                        }
                        tr.appendChild(actions);
                        tbody.appendChild(tr);
                    });
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);
                }).catch(notifyError);
            }
            markAllBtn.addEventListener('click', function () {
                hub.api('/notification/read-all', { method: 'POST' })
                    .then(function () {
                        hub.notify('Đã đánh dấu tất cả là đã đọc.', 'ok');
                        load();
                    })
                    .catch(notifyError);
            });
            filterSel.addEventListener('change', load);
            load();
        }
    });

    // ================================================================
    //  SCREEN (Admin + Manager)
    // ================================================================
    /**
     * Render chọn ghế VIP với 3 cơ chế:
     *  - Click ghế = toggle VIP/Standard (nhanh cho 1 ghế).
     *  - Click nhãn hàng A/B/C... = set cả hàng thành VIP.
     *  - Kéo chuột (drag) trên nhiều ghế = chọn cả vùng.
     *    + Nếu bắt đầu kéo trên ghế thường → cả vùng thành VIP.
     *    + Nếu bắt đầu kéo trên ghế VIP → cả vùng thành ghế thường.
     *    + Giữ phím Alt khi kéo = toggle ngược lại từng ghế trong vùng.
     *
     * Trải nghiệm giống các tool thiết kế phòng chiếu: chọn vùng nhanh,
     * không cần click từng ghế một.
     */
    function renderSeatPicker(container, vipInputOrRows, rowCount, colCount) {
        // Alias helper `el`: renderSeatPicker ban đầu được viết phụ thuộc vào helper el(),
        // nhưng `el` không có sẵn trong scope — gây ReferenceError khi re-render từ rowCount/colCount.
        // Lấy từ CinemaHub (đã được đăng ký trong app.js) hoặc fallback về createElement.bind(document).
        var el = (hub && hub.el) || (function () {
            return function (tag, attrs, children) {
                var node = document.createElement(tag);
                if (attrs) {
                    Object.keys(attrs).forEach(function (k) {
                        if (k === 'class') node.className = attrs[k];
                        else if (k === 'text') node.textContent = attrs[k];
                        else node.setAttribute(k, attrs[k]);
                    });
                }
                if (children) {
                    (Array.isArray(children) ? children : [children]).forEach(function (c) {
                        if (c == null) return;
                        node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
                    });
                }
                return node;
            };
        })();

        container.innerHTML = '';

        // Hỗ trợ 2 kiểu tham số để không phá code cũ:
        //  - Nếu caller truyền <input type=hidden> thì bind trực tiếp vào đó.
        //  - Nếu truyền chuỗi vipRows thì tự tạo hidden input mới (chỉ dùng cho
        //    smoke test / debug).
        var hiddenVip;
        var initialVipRows;
        if (vipInputOrRows && vipInputOrRows.tagName === 'INPUT') {
            hiddenVip = vipInputOrRows;
            initialVipRows = hiddenVip.value || '';
        } else {
            initialVipRows = vipInputOrRows || '';
            hiddenVip = document.createElement('input');
            hiddenVip.type = 'hidden';
            hiddenVip.name = 'vipRows';
            hiddenVip.value = initialVipRows;
            container.appendChild(hiddenVip);
        }

        var vipRowSet = new Set();
        if (initialVipRows) {
            initialVipRows.split(/[,;\s]+/).forEach(function (s) {
                var t = s.trim().toUpperCase();
                if (t) vipRowSet.add(t);
            });
        }

        // Toolbar với legend phím tắt
        var toolbar = el('div', { class: 'seat-picker-toolbar' });
        toolbar.appendChild(el('span', { class: 'seat-picker-label' }, ['Sơ đồ ghế']));
        toolbar.appendChild(el('span', { class: 'seat-picker-hint' },
            ['Click ghế: toggle. Kéo chuột: chọn vùng. Click nhãn hàng: chọn cả hàng.']));
        container.appendChild(toolbar);

        // Mode indicator (Vip/Standard) — phản ánh mode hiện tại khi đang kéo
        var modeBadge = el('span', { class: 'seat-picker-mode-badge', 'aria-live': 'polite' });

        var scrollWrap = el('div', { class: 'seat-picker-scroll' });
        var screen = el('div', { class: 'seat-picker-screen' });
        screen.appendChild(el('div', { class: 'seat-picker-curtain' }, ['— Màn hình —']));
        var grid = el('div', { class: 'seat-grid' });

        // --------- Trạng thái cho drag-select ---------
        // dragging: đang kéo hay không
        // dragMode: 'add-vip' (set VIP) hoặc 'remove-vip' (set thường) — dựa trên
        //   trạng thái GỐC (chưa apply) của ghế đầu tiên user pointer-down vào.
        // originalStates: Map<seatBtn, boolean> lưu VIP/GỐC của từng ghế đã chạm
        //   trong lần kéo — để click đơn (size=1) biết phải toggle về phía ngược lại.
        var dragging = false;
        var dragMode = null;
        var originalStates = new Map(); // btn -> bool (wasVip trước khi apply)
        var dragHandled = new Set();

        var rowCells = {};
        for (var i = 0; i < rowCount; i++) {
            var rowLabel = String.fromCharCode(65 + i);
            var row = el('div', { class: 'seat-row' });
            var rowLabelBtn = el('button', {
                type: 'button',
                class: 'seat-row-label',
                title: 'Bấm để chọn cả hàng ' + rowLabel + ' là ghế VIP'
            }, [rowLabel]);
            rowLabelBtn.addEventListener('click', (function (capRow) {
                return function () {
                    var nodes = rowCells[capRow] || [];
                    var anyNotVip = nodes.some(function (n) { return !n.classList.contains('is-vip'); });
                    nodes.forEach(function (n) {
                        if (anyNotVip) n.classList.add('is-vip');
                        else n.classList.remove('is-vip');
                    });
                    updateSummary();
                };
            })(rowLabel));
            row.appendChild(rowLabelBtn);

            rowCells[rowLabel] = [];
            for (var c = 1; c <= colCount; c++) {
                var isVip = vipRowSet.has(rowLabel);
                var seatBtn = el('button', {
                    type: 'button',
                    class: 'seat-cell' + (isVip ? ' is-vip' : ''),
                    'data-row': rowLabel,
                    'data-col': String(c),
                    'aria-label': rowLabel + c + (isVip ? ' (VIP)' : '')
                }, [String(c)]);

                // ---- Drag-select với pointer events ----
                // pointerdown: bắt đầu theo dõi, lưu trạng thái GỐC của ghế,
                //   set mode dựa trên trạng thái gốc (click vào ghế VIP sẽ
                //   đi theo hướng 'remove-vip' — kéo để bỏ chọn vùng VIP).
                seatBtn.addEventListener('pointerdown', (function (btn) {
                    return function (ev) {
                        if (ev.button !== undefined && ev.button !== 0) return;
                        dragging = true;
                        // Lưu trạng thái GỐC trước khi apply.
                        originalStates.set(btn, btn.classList.contains('is-vip'));
                        dragMode = originalStates.get(btn) ? 'remove-vip' : 'add-vip';
                        dragHandled = new Set();
                        ev.preventDefault();
                        try { btn.setPointerCapture(ev.pointerId); } catch (_) {}
                        // KHÔNG apply ngay tại đây — đợi pointerenter/pointerup để
                        //   biết đây là click đơn (toggle) hay kéo (set theo mode).
                        updateSummary();
                    };
                })(seatBtn));

                seatBtn.addEventListener('pointerenter', (function (btn) {
                    return function (ev) {
                        if (!dragging) return;
                        if (!originalStates.has(btn)) {
                            originalStates.set(btn, btn.classList.contains('is-vip'));
                        }
                        if (dragHandled.has(btn)) return;
                        dragHandled.add(btn);
                        applyDragMode(btn);
                        updateSummary();
                    };
                })(seatBtn));

                seatBtn.addEventListener('pointerup', (function (btn) {
                    return function () {
                        if (!dragging) return;
                        // Click đơn (dragHandled chỉ có 1 phần tử, đúng btn) → toggle.
                        // Kéo vùng (size > 1) → giữ nguyên mode đã apply.
                        if (dragHandled.size <= 1 && originalStates.has(btn)) {
                            var wasVip = originalStates.get(btn);
                            if (wasVip) btn.classList.remove('is-vip');
                            else btn.classList.add('is-vip');
                            updateSummary();
                        }
                        endDrag();
                    };
                })(seatBtn));

                // Khi chuột rời grid cũng kết thúc drag
                seatBtn.addEventListener('pointercancel', function () { endDrag(); });

                row.appendChild(seatBtn);
                rowCells[rowLabel].push(seatBtn);
            }
            grid.appendChild(row);
        }
        screen.appendChild(grid);
        scrollWrap.appendChild(screen);
        container.appendChild(scrollWrap);

        // Kết thúc kéo khi thả chuột ở bất cứ đâu (kể cả ngoài grid) — áp dụng
        // toggle cho click đơn vào bất kỳ ghế nào (kể cả khi up ngoài grid thì
        // đã có pointerup riêng trên ghế down xử lý rồi).
        document.addEventListener('pointerup', endDrag);
        document.addEventListener('pointercancel', endDrag);

        function applyDragMode(btn) {
            // Mode dựa trên trạng thái GỐC của ghế đó (đã lưu trong originalStates),
            //   không phải trạng thái hiện tại — để kéo qua vùng đã có sẵn VIP/standard
            //   vẫn cho ra kết quả đúng theo ý user (kéo từ 1 ghế chưa VIP sang
            //   1 vùng đã VIP thì cả vùng thành VIP).
            var wasVip = originalStates.get(btn);
            if (wasVip === undefined) {
                wasVip = btn.classList.contains('is-vip');
                originalStates.set(btn, wasVip);
            }
            if (wasVip) btn.classList.remove('is-vip');
            else btn.classList.add('is-vip');
        }

        function endDrag() {
            if (!dragging) return;
            dragging = false;
            dragMode = null;
            dragHandled = new Set();
            originalStates = new Map();
        }

        // Legend
        var legend = el('div', { class: 'seat-picker-legend' });
        legend.appendChild(el('div', { class: 'legend-item' }, [
            el('span', { class: 'legend-swatch seat-cell' }, []),
            el('span', {}, ['Ghế thường'])
        ]));
        legend.appendChild(el('div', { class: 'legend-item' }, [
            el('span', { class: 'legend-swatch seat-cell is-vip' }, []),
            el('span', {}, ['Ghế VIP (giá cao hơn)'])
        ]));
        legend.appendChild(modeBadge);
        container.appendChild(legend);

        // Summary bar
        var summary = el('div', { class: 'seat-picker-summary' });
        var summaryText = el('span', { class: 'seat-picker-text' }, []);
        var resetAllBtn = el('button', {
            type: 'button',
            class: 'ws-btn secondary small'
        }, ['Bỏ chọn tất cả VIP']);
        resetAllBtn.addEventListener('click', function () {
            container.querySelectorAll('.seat-cell.is-vip').forEach(function (b) {
                b.classList.remove('is-vip');
            });
            updateSummary();
        });
        summary.appendChild(summaryText);
        summary.appendChild(resetAllBtn);
        container.appendChild(summary);

        function updateSummary() {
            var vipButtons = container.querySelectorAll('.seat-cell.is-vip');
            var uniqueRows = new Set();
            vipButtons.forEach(function (b) { uniqueRows.add(b.getAttribute('data-row')); });
            var rows = Array.from(uniqueRows).sort();
            hiddenVip.value = rows.join(',');
            summaryText.textContent = vipButtons.length === 0
                ? 'Chưa chọn ghế VIP nào.'
                : 'Đã chọn ' + vipButtons.length + ' ghế VIP thuộc hàng: ' + (rows.join(', ') || '—');
            // Cập nhật mode badge cho thấy action sẽ xảy ra khi click/kéo
            if (dragging) {
                modeBadge.textContent = dragMode === 'add-vip'
                    ? 'Đang chọn: thành VIP'
                    : 'Đang chọn: thành ghế thường';
                modeBadge.className = 'seat-picker-mode-badge is-active ' +
                    (dragMode === 'add-vip' ? 'is-vip-mode' : 'is-standard-mode');
            } else {
                modeBadge.textContent = '';
                modeBadge.className = 'seat-picker-mode-badge';
            }
        }
        updateSummary();
    }

    function renderSeatLayoutPicker(container, hiddenLayout, rowCount, colCount, initialSeats) {
        var el = (hub && hub.el) || function (tag, attrs, children) {
            var node = document.createElement(tag);
            Object.keys(attrs || {}).forEach(function (key) {
                if (key === 'class') node.className = attrs[key];
                else node.setAttribute(key, attrs[key]);
            });
            (Array.isArray(children) ? children : children ? [children] : []).forEach(function (child) {
                node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
            });
            return node;
        };
        container.replaceChildren();
        var layout = new Map();
        (initialSeats || []).forEach(function (seat) {
            if ((!seat.status || seat.status === 'ACTIVE')
                    && seat.rowLabel.charCodeAt(0) - 65 < rowCount
                    && Number(seat.colNo) <= colCount) {
                layout.set(seat.rowLabel + ':' + seat.colNo, seat.seatType);
            }
        });
        var selectedTool = 'STANDARD';
        var dragging = false;
        var painted = new Set();
        var grid = el('div', { class: 'seat-grid seat-layout-grid' });
        var tools = el('div', { class: 'seat-layout-tools' });
        var summary = el('span', { class: 'seat-picker-hint', 'aria-live': 'polite' });

        function syncLayout() {
            hiddenLayout.value = JSON.stringify(Array.from(layout.entries()).map(function (entry) {
                var parts = entry[0].split(':');
                return { rowLabel: parts[0], colNo: Number(parts[1]), seatType: entry[1] };
            }));
            hiddenLayout.dispatchEvent(new Event('change', { bubbles: true }));
            var counts = { STANDARD: 0, VIP: 0, COUPLE: 0 };
            layout.forEach(function (type) { counts[type] = (counts[type] || 0) + 1; });
            summary.textContent = 'Tổng ' + layout.size + ' ghế · Thường ' + counts.STANDARD
                + ' · VIP ' + counts.VIP + ' · Đôi ' + counts.COUPLE;
        }

        [
            { type: 'STANDARD', label: 'Thêm ghế thường' },
            { type: 'VIP', label: 'Thêm ghế VIP' },
            { type: 'COUPLE', label: 'Thêm ghế đôi' },
            { type: 'REMOVE', label: 'Xóa ghế' }
        ].forEach(function (tool) {
            var button = el('button', { type: 'button', class: 'ws-btn secondary small' }, [tool.label]);
            button.setAttribute('aria-pressed', String(selectedTool === tool.type));
            button.addEventListener('click', function () {
                selectedTool = tool.type;
                tools.querySelectorAll('button').forEach(function (item) {
                    item.setAttribute('aria-pressed', String(item === button));
                });
            });
            tools.appendChild(button);
        });
        container.appendChild(tools);
        container.appendChild(el('p', { class: 'seat-picker-hint' },
            ['Chọn loại ghế hoặc Xóa ghế, rồi bấm/kéo trên ô để thêm hay xóa nhiều ghế. Ô trống sẽ không xuất hiện khi khách đặt vé.']));

        function paint(cell) {
            var key = cell.getAttribute('data-row') + ':' + cell.getAttribute('data-col');
            if (painted.has(key)) return;
            painted.add(key);
            if (selectedTool === 'REMOVE') layout.delete(key);
            else layout.set(key, selectedTool);
            var type = layout.get(key);
            cell.classList.toggle('is-empty', !type);
            cell.classList.toggle('is-vip', type === 'VIP');
            cell.classList.toggle('is-couple', type === 'COUPLE');
            cell.textContent = type ? cell.getAttribute('data-col') : '';
            cell.setAttribute('aria-label', cell.getAttribute('data-row') + cell.getAttribute('data-col')
                + (type ? ' ' + type : ' - không có ghế'));
            syncLayout();
        }

        for (var r = 0; r < rowCount; r++) {
            var rowLabel = String.fromCharCode(65 + r);
            var row = el('div', { class: 'seat-row' });
            row.appendChild(el('span', { class: 'seat-row-label seat-layout-row-label' }, [rowLabel]));
            for (var c = 1; c <= colCount; c++) {
                var key = rowLabel + ':' + c;
                var type = layout.get(key);
                var cell = el('button', {
                    type: 'button',
                    class: 'seat-cell seat-layout-cell' + (type ? '' : ' is-empty')
                        + (type === 'VIP' ? ' is-vip' : '')
                        + (type === 'COUPLE' ? ' is-couple' : ''),
                    'data-row': rowLabel,
                    'data-col': String(c),
                    'aria-label': rowLabel + c + (type ? ' ' + type : ' - không có ghế')
                }, [type ? String(c) : '']);
                cell.addEventListener('pointerdown', function (event) {
                    if (event.button !== undefined && event.button !== 0) return;
                    dragging = true;
                    painted = new Set();
                    paint(event.currentTarget);
                    document.addEventListener('pointerup', function endPaint() {
                        dragging = false;
                    }, { once: true });
                    document.addEventListener('pointercancel', function endPaint() {
                        dragging = false;
                    }, { once: true });
                    event.preventDefault();
                });
                cell.addEventListener('pointerenter', function (event) {
                    if (dragging) paint(event.currentTarget);
                });
                cell.addEventListener('click', function (event) {
                    if (event.detail === 0) {
                        painted = new Set();
                        paint(event.currentTarget);
                    }
                });
                row.appendChild(cell);
            }
            grid.appendChild(row);
        }
        container.appendChild(grid);
        container.appendChild(summary);
        syncLayout();
    }

    function renderScreenForm(screen, branches, preselectedBranchId) {
        var isEdit = !!screen;
        var fields = [
            isEdit ? null : { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true, value: preselectedBranchId, options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
            { name: 'code', label: 'Mã phòng', required: true, value: screen && screen.code, placeholder: 'VD: R01' },
            { name: 'name', label: 'Tên phòng', required: true, value: screen && screen.name, placeholder: 'Phòng chiếu 1' },
            { name: 'rowCount', label: 'Số hàng', type: 'number', required: true, min: 1, max: 26, value: (screen && screen.rowCount) || 8 },
            { name: 'colCount', label: 'Số cột', type: 'number', required: true, min: 1, max: 30, value: (screen && screen.colCount) || 12 }
        ].filter(Boolean);

        var initialRowCount = Number(((screen && screen.rowCount) || 8));
        var initialColCount = Number(((screen && screen.colCount) || 12));

        // Render form bằng renderFormPage, sau đó append seat picker theo row/col động.
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phòng chiếu', href: ctx + '/console?module=screen' }, isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa phòng chiếu' : 'Tạo phòng chiếu mới',
            subtitle: isEdit ? screen.name : 'Thêm phòng chiếu cho chi nhánh.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo phòng',
            sections: [{
                title: 'Thông tin phòng',
                fields: fields
            }],
            // Bỏ phần "vipRows" cũ ra khỏi sections, ta nhúng vào renderFooter bên dưới.
            renderFooter: function (formEl) {
                // Section sơ đồ ghế VIP
                var section = document.createElement('section');
                section.className = 'ws-form-section';
                var heading = document.createElement('h2');
                heading.className = 'ws-form-section-title';
                heading.textContent = 'Sơ đồ ghế';
                section.appendChild(heading);
                var desc = document.createElement('p');
                desc.className = 'ws-form-section-desc';
                desc.textContent = 'Cấu hình kích thước, sau đó thêm, xóa và phân loại ghế trực tiếp trên sơ đồ.';
                section.appendChild(desc);

                var seatHost = document.createElement('div');
                seatHost.id = 'seat-picker-host';
                seatHost.className = 'seat-picker-host';
                section.appendChild(seatHost);

                formEl.appendChild(section);

                var hidden = document.createElement('input');
                hidden.type = 'hidden';
                hidden.name = 'seatLayout';
                formEl.appendChild(hidden);

                var currentRows = initialRowCount;
                var currentCols = initialColCount;
                var initialSeats = screen && Array.isArray(screen.seats)
                    ? screen.seats.map(function (seat) { return Object.assign({}, seat); })
                    : [];
                if (!screen) {
                    for (var rowIndex = 0; rowIndex < initialRowCount; rowIndex++) {
                        var rowLabel = String.fromCharCode(65 + rowIndex);
                        for (var colNo = 1; colNo <= initialColCount; colNo++) {
                            initialSeats.push({
                                rowLabel: rowLabel,
                                colNo: colNo,
                                seatType: 'STANDARD',
                                status: 'ACTIVE'
                            });
                        }
                    }
                }
                var archivedSeats = initialSeats.filter(function (seat) {
                    return seat.status === 'INACTIVE';
                });
                var archivedPositions = new Set(archivedSeats.map(function (seat) {
                    return seat.rowLabel + ':' + seat.colNo;
                }));
                var archivedSeatSection = null;

                function syncSeatState() {
                    var desiredSeats;
                    try {
                        desiredSeats = JSON.parse(hidden.value || '[]');
                    } catch (_) {
                        throw new Error('Không đọc được sơ đồ ghế hiện tại.');
                    }
                    initialSeats = initialSeats.filter(function (seat) {
                        if (seat.status === 'INACTIVE') return true;
                        var rowIndex = seat.rowLabel.charCodeAt(0) - 65;
                        return rowIndex < 0 || rowIndex >= currentRows
                            || Number(seat.colNo) > currentCols;
                    });
                    desiredSeats.forEach(function (seat) {
                        initialSeats.push({
                            rowLabel: seat.rowLabel,
                            colNo: Number(seat.colNo),
                            seatType: seat.seatType,
                            status: 'ACTIVE'
                        });
                    });
                    updateArchivedSeatButtons();
                }

                function updateArchivedSeatButtons() {
                    if (!archivedSeatSection) return;
                    var desiredPositions = new Set();
                    try {
                        JSON.parse(hidden.value || '[]').forEach(function (seat) {
                            desiredPositions.add(seat.rowLabel + ':' + seat.colNo);
                        });
                    } catch (_) {
                        throw new Error('Không đọc được sơ đồ ghế hiện tại.');
                    }
                    archivedSeatSection.querySelectorAll('button[data-seat-position]').forEach(function (button) {
                        var position = button.getAttribute('data-seat-position');
                        var parts = position.split(':');
                        var rowIndex = parts[0].charCodeAt(0) - 65;
                        var inBounds = rowIndex >= 0 && rowIndex < currentRows
                            && Number(parts[1]) <= currentCols;
                        var restored = desiredPositions.has(position);
                        button.disabled = restored || !inBounds;
                        button.textContent = restored ? 'Đã chọn mở lại'
                            : (inBounds ? 'Mở lại ghế ' + parts[0] + parts[1] : 'Ngoài kích thước phòng');
                    });
                }

                function redrawSeatPicker() {
                    var rowInput = formEl.querySelector('input[name="rowCount"]');
                    var colInput = formEl.querySelector('input[name="colCount"]');
                    if (!rowInput || !colInput) return;
                    var nextRows = Math.max(1, Math.min(26, parseInt(rowInput.value, 10) || 1));
                    var nextCols = Math.max(1, Math.min(30, parseInt(colInput.value, 10) || 1));
                    if (nextRows > currentRows || nextCols > currentCols) {
                        var positions = new Set(initialSeats.map(function (seat) {
                            return seat.rowLabel + ':' + seat.colNo;
                        }));
                        for (var rowIndex = 0; rowIndex < nextRows; rowIndex++) {
                            var rowLabel = String.fromCharCode(65 + rowIndex);
                            for (var colNo = 1; colNo <= nextCols; colNo++) {
                                var position = rowLabel + ':' + colNo;
                                var addedByResize = rowIndex >= currentRows || colNo > currentCols;
                                if (addedByResize && !positions.has(position)
                                        && !archivedPositions.has(position)) {
                                    initialSeats.push({
                                        rowLabel: rowLabel,
                                        colNo: colNo,
                                        seatType: 'STANDARD',
                                        status: 'ACTIVE'
                                    });
                                    positions.add(position);
                                }
                            }
                        }
                    }
                    currentRows = nextRows;
                    currentCols = nextCols;
                    renderSeatLayoutPicker(seatHost, hidden, currentRows, currentCols, initialSeats);
                    updateArchivedSeatButtons();
                }

                if (archivedSeats.length) {
                    archivedSeatSection = document.createElement('div');
                    archivedSeatSection.className = 'seat-archived-list';
                    var archivedHeading = document.createElement('h3');
                    archivedHeading.textContent = 'Ghế đang lưu trữ';
                    archivedSeatSection.appendChild(archivedHeading);
                    var archivedHint = document.createElement('p');
                    archivedHint.textContent = 'Chọn ghế cần mở lại, sau đó lưu sơ đồ phòng.';
                    archivedSeatSection.appendChild(archivedHint);
                    archivedSeats.forEach(function (seat) {
                        var position = seat.rowLabel + ':' + seat.colNo;
                        var button = document.createElement('button');
                        button.type = 'button';
                        button.className = 'ws-btn secondary small';
                        button.setAttribute('data-seat-position', position);
                        button.textContent = 'Mở lại ghế ' + seat.rowLabel + seat.colNo;
                        button.addEventListener('click', function () {
                            var desiredSeats;
                            try {
                                desiredSeats = JSON.parse(hidden.value || '[]');
                            } catch (_) {
                                throw new Error('Không đọc được sơ đồ ghế hiện tại.');
                            }
                            if (desiredSeats.some(function (item) {
                                return item.rowLabel + ':' + item.colNo === position;
                            })) return;
                            desiredSeats.push({
                                rowLabel: seat.rowLabel,
                                colNo: seat.colNo,
                                seatType: seat.seatType,
                                status: 'ACTIVE'
                            });
                            hidden.value = JSON.stringify(desiredSeats);
                            hidden.dispatchEvent(new Event('change', { bubbles: true }));
                            redrawSeatPicker();
                        });
                        archivedSeatSection.appendChild(button);
                    });
                    section.appendChild(archivedSeatSection);
                }
                hidden.addEventListener('change', syncSeatState);

                setTimeout(redrawSeatPicker, 0);
                var rowInput = formEl.querySelector('input[name="rowCount"]');
                var colInput = formEl.querySelector('input[name="colCount"]');
                if (rowInput) rowInput.addEventListener('change', redrawSeatPicker);
                if (colInput) colInput.addEventListener('change', redrawSeatPicker);
            },
            onSubmit: function (data) {
                var url = isEdit ? '/screen/' + screen.id : '/screen';
                var method = isEdit ? 'PUT' : 'POST';
                hub.api(url, { method: method, body: data })
                    .then(function () {
                        var branchId = isEdit ? screen.branchId : data.branchId;
                        window.location.href = ctx + '/console?module=screen&branchId='
                            + encodeURIComponent(branchId);
                    })
                    .catch(notifyError);
            },
            onCancel: function () { router.go('screen'); }
        });
    }

    router.register('screen', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            hub.api(branchListApiPath(roleMeta)).then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId')
                    || (roleMeta === 'ADMIN' ? '' : (branches[0] && branches[0].id));
                list.render({
                    title: 'Phòng chiếu & sơ đồ ghế',
                    subtitle: 'Quản lý phòng chiếu và sơ đồ ghế cho từng chi nhánh.',
                    addUrl: function () {
                        var selectedBranch = document.querySelector('#viewRoot select[name="branchId"]');
                        var selectedBranchId = selectedBranch ? selectedBranch.value : '';
                        return ctx + '/console?module=screen&action=create'
                            + (selectedBranchId
                                ? '&branchId=' + encodeURIComponent(selectedBranchId) : '');
                    },
                    addLabel: 'Tạo phòng chiếu',
                    pageSize: 20,
                    filters: [{
                        name: 'branchId', label: 'Chi nhánh',
                        initialValue: branchId ? String(branchId) : '',
                        options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; })
                    }],
                    fetcher: function (qs) {
                        var selectedBranchId = qs.get('branchId');
                        var url = selectedBranchId
                            ? '/screen?branchId=' + encodeURIComponent(selectedBranchId)
                            : '/screen';
                        return hub.api(url).then(function (result) {
                            var branchNames = new Map((branches || []).map(function (branch) {
                                return [String(branch.id), branch.name];
                            }));
                            var screens = Array.isArray(result) ? result : [];
                            screens.forEach(function (screen) {
                                screen.branchName = branchNames.get(String(screen.branchId)) || '—';
                            });
                            return { items: screens, total: screens.length };
                        });
                    },
                    emptyTitle: 'Chưa có phòng chiếu',
                    emptyMessage: 'Chi nhánh này chưa có phòng chiếu nào.',
                    columns: (roleMeta === 'ADMIN' ? [{ label: 'Chi nhánh', key: 'branchName' }] : []).concat([
                        { label: 'Mã', key: 'code' },
                        { label: 'Tên', key: 'name' },
                        { label: 'Số hàng', key: 'rowCount' },
                        { label: 'Số cột', key: 'colCount' },
                        { label: 'Kích thước', render: function (s) { return s.rowCount + ' × ' + s.colCount; } },
                        { label: 'Trạng thái', render: function (s) { return statusBadge(s.status); } }
                    ]),
                    actions: function (row) {
                        var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=screen&action=edit&id=' + row.id }];
                        if (row.status === 'ACTIVE') {
                            arr.push({ label: 'Ngưng', class: 'danger', onClick: function () {
                                confirmAction('Ngưng hoạt động phòng chiếu?', function () {
                                    hub.api('/screen/' + row.id, { method: 'PUT', body: { action: 'deactivate' } })
                                        .then(function () {
                                            hub.notify('Đã ngưng phòng chiếu.', 'ok');
                                            router.go('screen');
                                        })
                                        .catch(notifyError);
                                });
                            }});
                        }
                        return arr;
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            hub.api(branchListApiPath(roleMeta)).then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId') || (branches[0] && branches[0].id);
                renderScreenForm(null, branches, branchId);
            }).catch(notifyError);
        },
        edit: function (id) {
            var branches = null;
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            hub.api(branchListApiPath(roleMeta))
                .then(function (b) {
                    branches = Array.isArray(b) ? b : [];
                    if (!branches.length) throw new Error('Chưa có chi nhánh để tải phòng chiếu');
                    return Promise.all(branches.map(function (branch) {
                        return hub.api('/screen?branchId=' + encodeURIComponent(branch.id))
                            .then(function (screens) {
                                return Array.isArray(screens) ? screens : [];
                            });
                    }));
                })
                .then(function (screenLists) {
                    var screens = [];
                    screenLists.forEach(function (items) {
                        screens = screens.concat(items);
                    });
                    var screen = screens.find(function (s) { return String(s.id) === String(id); });
                    if (!screen) throw new Error('Không tìm thấy phòng chiếu');
                    return hub.api('/screen/' + screen.id + '/seats').then(function (seats) {
                        screen.seats = Array.isArray(seats) ? seats : [];
                        renderScreenForm(screen, branches, screen.branchId);
                    });
                })
                .catch(notifyError);
        }
    });
    // ================================================================
    //  PROFILE (Customer account)
    // ================================================================
    router.register('profile', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            var header = hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Hồ sơ cá nhân']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý thông tin tài khoản, hạng thành viên và số dư ví.'])
            ]);
            var content = hub.el('div', { class: 'ws-card' });
            content.appendChild(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
            page.appendChild(header);
            page.appendChild(content);
            viewRoot.replaceChildren(page);

            hub.api('/api/profile').then(function (data) {
                var user = data && data.user ? data.user : {};
                var membership = data && data.profile ? data.profile : {};
                var wallet = data && data.wallet ? data.wallet : {};
                var form = hub.el('form', { class: 'ws-form' });
                var fields = hub.el('div', { class: 'ws-form-grid' });
                var fullName = hub.el('input', {
                    type: 'text', name: 'fullName', required: 'required',
                    value: user.fullName || '', maxlength: '120'
                });
                var phone = hub.el('input', {
                    type: 'tel', name: 'phone', value: user.phone || '', maxlength: '30'
                });
                fields.appendChild(hub.el('label', {}, ['Họ và tên', fullName]));
                fields.appendChild(hub.el('label', {}, ['Số điện thoại', phone]));
                fields.appendChild(hub.el('label', {}, ['Email', hub.el('input', {
                    type: 'email', value: user.email || '', disabled: 'disabled'
                })]));
                fields.appendChild(hub.el('label', {}, ['Vai trò', hub.el('input', {
                    type: 'text', value: user.role || 'CUSTOMER', disabled: 'disabled'
                })]));
                form.appendChild(fields);
                form.appendChild(hub.el('div', { class: 'ws-form-actions' }, [
                    hub.el('button', { class: 'ws-btn primary', type: 'submit' }, ['Lưu thay đổi'])
                ]));

                var summary = hub.el('div', { class: 'kpi-grid', style: 'margin-top:20px' }, [
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Hạng thành viên']),
                        hub.el('strong', {}, [String(membership.tier || 'STANDARD')])
                    ]),
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Điểm tích lũy']),
                        hub.el('strong', {}, [String(membership.points || 0)])
                    ]),
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Số dư ví']),
                        hub.el('strong', {}, [hub.fmtMoney(wallet.balance || 0)])
                    ])
                ]);
                form.addEventListener('submit', function (event) {
                    event.preventDefault();
                    var submit = form.querySelector('button[type="submit"]');
                    submit.disabled = true;
                    hub.api('/api/profile', {
                        method: 'POST',
                        body: { fullName: fullName.value.trim(), phone: phone.value.trim() }
                    }).then(function (updated) {
                        var updatedUser = updated && updated.user ? updated.user : {};
                        fullName.value = updatedUser.fullName || fullName.value;
                        phone.value = updatedUser.phone || '';
                        hub.notify('Đã cập nhật hồ sơ.', 'ok');
                    }).catch(notifyError).then(function () {
                        submit.disabled = false;
                    });
                });
                content.replaceChildren(form, summary);
            }).catch(function (error) {
                content.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không tải được hồ sơ']),
                    hub.el('p', {}, [error.message || 'Vui lòng thử lại.'])
                ]));
            });
        }
    });

    function branchListApiPath(roleMeta) {
        return '/branch/';
    }
    // Helper hiển thị thông báo lỗi validate trực tiếp dưới từng trường dữ liệu
    function setFieldError(fieldEl, message) {
        if (!fieldEl) return;
        clearFieldError(fieldEl);
        fieldEl.style.borderColor = '#dc2626';
        fieldEl.style.boxShadow = '0 0 0 1px #dc2626';
        var err = hub.el('div', {
            class: 'ws-field-error-msg',
            style: 'color: #dc2626; font-size: 0.82rem; font-weight: 500; margin-top: 5px; display: block;'
        }, [message]);
        if (fieldEl.parentNode) {
            fieldEl.parentNode.appendChild(err);
        }
        var onInput = function () {
            clearFieldError(fieldEl);
            fieldEl.removeEventListener('input', onInput);
            fieldEl.removeEventListener('change', onInput);
        };
        fieldEl.addEventListener('input', onInput);
        fieldEl.addEventListener('change', onInput);
    }

    function clearFieldError(fieldEl) {
        if (!fieldEl) return;
        fieldEl.style.borderColor = '';
        fieldEl.style.boxShadow = '';
        if (fieldEl.parentNode) {
            var existing = fieldEl.parentNode.querySelector('.ws-field-error-msg');
            if (existing) existing.remove();
        }
    }

    // Không render tab bar 3 nút trên đầu trang nữa vì sidebar đã có các menu con riêng biệt
    function createInventoryTabBar(activeTab, onSwitch) {
        return null;
    }

    function formatMaterialUnit(u) {
        if (!u) return '—';
        var str = String(u).trim();
        var lower = str.toLowerCase();
        if (lower === 'kg') return 'Kg';
        if (lower === 'g' || lower === 'gram') return 'Gram';
        if (lower === 'chai') return 'Chai';
        if (lower === 'cai' || lower === 'cái') return 'Cái';
        if (lower === 'hop' || lower === 'hộp') return 'Hộp';
        if (lower === 'ly') return 'Ly';
        if (lower === 'lon') return 'Lon';
        if (lower === 'tui' || lower === 'túi') return 'Túi';
        if (lower === 'l' || lower === 'lit' || lower === 'lít') return 'Lít';
        if (lower === 'ml') return 'ml';
        return str.charAt(0).toUpperCase() + str.slice(1);
    }

    function formatMaterialCategory(c) {
        if (!c) return '—';
        var str = String(c).trim();
        var upper = str.toUpperCase();
        var map = {
            'BAP': 'Bắp hạt',
            'BẮP': 'Bắp hạt',
            'DO UONG': 'Đồ uống',
            'ĐỒ UỐNG': 'Đồ uống',
            'BAO BI': 'Bao bì & Ly',
            'BAO BÌ': 'Bao bì & Ly',
            'GIA VI': 'Gia vị & Bơ',
            'GIA VỊ': 'Gia vị & Bơ'
        };
        if (map[upper]) return map[upper];
        return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
    }

    function formatInventoryReason(reason) {
        if (!reason) return '—';
        var str = String(reason).trim();
        var map = {
            'NHAP_HANG_TU_NCC': 'Nhập hàng từ nhà cung cấp',
            'NHAP_LAI_TU_NCC': 'Nhập đổi trả từ NCC',
            'NHAP_KHO': 'Nhập hàng từ nhà cung cấp',
            'RESTOCK': 'Nhập hàng từ nhà cung cấp',
            'KIEM_KE_THUA': 'Kiểm kê thừa (Thực tế nhiều hơn)',
            'KIEM_KE_THIEU': 'Kiểm kê thiếu (Hụt kho)',
            'HU_HONG': 'Hư hỏng / Rách bao bì',
            'HET_HAN': 'Hết hạn sử dụng',
            'MAT_DO_KHACH': 'Thất thoát / Đổ vỡ',
            'XUAT_CHE_BIEN': 'Xuất kho chế biến bắp nước',
            'NHAP_TU_CHE_BIEN': 'Nhập từ chế biến bắp nước',
            'CHE_BIEN': 'Xuất kho chế biến bắp nước',
            'PRODUCE': 'Xuất kho chế biến bắp nước',
            'XUAT_BAN': 'Xuất bán tại quầy',
            'BAN_HANG': 'Xuất bán tại quầy',
            'DON_HANG': 'Xuất bán theo đơn',
            'HOAN_TU_DON_HUY': 'Hoàn từ đơn hủy',
            'DIEU_CHINH_KHAC': 'Điều chỉnh khác',
            'OTHER': 'Lý do khác',
            'ADJUST': 'Điều chỉnh kho'
        };

        var upper = str.toUpperCase();
        if (map[upper]) return map[upper];

        // Nếu có dạng "CODE - Chi tiết..."
        var dashIdx = str.indexOf(' - ');
        if (dashIdx > 0) {
            var code = str.substring(0, dashIdx).trim().toUpperCase();
            var detail = str.substring(dashIdx + 3).trim();
            var label = map[code] || code;
            return detail ? (label + ' — ' + detail) : label;
        }

        return str;
    }

    // ================================================================
    //  PHẦN 1: GIAI ĐOẠN 1 - KHO THÀNH PHẨM (BẮP NƯỚC BÁN LẺ)
    // ================================================================

    // 1. Modal xem chi tiết sản phẩm và lịch sử biến động kho
    function showProductDetailModal(branchId, product) {
        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'dialog',
            'aria-modal': 'true',
            style: 'max-width: 720px; width: 100%; border-radius: 12px; overflow: hidden; max-height: 90vh; display: flex; flex-direction: column; padding: 0; box-shadow: 0 20px 40px rgba(0,0,0,0.25);'
        });

        var closeBtn = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng',
            style: 'color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;'
        }, ['×']);

        var head = hub.el('div', {
            style: 'background: #1d4ed8; color: #ffffff; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;'
        }, [
            hub.el('div', {}, [
                hub.el('h3', { style: 'margin: 0; font-size: 1.15rem; font-weight: 700; color: #fff;' }, ['Chi tiết tồn kho & Lịch sử biến động']),
                hub.el('small', { style: 'font-size: 0.82rem; color: #dbeafe; display: block; margin-top: 2px;' }, ['Lịch sử biến động tồn kho sản phẩm'])
            ]),
            closeBtn
        ]);

        var body = hub.el('div', { style: 'padding: 20px; background: #fff; overflow-y: auto; flex: 1;' });

        // Thông tin sản phẩm
        var pId = product.productId || product.id;
        var qty = product.qty != null ? product.qty : 0;
        var unit = product.unit || '';
        var updatedText = product.updatedAt || '—';
        var statusBadgeClass = qty <= 0 ? 'ws-badge-red' : (qty < 10 ? 'ws-badge-orange' : 'ws-badge-green');
        var statusText = qty <= 0 ? 'Hết hàng' : (qty < 10 ? ('Sắp hết (' + qty + ')') : 'Còn hàng');

        var infoCard = hub.el('div', {
            style: 'display: flex; gap: 16px; align-items: center; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px 18px; margin-bottom: 20px;'
        }, [
            hub.el('div', {
                style: 'width: 48px; height: 48px; border-radius: 10px; background: #eff6ff; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700; color: #1d4ed8;'
            }, ['SP']),
            hub.el('div', { style: 'flex: 1;' }, [
                hub.el('div', { style: 'display: flex; align-items: center; gap: 8px;' }, [
                    hub.el('h4', { style: 'margin: 0; font-size: 1.05rem; font-weight: 700; color: #0f172a;' }, [product.name || ('Sản phẩm #' + pId)]),
                    hub.el('span', { class: 'ws-badge ws-status-pill ' + statusBadgeClass }, [statusText])
                ]),
                hub.el('div', { style: 'margin-top: 6px; font-size: 0.86rem; color: #64748b; display: flex; flex-wrap: wrap; gap: 16px;' }, [
                    hub.el('span', {}, ['Loại: ', hub.el('strong', { style: 'color: #334155;' }, [product.type || '—'])]),
                    hub.el('span', {}, ['Tồn hiện tại: ', hub.el('strong', { style: 'color: #0f172a;' }, [qty + ' ' + unit])]),
                    hub.el('span', {}, ['Cập nhật: ', hub.el('span', { style: 'color: #334155;' }, [updatedText])])
                ])
            ])
        ]);
        body.appendChild(infoCard);

        // Bảng Lịch sử biến động
        body.appendChild(hub.el('h5', { style: 'margin: 0 0 10px; font-size: 0.95rem; font-weight: 700; color: #1e293b;' }, ['Lịch sử biến động']));

        var tableWrap = hub.el('div', { style: 'border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden;' });
        var historyLoading = hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-text-muted); margin: 0;' }, ['Đang tải lịch sử…']);
        tableWrap.appendChild(historyLoading);
        body.appendChild(tableWrap);

        var paginationWrap = hub.el('div', { style: 'display: flex; justify-content: flex-end; align-items: center; gap: 6px; margin-top: 12px;' });
        body.appendChild(paginationWrap);

        // Sticky Footer
        var closeFoot = hub.el('button', {
            class: 'ws-btn secondary',
            type: 'button',
            style: 'width: 100%; border-radius: 8px; font-weight: 600;'
        }, ['Đóng']);
        var footWrap = hub.el('div', {
            style: 'padding: 14px 20px; background: #f8fafc; border-top: 1px solid #e2e8f0; flex-shrink: 0;'
        }, [closeFoot]);

        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footWrap);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function close() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }
        backdrop.addEventListener('click', close);
        closeBtn.addEventListener('click', close);
        closeFoot.addEventListener('click', close);

        // Tải lịch sử biến động từ API
        hub.api('/inventory?action=history&branchId=' + branchId + '&productId=' + pId)
            .then(function (res) {
                var rawList = (res && res.data) || res || [];
                if (!Array.isArray(rawList) || rawList.length === 0) {
                    tableWrap.replaceChildren(hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-text-muted); margin: 0;' }, ['Chưa có lịch sử biến động nào cho sản phẩm này.']));
                    return;
                }

                var pageSize = 5;
                var curPage = 0;
                var totalPages = Math.ceil(rawList.length / pageSize);

                function renderHistoryTable() {
                    var table = hub.el('table', { class: 'ws-table', style: 'margin: 0; width: 100%;' });
                    var thead = hub.el('thead', {}, [
                        hub.el('tr', {}, [
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Ngày giờ']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Tăng/giảm']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Số lượng']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Lý do']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Người thực hiện'])
                        ])
                    ]);
                    var tbody = hub.el('tbody');
                    var start = curPage * pageSize;
                    var pageItems = rawList.slice(start, start + pageSize);

                    pageItems.forEach(function (h) {
                        var isPlus = h.deltaQty > 0;
                        var sign = isPlus ? '+' : '-';
                        var signColor = isPlus ? '#16a34a' : '#dc2626';
                        var absQty = Math.abs(h.deltaQty);

                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', { style: 'font-size: 0.82rem; color: #64748b; white-space: nowrap;' }, [h.createdAt || '—']),
                            hub.el('td', {}, [
                                hub.el('span', {
                                    style: 'display: inline-block; padding: 2px 8px; border-radius: 4px; font-weight: 700; font-size: 0.85rem; color: #fff; background: ' + signColor + ';'
                                }, [sign])
                            ]),
                            hub.el('td', { style: 'font-weight: 600; color: #0f172a;' }, [absQty + ' ' + unit]),
                            hub.el('td', { style: 'font-size: 0.85rem;' }, [formatInventoryReason(h.reason)]),
                            hub.el('td', { style: 'font-size: 0.85rem; font-weight: 600;' }, [h.actorName || ('ID: ' + h.actorId)])
                        ]));
                    });

                    table.appendChild(thead);
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);

                    paginationWrap.replaceChildren();
                    if (totalPages > 1) {
                        var prevBtn = hub.el('button', {
                            class: 'ws-pagination-btn',
                            type: 'button',
                            disabled: curPage === 0
                        }, ['<']);
                        prevBtn.addEventListener('click', function () {
                            if (curPage > 0) { curPage--; renderHistoryTable(); }
                        });
                        paginationWrap.appendChild(prevBtn);

                        for (var i = 0; i < totalPages; i++) {
                            (function (pIndex) {
                                var pBtn = hub.el('button', {
                                    class: 'ws-pagination-btn' + (pIndex === curPage ? ' active' : ''),
                                    type: 'button'
                                }, [String(pIndex + 1)]);
                                pBtn.addEventListener('click', function () {
                                    curPage = pIndex;
                                    renderHistoryTable();
                                });
                                paginationWrap.appendChild(pBtn);
                            })(i);
                        }

                        var nextBtn = hub.el('button', {
                            class: 'ws-pagination-btn',
                            type: 'button',
                            disabled: curPage >= totalPages - 1
                        }, ['>']);
                        nextBtn.addEventListener('click', function () {
                            if (curPage < totalPages - 1) { curPage++; renderHistoryTable(); }
                        });
                        paginationWrap.appendChild(nextBtn);
                    }
                }

                renderHistoryTable();
            })
            .catch(function (err) {
                tableWrap.replaceChildren(hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-danger); margin: 0;' }, ['Không thể tải lịch sử: ' + (err.message || err)]));
            });
    }

    var showDetailModal = showProductDetailModal;
    var showInventoryHistoryModal = showProductDetailModal;

    // 2. Modal nhập kho thành phẩm mới
    function showProductRestockModal(branchId, stockItems, onSuccess) {
        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'dialog',
            'aria-modal': 'true',
            style: 'max-width: 480px; width: 100%; border-radius: 12px; overflow: hidden; max-height: 90vh; display: flex; flex-direction: column; padding: 0; box-shadow: 0 20px 40px rgba(0,0,0,0.25);'
        });

        var closeBtn = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng',
            style: 'color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;'
        }, ['×']);

        var head = hub.el('div', {
            style: 'background: #1d4ed8; color: #ffffff; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;'
        }, [
            hub.el('div', {}, [
                hub.el('h3', { style: 'margin: 0; font-size: 1.15rem; font-weight: 700; color: #fff;' }, ['Nhập kho thành phẩm']),
                hub.el('small', { style: 'font-size: 0.82rem; color: #dbeafe; display: block; margin-top: 2px;' }, ['Tăng số lượng tồn kho theo chi nhánh'])
            ]),
            closeBtn
        ]);

        var body = hub.el('div', { style: 'padding: 20px; background: #fff; overflow-y: auto; flex: 1;' });
        body.appendChild(hub.el('h4', { style: 'margin: 0 0 16px; font-size: 1.05rem; font-weight: 700; color: #0f172a;' }, ['Thông tin nhập kho']));

        // Sản phẩm * (chỉ load sản phẩm ACTIVE)
        var productGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        productGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Sản phẩm ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var productSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        productSelect.appendChild(hub.el('option', { value: '' }, ['Chọn sản phẩm…']));

        (stockItems || []).filter(function (it) {
            return !it.productStatus || it.productStatus.toUpperCase() === 'ACTIVE' || it.status === 'ACTIVE';
        }).forEach(function (it) {
            var pId = it.productId || it.id;
            productSelect.appendChild(hub.el('option', { value: String(pId) }, [it.name + ' (' + (it.type || '') + ')']));
        });
        productGroup.appendChild(productSelect);
        body.appendChild(productGroup);

        // Số lượng nhập * (> 0)
        var qtyGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        qtyGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Số lượng nhập ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var qtyInput = hub.el('input', {
            type: 'number',
            min: '1',
            placeholder: 'Nhập số lượng (> 0)',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        qtyGroup.appendChild(qtyInput);
        body.appendChild(qtyGroup);

        // Lý do * (Bắt buộc)
        var reasonGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        reasonGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Lý do ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var reasonSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        [
            { value: '', label: 'Chọn lý do…' },
            { value: 'NHAP_HANG', label: 'Nhập hàng từ NCC' },
            { value: 'NHAP_LAI_TU_NCC', label: 'Nhập lại từ NCC' },
            { value: 'KIEM_KE_THUA', label: 'Kiểm kê thừa' },
            { value: 'HOAN_TU_DON_HUY', label: 'Hoàn từ đơn hủy' },
            { value: 'DIEU_CHINH_KHAC', label: 'Điều chỉnh khác' }
        ].forEach(function (opt) {
            reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
        });
        reasonGroup.appendChild(reasonSelect);
        body.appendChild(reasonGroup);

        // Ghi chú
        var noteGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        noteGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, ['Ghi chú']));
        var noteInput = hub.el('input', {
            type: 'text',
            placeholder: 'Nhập ghi chú thêm (nếu có)…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        noteGroup.appendChild(noteInput);
        body.appendChild(noteGroup);

        // Sticky Footer
        var cancelBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: 1px solid #cbd5e1; background: #e2e8f0; color: #334155; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Hủy']);

        var confirmBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: none; background: #1d4ed8; color: #ffffff; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Xác nhận']);

        var footer = hub.el('div', {
            style: 'display: flex; gap: 12px; padding: 14px 20px; background: #f8fafc; border-top: 1px solid #e2e8f0; flex-shrink: 0;'
        }, [cancelBtn, confirmBtn]);

        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footer);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function closeModal() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }
        closeBtn.addEventListener('click', closeModal);
        backdrop.addEventListener('click', closeModal);
        cancelBtn.addEventListener('click', closeModal);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(productSelect);
            clearFieldError(qtyInput);
            clearFieldError(reasonSelect);

            var hasError = false;
            var firstErrField = null;

            var pId = productSelect.value;
            if (!pId) {
                setFieldError(productSelect, 'Vui lòng chọn sản phẩm.');
                hasError = true;
                if (!firstErrField) firstErrField = productSelect;
            }

            var qtyRaw = (qtyInput.value || '').trim();
            var qtyVal = parseInt(qtyRaw, 10);
            if (!qtyRaw) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng.');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            } else if (isNaN(qtyVal) || qtyVal <= 0) {
                setFieldError(qtyInput, 'Số lượng nhập phải lớn hơn 0.');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            }

            var rVal = reasonSelect.value;
            if (!rVal) {
                setFieldError(reasonSelect, 'Vui lòng chọn lý do nhập kho.');
                hasError = true;
                if (!firstErrField) firstErrField = reasonSelect;
            }

            if (hasError) {
                if (firstErrField) firstErrField.focus();
                return;
            }

            var rLabel = reasonSelect.options[reasonSelect.selectedIndex].text;
            var note = (noteInput.value || '').trim();
            var fullReason = rLabel + (note ? (' - ' + note) : '');

            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Đang lưu…';

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    action: 'restock',
                    branchId: branchId,
                    productId: pId,
                    quantity: qtyVal,
                    reason: fullReason
                }
            }).then(function () {
                closeModal();
                hub.notify('Nhập kho thành công!', 'ok');
                if (onSuccess) onSuccess();
            }).catch(function (err) {
                confirmBtn.disabled = false;
                confirmBtn.textContent = 'Xác nhận';
                notifyError(err);
            });
        });
    }

    var showRestockModal = showProductRestockModal;

    // 3. Modal điều chỉnh tồn kho
    function showProductAdjustModal(branchId, selectedProduct, stockItems, onSuccess) {
        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'dialog',
            'aria-modal': 'true',
            style: 'max-width: 460px; width: 100%; border-radius: 12px; overflow: hidden; max-height: 90vh; display: flex; flex-direction: column; padding: 0; box-shadow: 0 20px 40px rgba(0,0,0,0.25);'
        });

        var closeBtn = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng',
            style: 'color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;'
        }, ['×']);

        var head = hub.el('div', {
            style: 'background: #1d4ed8; color: #ffffff; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;'
        }, [
            hub.el('div', {}, [
                hub.el('h3', { style: 'margin: 0; font-size: 1.15rem; font-weight: 700; color: #fff;' }, ['Điều chỉnh tồn kho']),
                hub.el('small', { style: 'font-size: 0.82rem; color: #dbeafe; display: block; margin-top: 2px;' }, ['Tăng/giảm số lượng tồn kho theo chi nhánh'])
            ]),
            closeBtn
        ]);

        var body = hub.el('div', { style: 'padding: 20px; background: #fff; overflow-y: auto; flex: 1;' });
        body.appendChild(hub.el('h4', { style: 'margin: 0 0 16px; font-size: 1.05rem; font-weight: 700; color: #0f172a;' }, ['Điều chỉnh kho']));

        // Sản phẩm *
        var productGroup = hub.el('div', { style: 'margin-bottom: 12px;' });
        productGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Sản phẩm ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var productSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        productSelect.appendChild(hub.el('option', { value: '' }, ['Chọn sản phẩm…']));
        (stockItems || []).forEach(function (it) {
            var pId = it.productId || it.id;
            var opt = hub.el('option', { value: String(pId) }, [it.name + ' (' + (it.qty || 0) + ' ' + (it.unit || '') + ')']);
            productSelect.appendChild(opt);
        });
        if (selectedProduct && (selectedProduct.productId || selectedProduct.id)) {
            productSelect.value = String(selectedProduct.productId || selectedProduct.id);
        }
        productGroup.appendChild(productSelect);
        body.appendChild(productGroup);

        // Tồn hiện tại
        var currentQtyDisplay = hub.el('strong', { id: 'm4CurrentQtyText', style: 'font-size: 1.05rem; color: #0f172a;' }, ['0']);
        var currentQtyWrap = hub.el('div', { style: 'margin-bottom: 16px; font-size: 0.9rem; color: #475569;' }, [
            'Tồn hiện tại: ', currentQtyDisplay
        ]);
        body.appendChild(currentQtyWrap);

        function updateCurrentQty() {
            var selectedId = productSelect.value;
            var found = (stockItems || []).find(function (it) { return String(it.productId || it.id) === selectedId; });
            var q = found ? (found.qty || 0) : 0;
            currentQtyDisplay.textContent = String(q);
        }
        productSelect.addEventListener('change', updateCurrentQty);
        updateCurrentQty();

        // Loại điều chỉnh *
        var typeGroup = hub.el('div', { style: 'margin-bottom: 16px;' });
        typeGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 8px;' }, [
            'Loại điều chỉnh ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var radioPlus = hub.el('input', { type: 'radio', name: 'm4AdjustType', value: 'PLUS', checked: true, style: 'accent-color: #16a34a; width: 17px; height: 17px; cursor: pointer;' });
        var radioMinus = hub.el('input', { type: 'radio', name: 'm4AdjustType', value: 'MINUS', style: 'accent-color: #dc2626; width: 17px; height: 17px; cursor: pointer;' });
        var typeOptions = hub.el('div', { style: 'display: flex; gap: 24px; align-items: center;' }, [
            hub.el('label', { style: 'display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #16a34a; font-size: 0.95rem;' }, [
                radioPlus, 'Tăng'
            ]),
            hub.el('label', { style: 'display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #dc2626; font-size: 0.95rem;' }, [
                radioMinus, 'Giảm'
            ])
        ]);
        typeGroup.appendChild(typeOptions);
        body.appendChild(typeGroup);

        // Số lượng *
        var qtyGroup = hub.el('div', { style: 'margin-bottom: 16px;' });
        qtyGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Số lượng ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var qtyInput = hub.el('input', {
            type: 'number',
            min: '1',
            placeholder: 'Nhập số lượng (> 0)',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        qtyGroup.appendChild(qtyInput);
        body.appendChild(qtyGroup);

        // Lý do *
        var reasonGroup = hub.el('div', { style: 'margin-bottom: 16px;' });
        reasonGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Lý do ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var reasonSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });

        var reasonsDecrease = [
            { value: '', label: 'Chọn lý do…' },
            { value: 'HU_HONG', label: 'Hư hỏng / Rách vỡ' },
            { value: 'HET_HAN', label: 'Hết hạn sử dụng' },
            { value: 'MAT_DO_KHACH', label: 'Thất thoát / Mất do khách' },
            { value: 'KIEM_KE_THIEU', label: 'Kiểm kê thiếu' },
            { value: 'KHAC', label: 'Khác' }
        ];

        var reasonsIncrease = [
            { value: '', label: 'Chọn lý do…' },
            { value: 'KIEM_KE_THUA', label: 'Kiểm kê thừa' },
            { value: 'DIEU_CHINH_KHAC', label: 'Điều chỉnh khác' },
            { value: 'KHAC', label: 'Khác' }
        ];

        function updateReasonOptions() {
            var isMinus = radioMinus.checked;
            var opts = isMinus ? reasonsDecrease : reasonsIncrease;
            reasonSelect.replaceChildren();
            opts.forEach(function (opt) {
                reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
            });
        }
        radioPlus.addEventListener('change', updateReasonOptions);
        radioMinus.addEventListener('change', updateReasonOptions);
        updateReasonOptions();

        reasonGroup.appendChild(reasonSelect);
        body.appendChild(reasonGroup);

        // Ghi chú
        var noteGroup = hub.el('div', { style: 'margin-bottom: 6px;' });
        noteGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, ['Ghi chú']));
        var noteInput = hub.el('input', {
            type: 'text',
            placeholder: 'Nhập ghi chú (nếu có)…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        noteGroup.appendChild(noteInput);
        body.appendChild(noteGroup);

        // Sticky Footer
        var cancelBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: 1px solid #cbd5e1; background: #e2e8f0; color: #334155; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Hủy']);

        var confirmBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: none; background: #1d4ed8; color: #ffffff; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Xác nhận']);

        var footer = hub.el('div', {
            style: 'display: flex; gap: 12px; padding: 14px 20px; background: #f8fafc; border-top: 1px solid #e2e8f0; flex-shrink: 0;'
        }, [cancelBtn, confirmBtn]);

        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footer);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function closeModal() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }
        closeBtn.addEventListener('click', closeModal);
        backdrop.addEventListener('click', closeModal);
        cancelBtn.addEventListener('click', closeModal);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(productSelect);
            clearFieldError(qtyInput);
            clearFieldError(reasonSelect);

            var hasError = false;
            var firstErrField = null;

            var pId = productSelect.value;
            if (!pId) {
                setFieldError(productSelect, 'Vui lòng chọn sản phẩm.');
                hasError = true;
                if (!firstErrField) firstErrField = productSelect;
            }

            var qtyRaw = (qtyInput.value || '').trim();
            var qtyVal = parseInt(qtyRaw, 10);
            var isMinus = radioMinus.checked;
            var currentQty = parseInt(currentQtyDisplay.textContent, 10) || 0;

            if (!qtyRaw) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng.');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            } else if (isNaN(qtyVal) || qtyVal <= 0) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng hợp lệ (> 0).');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            } else if (isMinus && qtyVal > currentQty) {
                setFieldError(qtyInput, 'Số lượng giảm (' + qtyVal + ') không được vượt quá tồn kho hiện tại (' + currentQty + ')!');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            }

            var reasonVal = reasonSelect.value;
            if (!reasonVal) {
                setFieldError(reasonSelect, 'Vui lòng chọn lý do.');
                hasError = true;
                if (!firstErrField) firstErrField = reasonSelect;
            }

            if (hasError) {
                if (firstErrField) firstErrField.focus();
                return;
            }

            var reasonLabel = reasonSelect.options[reasonSelect.selectedIndex].text;
            var noteVal = (noteInput.value || '').trim();
            var finalReason = reasonLabel + (noteVal ? (' - ' + noteVal) : '');
            var deltaQty = isMinus ? -qtyVal : qtyVal;

            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Đang lưu…';

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    action: 'adjust',
                    branchId: branchId,
                    productId: pId,
                    quantity: deltaQty,
                    reason: finalReason
                }
            }).then(function () {
                closeModal();
                hub.notify('Điều chỉnh kho thành công!', 'ok');
                if (onSuccess) onSuccess();
            }).catch(function (err) {
                confirmBtn.disabled = false;
                confirmBtn.textContent = 'Xác nhận';
                notifyError(err);
            });
        });
    }

    var showAdjustModal = showProductAdjustModal;
    var showInventoryAdjustModal = showProductAdjustModal;

    // 4. Màn hình danh sách tồn kho thành phẩm
    function renderProductInventoryPage(viewRoot, branchId, branches, isStaff, onSwitchTab) {
        hub.setWorkspaceHeader('Kho Thành Phẩm');
        var currentBranchId = branchId || (branches && branches[0] ? branches[0].id : null);
        var currentItems = [];
        var toolbarRight = [];

        function refreshProductList() {
            var branchSelect = document.querySelector('select[name="branchId"]');
            if (branchSelect) {
                branchSelect.dispatchEvent(new Event('change'));
            } else {
                renderProductInventoryPage(viewRoot, currentBranchId, branches, isStaff, onSwitchTab);
            }
        }

        if (!isStaff) {
            var btnAdjust = hub.el('button', {
                class: 'ws-btn secondary',
                type: 'button',
                style: 'display: inline-flex; align-items: center; gap: 6px; font-weight: 600;'
            }, ['Điều chỉnh kho']);
            btnAdjust.addEventListener('click', function () {
                if (!currentBranchId) { hub.notify('Vui lòng chọn chi nhánh trước.', 'warn'); return; }
                showProductAdjustModal(currentBranchId, null, currentItems, function () {
                    refreshProductList();
                });
            });

            var btnRestock = hub.el('button', {
                class: 'ws-btn primary',
                type: 'button',
                style: 'display: inline-flex; align-items: center; gap: 6px; font-weight: 600;'
            }, ['+ Nhập kho mới']);
            btnRestock.addEventListener('click', function () {
                if (!currentBranchId) { hub.notify('Vui lòng chọn chi nhánh trước.', 'warn'); return; }
                showProductRestockModal(currentBranchId, currentItems, function () {
                    refreshProductList();
                });
            });

            toolbarRight = [btnAdjust, btnRestock];
        }

        list.render({
            title: 'Kho Thành Phẩm',
            subtitle: 'Quản lý tồn kho bắp nước, combo bán tại quầy và theo dõi lịch sử biến động.',
            toolbarRight: toolbarRight,
            searchable: true,
            searchPlaceholder: 'Tìm kiếm sản phẩm…',
            searchParam: 'search',
            pageSize: 50,
            filters: [
                {
                    name: 'branchId',
                    label: 'Chi nhánh',
                    placeholder: 'Chọn chi nhánh…',
                    initialValue: currentBranchId ? String(currentBranchId) : '',
                    options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; })
                },
                {
                    name: 'type',
                    label: 'Loại',
                    placeholder: 'Tất cả loại',
                    options: [
                        { value: 'POPCORN', label: 'Bắp rang (POPCORN)' },
                        { value: 'DRINK', label: 'Nước uống (DRINK)' },
                        { value: 'COMBO', label: 'Combo (COMBO)' },
                        { value: 'OTHER', label: 'Khác (OTHER)' }
                    ]
                },
                {
                    name: 'status',
                    label: 'Trạng thái',
                    placeholder: 'Tất cả trạng thái',
                    options: [
                        { value: 'IN_STOCK', label: 'Còn hàng' },
                        { value: 'LOW_STOCK', label: 'Sắp hết' },
                        { value: 'OUT_OF_STOCK', label: 'Hết hàng' }
                    ]
                }
            ],
            fetcher: function (qs) {
                var activeBranch = qs.get('branchId') || currentBranchId;
                if (activeBranch) currentBranchId = activeBranch;
                if (!activeBranch) {
                    return Promise.resolve({ items: [], total: 0 });
                }

                return hub.api('/inventory?branchId=' + activeBranch).then(function (resList) {
                    var items = resList || [];
                    currentItems = items;

                    var search = (qs.get('search') || qs.get('q') || '').trim().toLowerCase();
                    if (search) {
                        var searchNorm = search.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
                        items = items.filter(function (it) {
                            var nameLower = (it.name || '').toLowerCase();
                            var nameNorm = nameLower.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
                            return nameLower.indexOf(search) !== -1 || nameNorm.indexOf(searchNorm) !== -1;
                        });
                    }

                    var typeFilter = qs.get('type');
                    if (typeFilter) {
                        items = items.filter(function (it) {
                            return (it.type || '').toUpperCase() === typeFilter.toUpperCase();
                        });
                    }

                    var statusFilter = qs.get('status');
                    if (statusFilter) {
                        items = items.filter(function (it) {
                            var q = it.qty != null ? it.qty : 0;
                            if (statusFilter === 'OUT_OF_STOCK') return q <= 0;
                            if (statusFilter === 'LOW_STOCK') return q > 0 && q < 10;
                            if (statusFilter === 'IN_STOCK') return q >= 10;
                            return true;
                        });
                    }

                    return { items: items, total: items.length };
                });
            },
            emptyTitle: 'Chưa có tồn kho',
            emptyMessage: 'Không tìm thấy sản phẩm nào trong kho theo điều kiện lọc.',
            columns: [
                {
                    label: 'Tên sản phẩm',
                    render: function (s) {
                        var elName = hub.el('strong', {
                            style: 'cursor: pointer; color: #1d4ed8; text-decoration: underline;'
                        }, [s.name]);
                        elName.addEventListener('click', function () {
                            showProductDetailModal(currentBranchId, s);
                        });
                        return elName;
                    }
                },
                {
                    label: 'Loại',
                    key: 'type'
                },
                {
                    label: 'Tồn kho',
                    render: function (s) {
                        return (s.qty || 0) + ' ' + (s.unit || '');
                    }
                },
                {
                    label: 'Trạng thái',
                    render: function (s) {
                        var q = s.qty != null ? s.qty : 0;
                        if (q <= 0) {
                            return hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-red' }, ['Hết hàng']);
                        } else if (q < 10) {
                            return hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-orange' }, ['Sắp hết (' + q + ')']);
                        } else {
                            return hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-green' }, ['Còn hàng']);
                        }
                    }
                },
                {
                    label: 'Cập nhật',
                    render: function (s) {
                        return s.updatedAt || '—';
                    }
                }
            ],
            actions: function (row) {
                return [
                    {
                        label: 'Lịch sử',
                        class: 'secondary',
                        onClick: function () {
                            showProductDetailModal(currentBranchId, row);
                        }
                    }
                ];
            }
        });

    }

    // ================================================================
    //  PHẦN 2: GIAI ĐOẠN 2 - KHO NGUYÊN LIỆU (NGUYÊN LIỆU THÔ CHẾ BIẾN)
    // ================================================================

    // 1. Modal xem chi tiết nguyên liệu và lịch sử biến động
    function showMaterialDetailModal(branchId, material, rawMaterials, onReload, isStaff) {
        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'dialog',
            'aria-modal': 'true',
            style: 'max-width: 760px; width: 100%; border-radius: 12px; overflow: hidden; max-height: 90vh; display: flex; flex-direction: column; padding: 0; box-shadow: 0 20px 40px rgba(0,0,0,0.25);'
        });

        var closeBtn = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng',
            style: 'color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;'
        }, ['×']);

        var head = hub.el('div', {
            style: 'background: #0284c7; color: #ffffff; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;'
        }, [
            hub.el('div', {}, [
                hub.el('h3', { style: 'margin: 0; font-size: 1.15rem; font-weight: 700; color: #fff;' }, ['Chi tiết nguyên liệu & Lịch sử biến động']),
                hub.el('small', { style: 'font-size: 0.82rem; color: #e0f2fe; display: block; margin-top: 2px;' }, ['Lịch sử xuất nhập & điều chỉnh nguyên liệu'])
            ]),
            closeBtn
        ]);

        var body = hub.el('div', { style: 'padding: 16px 20px; background: #fff; overflow-y: auto; flex: 1;' });

        var mId = material.materialId || material.id;
        var qty = material.qty != null ? material.qty : 0;
        var minStock = material.minStock != null ? material.minStock : 0;
        var unit = material.unit || '';
        var statusClass = qty <= 0 ? 'ws-badge-red' : (qty <= minStock ? 'ws-badge-orange' : 'ws-badge-green');
        var statusLabel = qty <= 0 ? 'Hết hàng' : (qty <= minStock ? ('Sắp hết (' + qty + ')') : 'Còn hàng');

        var infoCard = hub.el('div', {
            style: 'background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 16px 20px; margin-bottom: 20px;'
        }, [
            hub.el('div', { style: 'display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px;' }, [
                hub.el('div', { style: 'display: flex; align-items: center; gap: 12px;' }, [
                    hub.el('div', { style: 'width: 44px; height: 44px; border-radius: 8px; background: #e0f2fe; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700; color: #0369a1;' }, ['NL']),
                    hub.el('div', {}, [
                        hub.el('h4', { style: 'margin: 0; font-size: 1.1rem; font-weight: 700; color: #0f172a;' }, [material.name || ('Nguyên liệu #' + mId)]),
                        hub.el('div', { style: 'font-size: 0.85rem; color: #64748b; font-weight: 600; margin-top: 2px;' }, ['Mã: ' + (material.code || '—') + ' | Phân loại: ' + formatMaterialCategory(material.category)])
                    ])
                ]),
                hub.el('span', { class: 'ws-badge ws-status-pill ' + statusClass, style: 'font-size: 0.85rem;' }, [statusLabel])
            ]),
            hub.el('div', { style: 'display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; font-size: 0.86rem; border-top: 1px dashed #cbd5e1; padding-top: 12px;' }, [
                hub.el('div', {}, ['Đơn vị tính: ', hub.el('strong', { style: 'color: #334155;' }, [formatMaterialUnit(unit)])]),
                hub.el('div', {}, ['Tồn kho hiện tại: ', hub.el('strong', { style: 'color: #0284c7; font-size: 0.95rem;' }, [qty + ' ' + formatMaterialUnit(unit)])]),
                hub.el('div', {}, ['Định mức tối thiểu: ', hub.el('strong', { style: 'color: #ea580c;' }, [minStock + ' ' + formatMaterialUnit(unit)])]),
                hub.el('div', {}, ['Nhà cung cấp chính: ', hub.el('strong', { style: 'color: #334155;' }, [material.supplier || '—'])]),
                hub.el('div', {}, ['Đơn giá nhập: ', hub.el('strong', { style: 'color: #334155;' }, [formatMoneyVnd(material.costPrice)])]),
                hub.el('div', {}, ['Trạng thái: ', hub.el('span', { class: 'ws-badge ws-badge-green', style: 'padding: 2px 6px; font-size: 0.78rem;' }, [material.status || 'ACTIVE'])])
            ])
        ]);
        body.appendChild(infoCard);

        var histHead = hub.el('div', {
            style: 'display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; flex-wrap: wrap; gap: 8px;'
        }, [
            hub.el('h5', { style: 'margin: 0; font-size: 0.98rem; font-weight: 700; color: #1e293b;' }, ['Lịch sử biến động kho']),
            hub.el('div', { style: 'display: flex; gap: 8px; align-items: center;' }, [
                auditFilterSelect
            ])
        ]);
        body.appendChild(histHead);

        var tableWrap = hub.el('div', { style: 'border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden;' });
        var historyLoading = hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-text-muted); margin: 0;' }, ['Đang tải lịch sử…']);
        tableWrap.appendChild(historyLoading);
        body.appendChild(tableWrap);

        var paginationWrap = hub.el('div', { style: 'display: flex; justify-content: flex-end; align-items: center; gap: 6px; margin-top: 12px;' });
        body.appendChild(paginationWrap);

        var footBtns = [];
        if (!isStaff && rawMaterials) {
            var btnRestock = hub.el('button', {
                class: 'ws-btn primary',
                type: 'button',
                style: 'flex: 1; border-radius: 8px; background: #16a34a; border-color: #16a34a; font-weight: 600;'
            }, ['Nhập thêm']);
            btnRestock.addEventListener('click', function () {
                close();
                showMaterialRestockModal(branchId, rawMaterials, onReload, material);
            });

            var btnAdjust = hub.el('button', {
                class: 'ws-btn secondary',
                type: 'button',
                style: 'flex: 1; border-radius: 8px; font-weight: 600;'
            }, ['Điều chỉnh']);
            btnAdjust.addEventListener('click', function () {
                close();
                showMaterialAdjustModal(branchId, material, rawMaterials, onReload);
            });

            footBtns.push(btnRestock);
            footBtns.push(btnAdjust);
        }

        var closeFoot = hub.el('button', {
            class: 'ws-btn secondary',
            type: 'button',
            style: (!isStaff && rawMaterials ? 'flex: 0.8;' : 'width: 100%;') + ' border-radius: 8px; font-weight: 600;'
        }, ['Đóng']);
        footBtns.push(closeFoot);

        var footWrap = hub.el('div', {
            style: 'padding: 14px 20px; background: #f8fafc; border-top: 1px solid #e2e8f0; display: flex; gap: 10px; flex-shrink: 0;'
        }, footBtns);

        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footWrap);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function close() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }
        backdrop.addEventListener('click', close);
        closeBtn.addEventListener('click', close);
        closeFoot.addEventListener('click', close);

        hub.api('/inventory?type=material&action=history&branchId=' + branchId + '&materialId=' + mId)
            .then(function (res) {
                var rawList = (res && res.data) || res || [];
                if (!Array.isArray(rawList) || rawList.length === 0) {
                    tableWrap.replaceChildren(hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-text-muted); margin: 0;' }, ['Chưa có lịch sử biến động nào cho nguyên liệu này.']));
                    return;
                }

                var filteredList = rawList.slice();
                var pageSize = 5;
                var curPage = 0;
                var totalPages = Math.ceil(filteredList.length / pageSize) || 1;

                function applyAuditFilter() {
                    var filterVal = auditFilterSelect.value;
                    if (filterVal === 'TODAY') {
                        var todayStr = new Date().toISOString().slice(0, 10);
                        filteredList = rawList.filter(function (h) {
                            return (h.createdAt || '').slice(0, 10) === todayStr;
                        });
                    } else if (filterVal === '7DAYS') {
                        var sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000);
                        filteredList = rawList.filter(function (h) {
                            if (!h.createdAt) return false;
                            var d = new Date(h.createdAt);
                            return d >= sevenDaysAgo;
                        });
                    } else {
                        filteredList = rawList.slice();
                    }
                    curPage = 0;
                    totalPages = Math.ceil(filteredList.length / pageSize) || 1;
                    renderHistoryTable();
                }

                auditFilterSelect.addEventListener('change', applyAuditFilter);


                function renderHistoryTable() {
                    if (!filteredList.length) {
                        tableWrap.replaceChildren(hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-text-muted); margin: 0;' }, ['Không tìm thấy lịch sử phù hợp với khoảng thời gian đã chọn.']));
                        paginationWrap.replaceChildren();
                        return;
                    }

                    var table = hub.el('table', { class: 'ws-table', style: 'margin: 0; width: 100%;' });
                    var thead = hub.el('thead', {}, [
                        hub.el('tr', {}, [
                            hub.el('th', { style: 'font-size: 0.82rem; white-space: nowrap;' }, ['Ngày giờ']),
                            hub.el('th', { style: 'font-size: 0.82rem; width: 95px; min-width: 95px; text-align: center; white-space: nowrap;' }, ['Loại']),
                            hub.el('th', { style: 'font-size: 0.82rem; white-space: nowrap;' }, ['Số lượng']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Lý do']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Người thực hiện']),
                            hub.el('th', { style: 'font-size: 0.82rem;' }, ['Ghi chú'])
                        ])
                    ]);
                    var tbody = hub.el('tbody');
                    var start = curPage * pageSize;
                    var pageItems = filteredList.slice(start, start + pageSize);

                    pageItems.forEach(function (h) {
                        var isPlus = h.deltaQty > 0;
                        var signBg = isPlus ? '#16a34a' : '#dc2626';
                        var absQty = Math.abs(h.deltaQty);
                        var typeLabel = isPlus ? '+ Nhập' : '- Xuất';

                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', { style: 'font-size: 0.82rem; color: #64748b; white-space: nowrap;' }, [h.createdAt || '—']),
                            hub.el('td', { style: 'text-align: center; white-space: nowrap;' }, [
                                hub.el('span', {
                                    style: 'display: inline-block; padding: 4px 10px; border-radius: 6px; font-weight: 700; font-size: 0.82rem; color: #fff; background: ' + signBg + '; white-space: nowrap; line-height: 1.2;'
                                }, [typeLabel])
                            ]),
                            hub.el('td', { style: 'font-weight: 600; color: #0f172a; white-space: nowrap;' }, [absQty + ' ' + formatMaterialUnit(unit)]),
                            hub.el('td', { style: 'font-size: 0.85rem; font-weight: 500; color: #1e293b;' }, [formatInventoryReason(h.reason)]),
                            hub.el('td', { style: 'font-size: 0.85rem; font-weight: 600;' }, [h.actorName || ('ID: ' + h.actorId)]),
                            hub.el('td', { style: 'font-size: 0.82rem; color: #64748b;' }, [h.note || '—'])
                        ]));
                    });

                    table.appendChild(thead);
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);

                    paginationWrap.replaceChildren();
                    if (totalPages > 1) {
                        var prevBtn = hub.el('button', {
                            class: 'ws-pagination-btn',
                            type: 'button',
                            disabled: curPage === 0
                        }, ['<']);
                        prevBtn.addEventListener('click', function () {
                            if (curPage > 0) { curPage--; renderHistoryTable(); }
                        });
                        paginationWrap.appendChild(prevBtn);

                        for (var i = 0; i < totalPages; i++) {
                            (function (pIndex) {
                                var pBtn = hub.el('button', {
                                    class: 'ws-pagination-btn' + (pIndex === curPage ? ' active' : ''),
                                    type: 'button'
                                }, [String(pIndex + 1)]);
                                pBtn.addEventListener('click', function () {
                                    curPage = pIndex;
                                    renderHistoryTable();
                                });
                                paginationWrap.appendChild(pBtn);
                            })(i);
                        }

                        var nextBtn = hub.el('button', {
                            class: 'ws-pagination-btn',
                            type: 'button',
                            disabled: curPage >= totalPages - 1
                        }, ['>']);
                        nextBtn.addEventListener('click', function () {
                            if (curPage < totalPages - 1) { curPage++; renderHistoryTable(); }
                        });
                        paginationWrap.appendChild(nextBtn);
                    }
                }

                renderHistoryTable();
            })
            .catch(function (err) {
                tableWrap.replaceChildren(hub.el('p', { style: 'padding: 24px; text-align: center; color: var(--ws-danger); margin: 0;' }, ['Không thể tải lịch sử: ' + (err.message || err)]));
            });
    }

    // 2. Modal nhập nguyên liệu
    function showMaterialRestockModal(branchId, arg2, arg3, arg4, arg5) {
        var branchName = '';
        var materials = [];
        var onSuccess = null;
        var initialMaterial = null;

        if (Array.isArray(arg2)) {
            materials = arg2;
            onSuccess = arg3;
            initialMaterial = arg4;
        } else {
            branchName = arg2 || '';
            materials = arg3 || [];
            onSuccess = arg4;
            initialMaterial = arg5;
        }

        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'dialog',
            'aria-modal': 'true',
            style: 'max-width: 520px; width: 100%; border-radius: 12px; overflow: hidden; max-height: 90vh; display: flex; flex-direction: column; padding: 0; box-shadow: 0 20px 40px rgba(0,0,0,0.25);'
        });

        var closeBtn = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng',
            style: 'color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;'
        }, ['×']);

        var head = hub.el('div', {
            style: 'background: #16a34a; color: #ffffff; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;'
        }, [
            hub.el('div', {}, [
                hub.el('h3', { style: 'margin: 0; font-size: 1.15rem; font-weight: 700; color: #fff;' }, ['Nhập nguyên liệu']),
                hub.el('small', { style: 'font-size: 0.82rem; color: #dcfce7; display: block; margin-top: 2px;' }, ['Tăng số lượng tồn kho nguyên liệu chi nhánh'])
            ]),
            closeBtn
        ]);

        var body = hub.el('div', { style: 'padding: 18px 20px; background: #fff; overflow-y: auto; flex: 1;' });

        // Chi nhánh
        var branchField = hub.el('div', { style: 'margin-bottom: 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; font-size: 0.88rem; color: #475569;' }, [
            'Chi nhánh: ', hub.el('strong', { style: 'color: #0f172a;' }, [branchName || ('Chi nhánh #' + branchId)])
        ]);
        body.appendChild(branchField);

        // Nguyên liệu * (chỉ load ACTIVE)
        var matGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        matGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Nguyên liệu ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var matSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        matSelect.appendChild(hub.el('option', { value: '' }, ['Chọn nguyên liệu…']));

        (materials || []).filter(function (it) {
            return !it.status || it.status.toUpperCase() === 'ACTIVE';
        }).forEach(function (it) {
            var mId = it.materialId || it.id;
            matSelect.appendChild(hub.el('option', { value: String(mId) }, ['[' + (it.code || 'RM') + '] ' + it.name + ' (' + (it.qty != null ? it.qty : 0) + ' ' + (it.unit || '') + ')']));
        });
        matGroup.appendChild(matSelect);
        body.appendChild(matGroup);

        // Số lượng nhập * (> 0, cho phép số lẻ)
        var qtyGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        qtyGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Số lượng nhập ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var qtyInput = hub.el('input', {
            type: 'number',
            step: 'any',
            min: '0.01',
            placeholder: 'Nhập số lượng (vd: 50.5)',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        qtyGroup.appendChild(qtyInput);
        body.appendChild(qtyGroup);

        // Đơn giá nhập & Nhà cung cấp
        var row2 = hub.el('div', { style: 'display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 14px;' });
        var costInput = hub.el('input', {
            type: 'number',
            placeholder: 'Đơn giá (đ/ĐVT)…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem;'
        });
        var supplierInput = hub.el('input', {
            type: 'text',
            placeholder: 'Nhà cung cấp…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem;'
        });
        row2.appendChild(hub.el('div', {}, [
            hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, ['Đơn giá nhập']),
            costInput
        ]));
        row2.appendChild(hub.el('div', {}, [
            hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, ['Nhà cung cấp']),
            supplierInput
        ]));
        body.appendChild(row2);

        // Lý do * & Số PO
        var row3 = hub.el('div', { style: 'display: grid; grid-template-columns: 1.2fr 0.8fr; gap: 12px; margin-bottom: 14px;' });
        var reasonSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        [
            { value: 'NHAP_HANG_TU_NCC', label: 'Nhập hàng từ nhà cung cấp' },
            { value: 'NHAP_LAI_TU_NCC', label: 'Nhập đổi trả từ nhà cung cấp' },
            { value: 'HOAN_TU_DON_HUY', label: 'Hoàn lại từ đơn hủy' },
            { value: 'DIEU_CHINH_KHAC', label: 'Lý do khác' }
        ].forEach(function (opt) {
            reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
        });

        var poInput = hub.el('input', {
            type: 'text',
            placeholder: 'Số PO / Hóa đơn…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem;'
        });

        row3.appendChild(hub.el('div', {}, [
            hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
                'Lý do ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
            ]),
            reasonSelect
        ]));
        row3.appendChild(hub.el('div', {}, [
            hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, ['Số hóa đơn / PO']),
            poInput
        ]));
        body.appendChild(row3);

        // Ghi chú
        var noteGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        var noteLabel = hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, ['Ghi chú']);
        var noteInput = hub.el('input', {
            type: 'text',
            placeholder: 'Nhập ghi chú (bắt buộc nếu chọn Điều chỉnh khác)…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        noteGroup.appendChild(noteLabel);
        noteGroup.appendChild(noteInput);
        body.appendChild(noteGroup);

        // Realtime Preview
        var previewBox = hub.el('div', {
            style: 'background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 12px 16px; margin-bottom: 20px; font-size: 0.9rem; color: #166534;'
        }, ['Sau khi nhập: Tồn mới = —']);
        body.appendChild(previewBox);

        function updatePreview() {
            var selectedId = matSelect.value;
            var found = (materials || []).find(function (it) { return String(it.materialId || it.id) === selectedId; });
            var cur = found ? (found.qty || 0) : 0;
            var unit = found ? (found.unit || '') : '';
            var addQty = parseFloat(qtyInput.value) || 0;
            if (!selectedId) {
                previewBox.textContent = 'Sau khi nhập: Tồn mới = —';
            } else {
                var newQty = Math.round((cur + addQty) * 100) / 100;
                previewBox.innerHTML = '<strong>Xác nhận sau khi nhập:</strong><br>' +
                    '• Tồn mới: ' + cur + ' + ' + addQty + ' = <strong>' + newQty + ' ' + unit + '</strong><br>' +
                    '• Ghi log: +' + addQty + ' ' + unit + ' vào lịch sử biến động';
            }
        }

        matSelect.addEventListener('change', function () {
            var selectedId = matSelect.value;
            var found = (materials || []).find(function (it) { return String(it.materialId || it.id) === selectedId; });
            if (found) {
                costInput.value = (found.costPrice != null && found.costPrice > 0) ? found.costPrice : '';
                supplierInput.value = found.supplier || '';
            } else {
                costInput.value = '';
                supplierInput.value = '';
            }
            updatePreview();
        });
        qtyInput.addEventListener('input', updatePreview);

        if (initialMaterial && (initialMaterial.materialId || initialMaterial.id)) {
            var initId = String(initialMaterial.materialId || initialMaterial.id);
            matSelect.value = initId;
            var foundInit = (materials || []).find(function (it) { return String(it.materialId || it.id) === initId; }) || initialMaterial;
            costInput.value = (foundInit.costPrice != null && foundInit.costPrice > 0) ? foundInit.costPrice : '';
            supplierInput.value = foundInit.supplier || '';
            updatePreview();
        }

        // Sticky Footer
        var cancelBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: 1px solid #cbd5e1; background: #e2e8f0; color: #334155; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Hủy']);

        var confirmBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: none; background: #16a34a; color: #ffffff; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Xác nhận nhập']);

        var footer = hub.el('div', {
            style: 'display: flex; gap: 12px; padding: 14px 20px; background: #f8fafc; border-top: 1px solid #e2e8f0; flex-shrink: 0;'
        }, [cancelBtn, confirmBtn]);

        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footer);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function closeModal() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }
        closeBtn.addEventListener('click', closeModal);
        backdrop.addEventListener('click', closeModal);
        cancelBtn.addEventListener('click', closeModal);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(matSelect);
            clearFieldError(qtyInput);
            clearFieldError(noteInput);

            var hasError = false;
            var firstErrField = null;

            var mId = matSelect.value;
            if (!mId) {
                setFieldError(matSelect, 'Vui lòng chọn nguyên liệu.');
                hasError = true;
                if (!firstErrField) firstErrField = matSelect;
            }

            var qtyRaw = (qtyInput.value || '').trim();
            var qtyVal = parseFloat(qtyRaw);
            if (!qtyRaw) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng.');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            } else if (isNaN(qtyVal) || qtyVal <= 0) {
                setFieldError(qtyInput, 'Số lượng nhập phải lớn hơn 0.');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            }

            var reasonVal = reasonSelect.value;
            var noteVal = (noteInput.value || '').trim();
            if (reasonVal === 'DIEU_CHINH_KHAC' && !noteVal) {
                setFieldError(noteInput, 'Vui lòng nhập ghi chú khi chọn lý do "Điều chỉnh khác".');
                hasError = true;
                if (!firstErrField) firstErrField = noteInput;
            }

            if (hasError) {
                if (firstErrField) firstErrField.focus();
                return;
            }

            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Đang lưu…';

            var payload = {
                type: 'material',
                action: 'restock',
                branchId: branchId,
                materialId: mId,
                quantity: qtyVal,
                reason: reasonVal,
                costPrice: costInput.value ? costInput.value.trim() : '',
                supplier: supplierInput.value ? supplierInput.value.trim() : '',
                poNumber: poInput.value ? poInput.value.trim() : '',
                note: noteVal
            };

            hub.api('/inventory', {
                method: 'POST',
                body: payload
            }).then(function () {
                closeModal();
                hub.notify('Nhập nguyên liệu thành công!', 'ok');
                if (onSuccess) onSuccess();
            }).catch(function (err) {
                confirmBtn.disabled = false;
                confirmBtn.textContent = 'Xác nhận nhập';
                notifyError(err);
            });
        });
    }

    // 3. Modal điều chỉnh nguyên liệu
    function showMaterialAdjustModal(branchId, selectedMaterial, materials, onSuccess) {
        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'dialog',
            'aria-modal': 'true',
            style: 'max-width: 500px; width: 100%; border-radius: 12px; overflow: hidden; max-height: 90vh; display: flex; flex-direction: column; padding: 0; box-shadow: 0 20px 40px rgba(0,0,0,0.25);'
        });

        var closeBtn = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng',
            style: 'color: #fff; background: rgba(255,255,255,0.18); border-radius: 50%; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border: none; font-size: 18px; cursor: pointer;'
        }, ['×']);

        var head = hub.el('div', {
            style: 'background: #475569; color: #ffffff; padding: 14px 20px; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;'
        }, [
            hub.el('div', {}, [
                hub.el('h3', { style: 'margin: 0; font-size: 1.15rem; font-weight: 700; color: #fff;' }, ['Điều chỉnh nguyên liệu']),
                hub.el('small', { style: 'font-size: 0.82rem; color: #cbd5e1; display: block; margin-top: 2px;' }, ['Kiểm kê, hao hụt hoặc cân bằng số tồn'])
            ]),
            closeBtn
        ]);

        var body = hub.el('div', { style: 'padding: 18px 20px; background: #fff; overflow-y: auto; flex: 1;' });

        // Nguyên liệu *
        var matGroup = hub.el('div', { style: 'margin-bottom: 12px;' });
        matGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Nguyên liệu ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var matSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        matSelect.appendChild(hub.el('option', { value: '' }, ['Chọn nguyên liệu…']));
        (materials || []).forEach(function (it) {
            var mId = it.materialId || it.id;
            matSelect.appendChild(hub.el('option', { value: String(mId) }, ['[' + (it.code || 'RM') + '] ' + it.name + ' (' + (it.qty != null ? it.qty : 0) + ' ' + (it.unit || '') + ')']));
        });
        if (selectedMaterial && (selectedMaterial.materialId || selectedMaterial.id)) {
            matSelect.value = String(selectedMaterial.materialId || selectedMaterial.id);
        }
        matGroup.appendChild(matSelect);
        body.appendChild(matGroup);

        // Tồn hiện tại
        var currentQtyDisplay = hub.el('strong', { style: 'font-size: 1.05rem; color: #0f172a;' }, ['0']);
        var currentQtyWrap = hub.el('div', { style: 'margin-bottom: 16px; font-size: 0.9rem; color: #475569;' }, [
            'Tồn hiện tại: ', currentQtyDisplay
        ]);
        body.appendChild(currentQtyWrap);

        var currentItem = null;
        function updateCurrentQty() {
            var selectedId = matSelect.value;
            currentItem = (materials || []).find(function (it) { return String(it.materialId || it.id) === selectedId; }) || null;
            var q = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
            var u = currentItem ? (currentItem.unit || '') : '';
            currentQtyDisplay.textContent = q + ' ' + u;
            updateRealtimeCheck();
        }
        matSelect.addEventListener('change', function () {
            updateCurrentQty();
            updateReasonList();
        });

        // Loại điều chỉnh: 3 lựa chọn (GIẢM / TĂNG / SỬA THẲNG)
        var typeGroup = hub.el('div', { style: 'margin-bottom: 16px;' });
        typeGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 8px;' }, [
            'Loại điều chỉnh ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var radioMinus = hub.el('input', { type: 'radio', name: 'mr5Type', value: 'MINUS', checked: true, style: 'accent-color: #dc2626; width: 17px; height: 17px; cursor: pointer;' });
        var radioPlus = hub.el('input', { type: 'radio', name: 'mr5Type', value: 'PLUS', style: 'accent-color: #16a34a; width: 17px; height: 17px; cursor: pointer;' });
        var radioSet = hub.el('input', { type: 'radio', name: 'mr5Type', value: 'SET', style: 'accent-color: #0284c7; width: 17px; height: 17px; cursor: pointer;' });

        var typeOptions = hub.el('div', { style: 'display: flex; gap: 20px; align-items: center;' }, [
            hub.el('label', { style: 'display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #dc2626; font-size: 0.95rem;' }, [
                radioMinus, 'GIẢM'
            ]),
            hub.el('label', { style: 'display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #16a34a; font-size: 0.95rem;' }, [
                radioPlus, 'TĂNG'
            ]),
            hub.el('label', { style: 'display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-weight: 600; color: #0284c7; font-size: 0.95rem;' }, [
                radioSet, 'SỬA THẲNG'
            ])
        ]);
        typeGroup.appendChild(typeOptions);
        body.appendChild(typeGroup);

        // Input Số lượng
        var qtyGroup = hub.el('div', { style: 'margin-bottom: 16px;' });
        var qtyLabel = hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Số lượng giảm ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]);
        var qtyInput = hub.el('input', {
            type: 'number',
            step: 'any',
            min: '0.01',
            placeholder: 'Nhập số lượng giảm (vd: 5.5)…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        qtyGroup.appendChild(qtyLabel);
        qtyGroup.appendChild(qtyInput);
        body.appendChild(qtyGroup);

        // Lý do *
        var reasonGroup = hub.el('div', { style: 'margin-bottom: 16px;' });
        reasonGroup.appendChild(hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Lý do ', hub.el('span', { style: 'color: #ef4444;' }, ['*'])
        ]));
        var reasonSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; box-sizing: border-box; height: 38px;' });
        reasonGroup.appendChild(reasonSelect);
        body.appendChild(reasonGroup);

        var reasonsDecrease = [
            { value: '', label: 'Chọn lý do…' },
            { value: 'HU_HONG', label: 'Hư hỏng / Rách bao bì' },
            { value: 'HET_HAN', label: 'Hết hạn sử dụng' },
            { value: 'MAT_DO_KHACH', label: 'Thất thoát / Đổ vỡ' },
            { value: 'KIEM_KE_THIEU', label: 'Kiểm kê thiếu (Hụt kho)' },
            { value: 'OTHER', label: 'Lý do khác' }
        ];

        var reasonsIncrease = [
            { value: '', label: 'Chọn lý do…' },
            { value: 'KIEM_KE_THUA', label: 'Kiểm kê thừa (Thực tế nhiều hơn)' },
            { value: 'DIEU_CHINH_KHAC', label: 'Tìm lại hàng thất lạc / để sót' },
            { value: 'OTHER', label: 'Lý do khác' }
        ];

        function updateReasonList() {
            var isMinus = radioMinus.checked;
            var isPlus = radioPlus.checked;
            var isSet = radioSet.checked;
            var opts = reasonsDecrease;
            if (isPlus) {
                opts = reasonsIncrease;
            } else if (isSet) {
                var curQ = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
                var setVal = parseFloat(qtyInput.value) || 0;
                opts = setVal >= curQ ? reasonsIncrease : reasonsDecrease;
            }

            reasonSelect.replaceChildren();
            opts.forEach(function (opt) {
                reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
            });
        }

        // Ghi chú
        var noteGroup = hub.el('div', { style: 'margin-bottom: 14px;' });
        var noteLabel = hub.el('label', { style: 'display: block; font-size: 0.86rem; font-weight: 600; color: #334155; margin-bottom: 6px;' }, [
            'Ghi chú ', hub.el('span', { id: 'mr5NoteReq', style: 'color: #ef4444; display: none;' }, ['(bắt buộc >= 10 ký tự)'])
        ]);
        var noteInput = hub.el('input', {
            type: 'text',
            placeholder: 'Nhập ghi chú thêm…',
            style: 'width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ws-line); border-radius: 8px; font-size: 0.9rem; background: #fff; color: #0f172a;'
        });
        noteGroup.appendChild(noteLabel);
        noteGroup.appendChild(noteInput);
        body.appendChild(noteGroup);

        // Cảnh báo realtime (Dưới min stock)
        var warningBox = hub.el('div', {
            style: 'display: none; background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 10px 14px; margin-bottom: 18px; font-size: 0.88rem; color: #b45309;'
        }, ['[Cảnh báo] Tồn kho sau điều chỉnh dưới mức tối thiểu!']);
        body.appendChild(warningBox);

        function updateRealtimeCheck() {
            var curQ = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
            var minQ = currentItem ? (currentItem.minStock != null ? currentItem.minStock : 0) : 0;
            var unit = currentItem ? (currentItem.unit || '') : '';
            var val = parseFloat(qtyInput.value) || 0;

            var newQty = curQ;
            if (radioMinus.checked) {
                newQty = curQ - val;
            } else if (radioPlus.checked) {
                newQty = curQ + val;
            } else if (radioSet.checked) {
                newQty = val;
            }

            if (currentItem && newQty < minQ) {
                warningBox.style.display = 'block';
                warningBox.innerHTML = '<strong>Cảnh báo:</strong> Tồn sau điều chỉnh (' + (Math.round(newQty * 100) / 100) + ' ' + unit + ') dưới mức tối thiểu (' + minQ + ' ' + unit + ')';
            } else {
                warningBox.style.display = 'none';
            }
        }

        function onTypeChange() {
            if (radioMinus.checked) {
                qtyLabel.innerHTML = 'Số lượng giảm <span style="color: #ef4444;">*</span>';
                qtyInput.placeholder = 'Nhập số lượng giảm (vd: 5.5)…';
            } else if (radioPlus.checked) {
                qtyLabel.innerHTML = 'Số lượng tăng <span style="color: #ef4444;">*</span>';
                qtyInput.placeholder = 'Nhập số lượng tăng (vd: 10.0)…';
            } else {
                qtyLabel.innerHTML = 'Tồn kho mới (Set value) <span style="color: #ef4444;">*</span>';
                qtyInput.placeholder = 'Nhập số tồn kho thực tế mới (vd: 120)…';
            }
            updateReasonList();
            updateRealtimeCheck();
        }

        radioMinus.addEventListener('change', onTypeChange);
        radioPlus.addEventListener('change', onTypeChange);
        radioSet.addEventListener('change', onTypeChange);
        qtyInput.addEventListener('input', function () {
            if (radioSet.checked) updateReasonList();
            updateRealtimeCheck();
        });

        reasonSelect.addEventListener('change', function () {
            var reqSpan = document.getElementById('mr5NoteReq');
            if (reqSpan) reqSpan.style.display = reasonSelect.value === 'OTHER' ? 'inline' : 'none';
        });

        updateCurrentQty();
        onTypeChange();

        // Sticky Footer
        var cancelBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: 1px solid #cbd5e1; background: #e2e8f0; color: #334155; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Hủy']);

        var confirmBtn = hub.el('button', {
            type: 'button',
            style: 'flex: 1; padding: 10px 16px; border-radius: 8px; border: none; background: #1d4ed8; color: #ffffff; font-weight: 600; font-size: 0.95rem; cursor: pointer;'
        }, ['Xác nhận điều chỉnh']);

        var footer = hub.el('div', {
            style: 'display: flex; gap: 12px; padding: 14px 20px; background: #f8fafc; border-top: 1px solid #e2e8f0; flex-shrink: 0;'
        }, [cancelBtn, confirmBtn]);

        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footer);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function closeModal() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }
        closeBtn.addEventListener('click', closeModal);
        backdrop.addEventListener('click', closeModal);
        cancelBtn.addEventListener('click', closeModal);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(matSelect);
            clearFieldError(qtyInput);
            clearFieldError(reasonSelect);
            clearFieldError(noteInput);

            var hasError = false;
            var firstErrField = null;

            var mId = matSelect.value;
            if (!mId) {
                setFieldError(matSelect, 'Vui lòng chọn nguyên liệu.');
                hasError = true;
                if (!firstErrField) firstErrField = matSelect;
            }

            var curQ = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
            var qtyRaw = (qtyInput.value || '').trim();
            var val = parseFloat(qtyRaw);
            var deltaQty = 0;

            if (!qtyRaw) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng.');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            } else if (isNaN(val) || val < 0) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng hợp lệ (>= 0).');
                hasError = true;
                if (!firstErrField) firstErrField = qtyInput;
            } else if (radioMinus.checked) {
                if (val <= 0) {
                    setFieldError(qtyInput, 'Số lượng giảm phải lớn hơn 0.');
                    hasError = true;
                    if (!firstErrField) firstErrField = qtyInput;
                } else if (val > curQ) {
                    setFieldError(qtyInput, 'Số lượng giảm (' + val + ') không được vượt quá tồn hiện tại (' + curQ + ')!');
                    hasError = true;
                    if (!firstErrField) firstErrField = qtyInput;
                } else {
                    deltaQty = -val;
                }
            } else if (radioPlus.checked) {
                if (val <= 0) {
                    setFieldError(qtyInput, 'Số lượng tăng phải lớn hơn 0.');
                    hasError = true;
                    if (!firstErrField) firstErrField = qtyInput;
                } else {
                    deltaQty = val;
                }
            } else if (radioSet.checked) {
                deltaQty = Math.round((val - curQ) * 100) / 100;
                if (deltaQty === 0) {
                    setFieldError(qtyInput, 'Số tồn mới bằng tồn hiện tại, không có chênh lệch để điều chỉnh.');
                    hasError = true;
                    if (!firstErrField) firstErrField = qtyInput;
                }
            }

            var reasonVal = reasonSelect.value;
            if (!reasonVal) {
                setFieldError(reasonSelect, 'Vui lòng chọn lý do điều chỉnh.');
                hasError = true;
                if (!firstErrField) firstErrField = reasonSelect;
            }

            var noteVal = (noteInput.value || '').trim();
            if (reasonVal === 'OTHER' && noteVal.length < 10) {
                setFieldError(noteInput, 'Khi chọn lý do Khác (OTHER), ghi chú bắt buộc phải có ít nhất 10 ký tự.');
                hasError = true;
                if (!firstErrField) firstErrField = noteInput;
            }

            if (hasError) {
                if (firstErrField) firstErrField.focus();
                return;
            }

            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Đang lưu…';

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    type: 'material',
                    action: 'adjust',
                    branchId: branchId,
                    materialId: mId,
                    quantity: deltaQty,
                    reason: reasonVal,
                    note: noteVal
                }
            }).then(function () {
                closeModal();
                hub.notify('Điều chỉnh tồn kho nguyên liệu thành công!', 'ok');
                if (onSuccess) onSuccess();
            }).catch(function (err) {
                confirmBtn.disabled = false;
                confirmBtn.textContent = 'Xác nhận điều chỉnh';
                notifyError(err);
            });
        });
    }

    // 4. Màn hình danh sách tồn kho nguyên liệu
    function renderMaterialInventoryPage(targetRoot, branchId, branches, isStaff, onSwitchTab) {
        if (!targetRoot) return;

        hub.setWorkspaceHeader('Kho Nguyên Liệu');

        var page = hub.el('div', { class: 'ws-page' });

        // Header
        var header = hub.el('div', { class: 'ws-page-header', style: 'margin-bottom: 20px;' }, [
            hub.el('h1', { class: 'ws-page-title' }, ['Kho Nguyên Liệu']),
            hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý tồn kho nguyên liệu phục vụ chế biến bắp nước tại chi nhánh.'])
        ]);
        page.appendChild(header);

        // Vùng 4 Summary Cards
        var cardsContainer = hub.el('div', {
            style: 'display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px;'
        });

        var cardTotal = hub.el('div', {
            class: 'ws-card',
            style: 'padding: 16px 20px; border-radius: 10px; cursor: pointer; border: 2px solid #e2e8f0; transition: all 0.2s;'
        }, [
            hub.el('div', { style: 'font-size: 0.82rem; font-weight: 700; color: #64748b; letter-spacing: 0.5px;' }, ['TỔNG NGUYÊN LIỆU']),
            hub.el('div', { id: 'mrTotalCount', style: 'font-size: 2rem; font-weight: 800; color: #0f172a; margin-top: 4px;' }, ['0'])
        ]);

        var cardInStock = hub.el('div', {
            class: 'ws-card',
            style: 'padding: 16px 20px; border-radius: 10px; cursor: pointer; border: 2px solid #bbf7d0; background: #f0fdf4; transition: all 0.2s;'
        }, [
            hub.el('div', { style: 'font-size: 0.82rem; font-weight: 700; color: #16a34a; letter-spacing: 0.5px;' }, ['CÒN HÀNG']),
            hub.el('div', { id: 'mrInStockCount', style: 'font-size: 2rem; font-weight: 800; color: #16a34a; margin-top: 4px;' }, ['0'])
        ]);

        var cardLowStock = hub.el('div', {
            class: 'ws-card',
            style: 'padding: 16px 20px; border-radius: 10px; cursor: pointer; border: 2px solid #fed7aa; background: #fff7ed; transition: all 0.2s;'
        }, [
            hub.el('div', { style: 'font-size: 0.82rem; font-weight: 700; color: #ea580c; letter-spacing: 0.5px;' }, ['SẮP HẾT']),
            hub.el('div', { id: 'mrLowStockCount', style: 'font-size: 2rem; font-weight: 800; color: #ea580c; margin-top: 4px;' }, ['0'])
        ]);

        var cardOutOfStock = hub.el('div', {
            class: 'ws-card',
            style: 'padding: 16px 20px; border-radius: 10px; cursor: pointer; border: 2px solid #fecaca; background: #fef2f2; transition: all 0.2s;'
        }, [
            hub.el('div', { style: 'font-size: 0.82rem; font-weight: 700; color: #dc2626; letter-spacing: 0.5px;' }, ['HẾT HÀNG']),
            hub.el('div', { id: 'mrOutOfStockCount', style: 'font-size: 2rem; font-weight: 800; color: #dc2626; margin-top: 4px;' }, ['0'])
        ]);

        cardsContainer.appendChild(cardTotal);
        cardsContainer.appendChild(cardInStock);
        cardsContainer.appendChild(cardLowStock);
        cardsContainer.appendChild(cardOutOfStock);
        page.appendChild(cardsContainer);

        // Toolbar
        var toolbar = hub.el('div', { class: 'ws-list-toolbar', style: 'margin-bottom: 16px;' });
        var tbLeft = hub.el('div', { class: 'ws-list-toolbar-left', style: 'display: flex; gap: 10px; align-items: center; flex-wrap: wrap;' });

        // Dropdown Chi nhánh
        var branchSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Chi nhánh' });
        (branches || []).forEach(function (b) {
            var opt = hub.el('option', { value: String(b.id) }, [b.name]);
            if (String(b.id) === String(branchId)) opt.selected = true;
            branchSelect.appendChild(opt);
        });
        tbLeft.appendChild(branchSelect);

        // Input Tìm kiếm
        var searchWrap = hub.el('div', { class: 'ws-search' });
        searchWrap.appendChild(hub.el('span', { 'aria-hidden': 'true', innerHTML: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>' }));
        var searchInput = hub.el('input', {
            type: 'search',
            placeholder: 'Tìm nguyên liệu (mã hoặc tên)…',
            'aria-label': 'Tìm nguyên liệu'
        });
        searchWrap.appendChild(searchInput);
        tbLeft.appendChild(searchWrap);

        // Dropdown Nhóm
        var groupSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Nhóm' });
        [
            { value: '', label: 'Tất cả nhóm' },
            { value: 'BẮP', label: 'BẮP' },
            { value: 'ĐỒ UỐNG', label: 'ĐỒ UỐNG' },
            { value: 'BAO BÌ', label: 'BAO BÌ' },
            { value: 'GIA VỊ', label: 'GIA VỊ' }
        ].forEach(function (g) {
            groupSelect.appendChild(hub.el('option', { value: g.value }, [g.label]));
        });
        tbLeft.appendChild(groupSelect);

        // Dropdown Trạng thái
        var statusSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Trạng thái' });
        [
            { value: '', label: 'Tất cả trạng thái' },
            { value: 'IN_STOCK', label: 'Còn hàng' },
            { value: 'LOW_STOCK', label: 'Sắp hết' },
            { value: 'OUT_OF_STOCK', label: 'Hết hàng' }
        ].forEach(function (s) {
            statusSelect.appendChild(hub.el('option', { value: s.value }, [s.label]));
        });
        tbLeft.appendChild(statusSelect);

        // Dropdown Sắp xếp
        var sortSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Sắp xếp' });
        [
            { value: '', label: 'Tất cả sắp xếp' },
            { value: 'QTY_ASC', label: 'Tồn tăng' },
            { value: 'QTY_DESC', label: 'Tồn giảm' },
            { value: 'NAME_ASC', label: 'Tên A-Z' }
        ].forEach(function (opt) {
            sortSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
        });
        tbLeft.appendChild(sortSelect);
        toolbar.appendChild(tbLeft);

        // Thanh thao tác phía trên (Top Toolbar)
        var tbRight = hub.el('div', { class: 'ws-list-toolbar-right', style: 'display: flex; gap: 8px; align-items: center; flex-wrap: wrap;' });

        if (!isStaff) {
            var btnTopRestock = hub.el('button', {
                class: 'ws-btn primary',
                type: 'button',
                style: 'background: #16a34a; border-color: #16a34a; font-weight: 600;'
            }, ['+ Nhập Nguyên Liệu']);
            btnTopRestock.addEventListener('click', function () {
                var curBranchObj = (branches || []).find(function (b) { return String(b.id) === String(branchSelect.value); });
                var curBranchId = curBranchObj ? curBranchObj.id : branchId;
                var curBranchName = curBranchObj ? curBranchObj.name : '';
                showMaterialRestockModal(curBranchId, curBranchName, rawMaterials, loadData);
            });

            var btnTopAdjust = hub.el('button', {
                class: 'ws-btn secondary',
                type: 'button',
                style: 'font-weight: 600;'
            }, ['Điều chỉnh']);
            btnTopAdjust.addEventListener('click', function () {
                var curBranchObj = (branches || []).find(function (b) { return String(b.id) === String(branchSelect.value); });
                var curBranchId = curBranchObj ? curBranchObj.id : branchId;
                showMaterialAdjustModal(curBranchId, null, rawMaterials, loadData);
            });

            tbRight.appendChild(btnTopRestock);
            
            tbRight.appendChild(btnTopAdjust);
        }

        toolbar.appendChild(tbRight);

        var rawMaterials = [];
        page.appendChild(toolbar);

        // Bảng hiển thị 9 CỘT
        var tableCard = hub.el('div', { class: 'ws-card', style: 'padding: 0; overflow: hidden;' });
        var tableRegion = hub.el('div');
        tableCard.appendChild(tableRegion);
        page.appendChild(tableCard);

        targetRoot.replaceChildren(page);

        // Click các thẻ Summary Card để lọc nhanh
        cardTotal.addEventListener('click', function () {
            statusSelect.value = '';
            applyFilters();
        });
        cardInStock.addEventListener('click', function () {
            statusSelect.value = 'IN_STOCK';
            applyFilters();
        });
        cardLowStock.addEventListener('click', function () {
            statusSelect.value = 'LOW_STOCK';
            applyFilters();
        });
        cardOutOfStock.addEventListener('click', function () {
            statusSelect.value = 'OUT_OF_STOCK';
            applyFilters();
        });

        function highlightCard(selectedVal) {
            cardTotal.style.boxShadow = selectedVal === '' ? '0 0 0 2px #1d4ed8' : 'none';
            cardInStock.style.boxShadow = selectedVal === 'IN_STOCK' ? '0 0 0 2px #16a34a' : 'none';
            cardLowStock.style.boxShadow = selectedVal === 'LOW_STOCK' ? '0 0 0 2px #ea580c' : 'none';
            cardOutOfStock.style.boxShadow = selectedVal === 'OUT_OF_STOCK' ? '0 0 0 2px #dc2626' : 'none';
        }

        function loadData() {
            var activeBranch = branchSelect.value || branchId;
            tableRegion.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [
                hub.el('div', { class: 'ws-spinner' }),
                hub.el('span', {}, ['Đang tải dữ liệu nguyên liệu…'])
            ]));

            hub.api('/inventory?type=material&branchId=' + activeBranch)
                .then(function (resList) {
                    rawMaterials = resList || [];

                    // Cập nhật số liệu trên 4 Summary Cards
                    var total = rawMaterials.length;
                    var inStock = 0;
                    var lowStock = 0;
                    var outOfStock = 0;

                    rawMaterials.forEach(function (m) {
                        var q = m.qty != null ? m.qty : 0;
                        var minQ = m.minStock != null ? m.minStock : 0;
                        if (q <= 0) {
                            outOfStock++;
                        } else if (q <= minQ) {
                            lowStock++;
                        } else {
                            inStock++;
                        }
                    });

                    var elTotal = document.getElementById('mrTotalCount');
                    var elInStock = document.getElementById('mrInStockCount');
                    var elLowStock = document.getElementById('mrLowStockCount');
                    var elOutOfStock = document.getElementById('mrOutOfStockCount');
                    if (elTotal) elTotal.textContent = String(total);
                    if (elInStock) elInStock.textContent = String(inStock);
                    if (elLowStock) elLowStock.textContent = String(lowStock);
                    if (elOutOfStock) elOutOfStock.textContent = String(outOfStock);

                    applyFilters();
                })
                .catch(function (err) {
                    tableRegion.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Lỗi tải dữ liệu']),
                        hub.el('p', {}, [err.message || 'Không thể tải dữ liệu nguyên liệu. Vui lòng thử lại.'])
                    ]));
                });
        }

        function applyFilters() {
            var search = (searchInput.value || '').trim().toLowerCase();
            var searchNorm = search.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
            var catFilter = groupSelect.value;
            var stFilter = statusSelect.value;
            var sortVal = sortSelect.value;

            highlightCard(stFilter);

            var filtered = rawMaterials.filter(function (it) {
                // Lọc search
                if (search) {
                    var nameLower = (it.name || '').toLowerCase();
                    var nameNorm = nameLower.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
                    var codeLower = (it.code || '').toLowerCase();
                    var matchName = nameLower.indexOf(search) !== -1 || nameNorm.indexOf(searchNorm) !== -1;
                    var matchCode = codeLower.indexOf(search) !== -1;
                    if (!matchName && !matchCode) return false;
                }

                // Lọc nhóm
                if (catFilter) {
                    var c = (it.category || '').toUpperCase();
                    if (c.indexOf(catFilter.toUpperCase()) === -1) return false;
                }

                // Lọc trạng thái
                if (stFilter) {
                    var q = it.qty != null ? it.qty : 0;
                    var minQ = it.minStock != null ? it.minStock : 0;
                    if (stFilter === 'OUT_OF_STOCK' && q > 0) return false;
                    if (stFilter === 'LOW_STOCK' && (q <= 0 || q > minQ)) return false;
                    if (stFilter === 'IN_STOCK' && q <= minQ) return false;
                }

                return true;
            });

            // Sắp xếp
            if (sortVal === 'QTY_ASC') {
                filtered.sort(function (a, b) { return (a.qty != null ? a.qty : 0) - (b.qty != null ? b.qty : 0); });
            } else if (sortVal === 'QTY_DESC') {
                filtered.sort(function (a, b) { return (b.qty != null ? b.qty : 0) - (a.qty != null ? a.qty : 0); });
            } else if (sortVal === 'NAME_ASC') {
                filtered.sort(function (a, b) { return (a.name || '').localeCompare(b.name || ''); });
            }

            renderTable(filtered);
        }

        function renderTable(items) {
            if (!items.length) {
                tableRegion.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Chưa có nguyên liệu']),
                    hub.el('p', {}, ['Không tìm thấy nguyên liệu nào trong kho theo điều kiện lọc hiện tại.'])
                ]));
                return;
            }

            var table = hub.el('table', { class: 'ws-table', style: 'margin: 0; width: 100%; border-collapse: collapse;' });

            var chkAll = hub.el('input', { type: 'checkbox', style: 'width: 16px; height: 16px; cursor: pointer;' });
            var thead = hub.el('thead', {}, [
                hub.el('tr', {}, [
                    hub.el('th', { style: 'width: 40px; text-align: center;' }, [chkAll]),
                    hub.el('th', {}, ['Tên nguyên liệu']),
                    hub.el('th', {}, ['Phân loại']),
                    hub.el('th', { style: 'text-align: center;' }, ['Đơn vị tính']),
                    hub.el('th', { style: 'text-align: right;' }, ['Tồn kho']),
                    hub.el('th', { style: 'text-align: right;' }, ['Tồn tối thiểu']),
                    hub.el('th', {}, ['Cập nhật lúc']),
                    hub.el('th', { style: 'text-align: center;' }, ['Trạng thái']),
                    hub.el('th', { style: 'text-align: right; width: 100px;' }, ['Thao tác'])
                ])
            ]);

            var tbody = hub.el('tbody');
            var itemCheckboxes = [];

            items.forEach(function (s) {
                var q = s.qty != null ? s.qty : 0;
                var minQ = s.minStock != null ? s.minStock : 0;
                var unit = s.unit || '';
                var unitFormatted = formatMaterialUnit(unit);

                // Checkbox
                var rowChk = hub.el('input', { type: 'checkbox', style: 'width: 16px; height: 16px; cursor: pointer;' });
                itemCheckboxes.push(rowChk);

                // Tên + Mã nguyên liệu (click mở modal chi tiết)
                var nameEl = hub.el('div', {}, [
                    hub.el('div', {
                        style: 'font-weight: 700; color: #1d4ed8; cursor: pointer; text-decoration: underline;'
                    }, [s.name || ('NL #' + (s.materialId || s.id))]),
                    hub.el('small', { style: 'color: #64748b; font-size: 0.8rem;' }, ['Mã: ' + (s.code || '—')])
                ]);
                nameEl.addEventListener('click', function () {
                    showMaterialDetailModal(branchSelect.value || branchId, s, rawMaterials, loadData, isStaff);
                });

                // Phân loại / Nhóm
                var catBadge = hub.el('span', {
                    style: 'background: #f1f5f9; color: #334155; padding: 4px 10px; border-radius: 6px; font-size: 0.82rem; font-weight: 600;'
                }, [formatMaterialCategory(s.category)]);

                // Đơn vị tính (Badge căn giữa thanh lịch)
                var unitTd = hub.el('div', { style: 'text-align: center;' }, [
                    hub.el('span', {
                        style: 'display: inline-block; padding: 2px 10px; border-radius: 6px; background: #f8fafc; border: 1px solid #e2e8f0; font-size: 0.84rem; font-weight: 600; color: #475569;'
                    }, [unitFormatted])
                ]);

                // Tồn kho (Hiển thị số nổi bật kèm đơn vị chuẩn ERP)
                var qtyTd = hub.el('div', { style: 'text-align: right;' }, [
                    hub.el('strong', { style: 'color: #0f172a; font-size: 0.98rem; font-weight: 700;' }, [Number(q).toLocaleString('vi-VN')]),
                    hub.el('span', { style: 'color: #64748b; font-size: 0.82rem; font-weight: 500; margin-left: 4px;' }, [unitFormatted])
                ]);

                // Tồn tối thiểu
                var minTd = hub.el('div', { style: 'text-align: right;' }, [
                    hub.el('span', { style: 'color: #475569; font-size: 0.9rem; font-weight: 500;' }, [Number(minQ).toLocaleString('vi-VN')]),
                    hub.el('span', { style: 'color: #94a3b8; font-size: 0.78rem; margin-left: 3px;' }, [unitFormatted])
                ]);

                // Cập nhật
                var updatedTd = hub.el('span', { style: 'color: #64748b; font-size: 0.84rem; white-space: nowrap;' }, [s.updatedAt || '—']);

                // Trạng thái badge: Còn hàng / Sắp hết / Hết hàng
                var stBadge;
                if (q <= 0) {
                    stBadge = hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-red' }, ['Hết hàng']);
                } else if (q <= minQ) {
                    stBadge = hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-orange' }, ['Sắp hết']);
                } else {
                    stBadge = hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-green' }, ['Còn hàng']);
                }
                var stWrap = hub.el('div', { style: 'text-align: center;' }, [stBadge]);

                // Cột Thao tác: Nút Lịch sử
                var btnHist = hub.el('button', {
                    type: 'button',
                    class: 'ws-btn secondary',
                    style: 'padding: 4px 10px; font-size: 0.82rem; border-radius: 6px; cursor: pointer; white-space: nowrap;'
                }, ['Lịch sử']);
                btnHist.addEventListener('click', function () {
                    showMaterialDetailModal(branchSelect.value || branchId, s, rawMaterials, loadData, isStaff);
                });

                tbody.appendChild(hub.el('tr', {}, [
                    hub.el('td', { style: 'text-align: center;' }, [rowChk]),
                    hub.el('td', {}, [nameEl]),
                    hub.el('td', {}, [catBadge]),
                    hub.el('td', {}, [unitTd]),
                    hub.el('td', {}, [qtyTd]),
                    hub.el('td', {}, [minTd]),
                    hub.el('td', {}, [updatedTd]),
                    hub.el('td', {}, [stWrap]),
                    hub.el('td', { style: 'text-align: right;' }, [btnHist])
                ]));
            });

            chkAll.addEventListener('change', function () {
                var checked = chkAll.checked;
                itemCheckboxes.forEach(function (c) { c.checked = checked; });
            });

            table.appendChild(thead);
            table.appendChild(tbody);
            tableRegion.replaceChildren(table);
        }

        branchSelect.addEventListener('change', loadData);
        searchInput.addEventListener('input', applyFilters);
        groupSelect.addEventListener('change', applyFilters);
        statusSelect.addEventListener('change', applyFilters);
        sortSelect.addEventListener('change', applyFilters);

        loadData();
    }

    
    // ================================================================
    //  PHẦN 3: BỘ ĐIỀU HƯỚNG KHO HÀNG (ROUTER DISPATCHER: 2 MÀN)
    // ================================================================

    router.register('inventory', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            var isStaff = roleMeta === 'STAFF' || roleMeta === 'BRANCH_STAFF';
            var viewRoot = document.getElementById('viewRoot');
            if (!viewRoot) return;

            viewRoot.replaceChildren(hub.el('div', { class: 'ws-list-loading', style: 'padding: 48px 24px; text-align: center;' }, [
                hub.el('div', { class: 'ws-spinner' }),
                hub.el('span', { style: 'display: block; margin-top: 12px; color: #64748b; font-weight: 500;' }, ['Đang tải dữ liệu kho hàng…'])
            ]));

            hub.api(branchListApiPath(roleMeta)).then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                var activeTab = params.get('tab') === 'material' ? 'material' : 'product';
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
                    activeTab = newTab === 'material' ? 'material' : 'product';
                    var newUrl = window.location.pathname + '?module=inventory&tab=' + activeTab + (branchId ? '&branchId=' + branchId : '');
                    window.history.replaceState(null, '', newUrl);
                    syncSidebarSubmenu(activeTab);
                    if (activeTab === 'material') {
                        renderMaterialInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
                    } else {
                        renderProductInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
                    }
                }

                window.CinemaHubSwitchInventoryTab = switchTab;
                syncSidebarSubmenu(activeTab);

                if (activeTab === 'material') {
                    renderMaterialInventoryPage(viewRoot, branchId, branches, isStaff, switchTab);
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
            var params = new URLSearchParams(window.location.search);
            var branchId = params.get('branchId');
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
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
            var params = new URLSearchParams(window.location.search);
            var branchId = params.get('branchId');
            var productId = params.get('productId') || id;
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
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
