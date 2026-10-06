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


     // ================================================================
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
                    var hasLegacyAllocationText = list.some(function (n) {
                        return /^SHOWTIME_ALLOCATION_/.test(n.type || '')
                            && /(?:movie|chi nhánh)\s+#\d+/i.test(n.body || '');
                    });
                    var references = hasLegacyAllocationText
                        ? Promise.all([hub.api('/movie'), hub.api('/branch')]).then(function (results) {
                            var movies = {};
                            var branches = {};
                            (results[0] || []).forEach(function (movie) {
                                movies[String(movie.id)] = movie.title;
                            });
                            (results[1] || []).forEach(function (branch) {
                                branches[String(branch.id)] = branch.name;
                            });
                            return { movies: movies, branches: branches };
                        })
                        : Promise.resolve({ movies: {}, branches: {} });
                    return references.then(function (names) {
                        var table = hub.el('table', { class: 'ws-table' });
                        table.innerHTML = '<thead><tr><th>Nội dung</th><th>Thời điểm</th><th>Trạng thái</th><th></th></tr></thead>';
                        var tbody = hub.el('tbody');
                        list.forEach(function (n) {
                            var tr = hub.el('tr');
                            var action = hub.showtimeScheduleReminderAction(n);
                            var body = action ? action.body : (n.body || n.title || '—');
                            body = body.replace(/\s*·\s*\/console\?[^\s]*\s*$/, '');
                            if (/^SHOWTIME_ALLOCATION_/.test(n.type || '')) {
                                body = body.replace(/movie\s+#(\d+)/gi, function (match, id) {
                                    return names.movies[id] ? 'phim ' + names.movies[id] : match;
                                });
                                body = body.replace(/chi nhánh\s+#(\d+)/gi, function (match, id) {
                                    if (!names.branches[id]) return match;
                                    var label = 'chi nhánh ' + names.branches[id];
                                    return match.charAt(0) === match.charAt(0).toUpperCase()
                                        ? 'Chi nhánh ' + names.branches[id] : label;
                                });
                                body = body.replace(
                                    'Vui lòng tạo Time Sheet và Room Seat tương ứng.',
                                    'Vui lòng tạo lịch chiếu và phòng chiếu tương ứng.');
                            }
                            var contentCell = hub.el('td');
                            if (action) {
                                contentCell.appendChild(hub.el('span', {
                                    class: 'notification-severity '
                                        + (action.urgent ? 'urgent' : 'normal')
                                }, [action.urgent ? 'Khẩn cấp' : 'Cần xếp lịch']));
                            }
                            contentCell.appendChild(document.createTextNode(body));
                            tr.appendChild(contentCell);
                            tr.appendChild(hub.el('td', {}, [
                                n.createdAt ? hub.fmtNotificationDateTime(n.createdAt) : '—'
                            ]));
                            tr.appendChild(hub.el('td', {}, [statusBadge(n.read ? 'USED' : 'PENDING')]));
                            var actions = hub.el('td', { class: 'row-actions' });
                            if (action) {
                                actions.appendChild(hub.el('a', {
                                    class: 'ws-btn ws-btn-sm primary',
                                    href: action.href
                                }, ['Xếp lịch ngay']));
                            }
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
                    });
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
})();
