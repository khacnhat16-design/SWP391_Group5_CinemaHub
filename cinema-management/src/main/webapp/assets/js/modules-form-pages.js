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

  // ============================================================
    // Showtime Allocation — Admin phân bổ suất chiếu cho từng branch,
    // Manager xem & tạo suất chiếu để đáp ứng quota.
    // ============================================================
    router.register('showtime-allocation', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            if (!viewRoot) return;
            if (typeof hub.setWorkspaceHeader === 'function') {
                hub.setWorkspaceHeader('Phân bổ suất chiếu');
            }
            viewRoot.replaceChildren();

            Promise.all([
                hub.api('/branch'),
                hub.api('/movie')
            ]).then(function (results) {
                var branches = results[0] || [];
                var movies = results[1] || [];
                var branchNames = new Map(branches.map(function (branch) {
                    return [String(branch.id), branch.name];
                }));
                var movieNames = new Map(movies.map(function (movie) {
                    return [String(movie.id), movie.title];
                }));
                var roleMeta = document.querySelector('meta[name="user-role"]');
                var userRole = roleMeta ? roleMeta.content : '';
                var isAdmin = userRole === 'ADMIN';

                var header = hub.el('div', { class: 'ws-page-header' }, [
                    hub.el('div', {}, [
                        hub.el('h1', {}, ['Phân bổ suất chiếu']),
                        hub.el('p', { class: 'ws-tone-muted' }, [
                            'Admin phân bổ tổng số suất chiếu cho phim tại chi nhánh, gộp tất cả phòng. ',
                            'Mỗi suất chiếu được tạo sẽ dùng một slot; hủy suất sẽ hoàn lại slot.'
                        ])
                    ])
                ]);
                viewRoot.appendChild(header);

                // Filter bar
                var filterWrap = hub.el('div', { class: 'ws-card', style: 'margin-bottom:12px' });
                var filterRow = hub.el('div', { class: 'ws-form-row' });
                var statusSelect = hub.el('select', { class: 'ws-input' }, [
                    hub.el('option', { value: '' }, ['-- Tất cả trạng thái --']),
                    hub.el('option', { value: 'PENDING' }, ['PENDING (chờ)']),
                    hub.el('option', { value: 'IN_PROGRESS' }, ['IN_PROGRESS (đang làm)']),
                    hub.el('option', { value: 'COMPLETED' }, ['COMPLETED (đủ)']),
                    hub.el('option', { value: 'OVER_ALLOCATED' }, ['OVER_ALLOCATED (vượt)'])
                ]);
                var branchSelect = hub.el('select', { class: 'ws-input' });
                branchSelect.appendChild(hub.el('option', { value: '' }, ['-- Tất cả chi nhánh --']));
                branches.forEach(function (b) {
                    branchSelect.appendChild(hub.el('option', { value: String(b.id) }, [b.name]));
                });
                var movieSelect = hub.el('select', { class: 'ws-input' });
                movieSelect.appendChild(hub.el('option', { value: '' }, ['-- Tất cả phim --']));
                movies.forEach(function (m) {
                    movieSelect.appendChild(hub.el('option', { value: String(m.id) }, [m.title]));
                });
                var queryParams = new URLSearchParams(window.location.search);
                var requestedBranch = queryParams.get('branchId');
                var requestedMovie = queryParams.get('movieId');
                if (/^\d+$/.test(requestedBranch || '')
                        && branches.some(function (branch) {
                            return String(branch.id) === requestedBranch;
                        })) {
                    branchSelect.value = requestedBranch;
                }
                if (/^\d+$/.test(requestedMovie || '')
                        && movies.some(function (movie) {
                            return String(movie.id) === requestedMovie;
                        })) {
                    movieSelect.value = requestedMovie;
                }
                if (!isAdmin) {
                    branchSelect.disabled = true;
                }
                filterRow.appendChild(hub.el('div', { class: 'ws-form-col' }, [
                    hub.el('label', {}, ['Trạng thái']), statusSelect
                ]));
                filterRow.appendChild(hub.el('div', { class: 'ws-form-col' }, [
                    hub.el('label', {}, ['Chi nhánh']), branchSelect
                ]));
                filterRow.appendChild(hub.el('div', { class: 'ws-form-col' }, [
                    hub.el('label', {}, ['Phim']), movieSelect
                ]));
                filterWrap.appendChild(filterRow);
                if (isAdmin) {
                    var addBtn = hub.el('button', {
                        class: 'ws-btn primary',
                        type: 'button',
                        style: 'margin-top:12px'
                    }, ['+ Tạo phân bổ mới']);
                    addBtn.addEventListener('click', function () { openCreateDialog(); });
                    filterWrap.appendChild(addBtn);
                }
                viewRoot.appendChild(filterWrap);

                var listCard = hub.el('article', { class: 'ws-card' });
                listCard.appendChild(hub.el('h2', {}, ['Danh sách phân bổ']));
                var listWrap = hub.el('div', {});
                listCard.appendChild(listWrap);
                viewRoot.appendChild(listCard);

                function buildQuery() {
                    var qs = new URLSearchParams();
                    if (statusSelect.value) qs.append('status', statusSelect.value);
                    if (branchSelect.value) qs.append('branchId', branchSelect.value);
                    if (movieSelect.value) qs.append('movieId', movieSelect.value);
                    return qs.toString();
                }

                function load() {
                    var qs = buildQuery();
                    var path = '/api/showtime-allocations' + (qs ? '?' + qs : '');
                    hub.api(path).then(function (resp) {
                        var data = resp && resp.data ? resp.data : resp;
                        var items = (data && data.items) || [];
                        listWrap.replaceChildren();
                        if (!items.length) {
                            listWrap.appendChild(hub.el('div', { class: 'ws-empty' }, [
                                hub.el('p', {}, ['Chưa có phân bổ nào.'])
                            ]));
                            return;
                        }
                        var table = hub.el('table', { class: 'ws-table' });
                        var thead = hub.el('thead', {}, [hub.el('tr', {}, [
                            hub.el('th', {}, ['Phim']),
                            hub.el('th', {}, ['Chi nhánh']),
                            hub.el('th', {}, ['Phân bổ']),
                            hub.el('th', {}, ['Đã tạo']),
                            hub.el('th', {}, ['Còn thiếu']),
                            hub.el('th', {}, ['Trạng thái']),
                            hub.el('th', {}, ['Ngày phân bổ']),
                            hub.el('th', {}, ['Thao tác'])
                        ])]);
                        table.appendChild(thead);
                        var tbody = hub.el('tbody');
                        items.forEach(function (a) {
                            var row = hub.el('tr');
                            var remaining = a.remainingQuantity;
                            var remainingCell = remaining > 0
                                ? hub.el('span', { class: 'ws-tone-warn' }, [String(remaining)])
                                : remaining < 0
                                    ? hub.el('span', { class: 'ws-tone-danger' },
                                        [(remaining) + ' (vượt)'])
                                    : hub.el('span', { class: 'ws-tone-ok' }, ['0']);
                            row.appendChild(hub.el('td', {}, [
                                a.movieTitle || movieNames.get(String(a.movieId)) || '—'
                            ]));
                            row.appendChild(hub.el('td', {}, [
                                a.branchName || branchNames.get(String(a.branchId)) || '—'
                            ]));
                            row.appendChild(hub.el('td', {}, [String(a.allocatedQuantity)]));
                            row.appendChild(hub.el('td', {}, [String(a.createdQuantity)]));
                            row.appendChild(hub.el('td', {}, [remainingCell]));
                            row.appendChild(hub.el('td', {}, [statusBadge(a.status)]));
                            row.appendChild(hub.el('td', {}, [a.allocatedAt
                                ? hub.fmtDateTime(a.allocatedAt) : '—']));
                            var actions = hub.el('div', { class: 'ws-action-group' });
                            if (isAdmin) {
                                var editBtn = hub.el('button', {
                                    class: 'ws-btn secondary small', type: 'button'
                                }, ['Sửa SL']);
                                editBtn.addEventListener('click', function () {
                                    openEditDialog(a);
                                });
                                actions.appendChild(editBtn);
                                if (a.createdQuantity === 0) {
                                    var delBtn = hub.el('button', {
                                        class: 'ws-btn danger small', type: 'button'
                                    }, ['Xóa']);
                                    delBtn.addEventListener('click', function () {
                                        confirmAction('Xóa phân bổ #' + a.id + '?', function () {
                                            hub.api('/api/showtime-allocations/' + a.id,
                                                { method: 'DELETE' })
                                                .then(function () {
                                                    hub.notify('Đã xóa phân bổ.', 'ok');
                                                    load();
                                                })
                                                .catch(notifyError);
                                        });
                                    });
                                    actions.appendChild(delBtn);
                                }
                            } else {
                                // Manager chỉ tạo thêm Showtime khi allocation còn quota.
                                var remainingQuota = remaining != null && remaining !== ''
                                    && Number.isFinite(Number(remaining))
                                    ? Number(remaining)
                                    : Number(a.allocatedQuantity) - Number(a.createdQuantity);
                                if (remainingQuota > 0) {
                                    var createShowtime = hub.el('a', {
                                        class: 'ws-btn primary small',
                                        href: ctx + '/console?module=showtime&action=create'
                                            + '&movieId=' + a.movieId
                                            + '&branchId=' + a.branchId
                                    }, ['+ Tạo suất chiếu']);
                                    actions.appendChild(createShowtime);
                                }
                            }
                            row.appendChild(hub.el('td', {}, [actions]));
                            tbody.appendChild(row);
                        });
                        table.appendChild(tbody);
                        listWrap.appendChild(table);
                    }).catch(notifyError);
                }

<<<<<<< HEAD
    function renderAllocationForm(allocation, movies, branches) {
        var editing = !!allocation;
        var fields = editing ? [
            { name: 'allocatedQuantity', label: 'Số suất phân bổ', type: 'number',
              min: 0, required: true, value: allocation.allocatedQuantity },
            { name: 'note', label: 'Ghi chú', type: 'textarea', required: false,
              value: allocation.note || '', rows: 3 }
        ] : [
            { name: 'movieId', label: 'Phim', type: 'select', required: true,
              options: movies.map(function (movie) {
                  return { value: String(movie.id), label: movie.title };
              }) },
            { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true,
              options: branches.map(function (branch) {
                  return { value: String(branch.id), label: branch.name };
              }) },
            { name: 'allocatedQuantity', label: 'Số suất phân bổ', type: 'number',
              min: 0, required: true, value: 1 },
            { name: 'note', label: 'Ghi chú', type: 'textarea', required: false, rows: 3 }
        ];

        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phân bổ suất chiếu',
                href: ctx + '/console?module=showtime-allocation' },
                editing ? 'Cập nhật' : 'Tạo mới'],
            title: editing ? 'Cập nhật phân bổ' : 'Tạo phân bổ suất chiếu',
            subtitle: editing ? (allocation.movieTitle || 'Allocation #' + allocation.id)
                : 'Gán số suất chiếu cho một phim tại một chi nhánh.',
            submitLabel: editing ? 'Lưu thay đổi' : 'Tạo phân bổ',
            sections: [{ title: 'Thông tin phân bổ', fields: fields }],
            onSubmit: function (data) {
                return hub.api(editing ? '/api/showtime-allocations/' + allocation.id
                    : '/api/showtime-allocations', {
                    method: editing ? 'PUT' : 'POST', body: data
                }).then(function () {
                    hub.notify(editing ? 'Đã cập nhật phân bổ.' : 'Đã tạo phân bổ.', 'ok');
                    router.go('showtime-allocation');
                }).catch(notifyError);
            },
            onCancel: function () { router.go('showtime-allocation'); }
        });
    }

    router.register('showtime-allocation', {
        list: function () {
            var root = document.getElementById('viewRoot');
            if (!root) return;
            Promise.all([hub.api('/branch'), hub.api('/movie')]).then(function (results) {
                var branches = Array.isArray(results[0]) ? results[0] : [];
                var movies = Array.isArray(results[1]) ? results[1] : [];
                var role = (document.querySelector('meta[name="user-role"]') || {}).content;
                var isAdmin = role === 'ADMIN';
                root.replaceChildren();
                root.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                    hub.el('div', {}, [
                        hub.el('h1', {}, ['Phân bổ suất chiếu']),
                        hub.el('p', { class: 'ws-tone-muted' }, [
                            'Theo dõi số suất phân bổ và số suất thực tế theo phim, chi nhánh.'
                        ])
                    ])
                ]));

                var filters = hub.el('section', { class: 'ws-card' });
                var filterRow = hub.el('div', { class: 'ws-form-row' });
                var status = hub.el('select', { class: 'ws-input' });
                [['', 'Tất cả trạng thái'], ['PENDING', 'Chờ xếp lịch'],
                    ['IN_PROGRESS', 'Đang thực hiện'], ['COMPLETED', 'Đã đủ'],
                    ['OVER_ALLOCATED', 'Vượt phân bổ']].forEach(function (option) {
                    status.appendChild(hub.el('option', { value: option[0] }, [option[1]]));
                });
                var branch = hub.el('select', { class: 'ws-input' });
                branch.appendChild(hub.el('option', { value: '' }, ['Tất cả chi nhánh']));
                branches.forEach(function (item) {
                    branch.appendChild(hub.el('option', { value: String(item.id) }, [item.name]));
                });
                var movie = hub.el('select', { class: 'ws-input' });
                movie.appendChild(hub.el('option', { value: '' }, ['Tất cả phim']));
                movies.forEach(function (item) {
                    movie.appendChild(hub.el('option', { value: String(item.id) }, [item.title]));
                });
                var params = new URLSearchParams(window.location.search);
                if (params.has('branchId')) branch.value = params.get('branchId');
                if (params.has('movieId')) movie.value = params.get('movieId');
                if (!isAdmin) branch.disabled = true;
                [['Trạng thái', status], ['Chi nhánh', branch], ['Phim', movie]]
                    .forEach(function (filter) {
                        filterRow.appendChild(hub.el('div', { class: 'ws-form-col' }, [
                            hub.el('label', {}, [filter[0]]), filter[1]
                        ]));
                    });
                filters.appendChild(filterRow);
                if (isAdmin) {
                    var add = hub.el('button', { class: 'ws-btn primary', type: 'button' },
                        ['Tạo phân bổ']);
                    add.addEventListener('click', function () {
                        renderAllocationForm(null, movies, branches);
                    });
                    filters.appendChild(add);
                }
                root.appendChild(filters);

                var card = hub.el('section', { class: 'ws-card' }, [
                    hub.el('h2', {}, ['Danh sách phân bổ'])
                ]);
                var listRoot = hub.el('div');
                card.appendChild(listRoot);
                root.appendChild(card);

                function load() {
                    var query = new URLSearchParams();
                    if (status.value) query.set('status', status.value);
                    if (branch.value) query.set('branchId', branch.value);
                    if (movie.value) query.set('movieId', movie.value);
                    var suffix = query.toString();
                    hub.api('/api/showtime-allocations' + (suffix ? '?' + suffix : ''))
                        .then(function (response) {
                            var items = response && response.items ? response.items : [];
                            listRoot.replaceChildren();
                            if (!items.length) {
                                listRoot.appendChild(hub.el('div', { class: 'ws-empty' }, [
                                    hub.el('p', {}, ['Chưa có phân bổ nào.'])
                                ]));
                                return;
                            }
                            var table = hub.el('table', { class: 'ws-table' });
                            table.appendChild(hub.el('thead', {}, [hub.el('tr', {}, [
                                'Phim', 'Chi nhánh', 'Phân bổ', 'Đã tạo', 'Còn thiếu',
                                'Trạng thái', 'Ngày phân bổ', 'Thao tác'
                            ].map(function (label) { return hub.el('th', {}, [label]); }))]));
                            var body = hub.el('tbody');
                            items.forEach(function (item) {
                                var remaining = Number(item.allocatedQuantity)
                                    - Number(item.createdQuantity);
                                var remainingTone = remaining < 0 ? 'ws-tone-danger'
                                    : remaining > 0 ? 'ws-tone-warn' : 'ws-tone-ok';
                                var actions = hub.el('div', { class: 'ws-action-group' });
                                if (isAdmin) {
                                    var edit = hub.el('button', {
                                        class: 'ws-btn secondary small', type: 'button'
                                    }, ['Sửa']);
                                    edit.addEventListener('click', function () {
                                        renderAllocationForm(item, movies, branches);
                                    });
                                    actions.appendChild(edit);
                                    if (Number(item.createdQuantity) === 0) {
                                        var remove = hub.el('button', {
                                            class: 'ws-btn danger small', type: 'button'
                                        }, ['Xóa']);
                                        remove.addEventListener('click', function () {
                                            confirmAction('Xóa phân bổ này?', function () {
                                                hub.api('/api/showtime-allocations/' + item.id,
                                                    { method: 'DELETE' }).then(load).catch(notifyError);
                                            });
                                        });
                                        actions.appendChild(remove);
                                    }
                                }
                                var history = hub.el('button', {
                                    class: 'ws-btn secondary small', type: 'button'
                                }, ['Lịch sử']);
                                history.addEventListener('click', function () {
                                    hub.api('/api/showtime-allocations/' + item.id + '/history')
                                        .then(function (result) {
                                            var entries = result && result.items ? result.items : [];
                                            var lines = entries.map(function (entry) {
                                                return [entry.eventType, entry.note, entry.createdAt]
                                                    .filter(Boolean).join(' · ');
                                            });
                                            window.alert(lines.length ? lines.join('\n')
                                                : 'Chưa có lịch sử thay đổi.');
                                        }).catch(notifyError);
                                });
                                actions.appendChild(history);
                                var values = [
                                    item.movieTitle || '—', item.branchName || '—',
                                    String(item.allocatedQuantity), String(item.createdQuantity),
                                    hub.el('span', { class: remainingTone }, [String(remaining)]),
                                    statusBadge(item.status),
                                    item.allocatedAt ? hub.fmtDateTime(item.allocatedAt) : '—',
                                    actions
                                ];
                                body.appendChild(hub.el('tr', {}, values.map(function (value) {
                                    return hub.el('td', {}, [value]);
                                })));
                            });
                            table.appendChild(body);
                            listRoot.appendChild(table);
                        }).catch(notifyError);
                }

                [status, branch, movie].forEach(function (control) {
                    control.addEventListener('change', load);
                });
                load();
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  PRODUCT (F&B Catalog Admin)
    // ================================================================
    function renderProductForm(product) {
        var isEdit = !!product;
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Sản phẩm F&B', href: ctx + '/console?module=product' }, isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa sản phẩm' : 'Tạo sản phẩm mới',
            subtitle: isEdit ? product.name : 'Thêm sản phẩm hoặc combo bắp nước.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo sản phẩm',
            sections: [{
                title: 'Thông tin sản phẩm',
                fields: [
                    { name: 'name', label: 'Tên sản phẩm', required: true, value: product && product.name, fullWidth: true },
                    { name: 'description', label: 'Mô tả', type: 'textarea', required: false, value: product && product.description, rows: 3, fullWidth: true },
                    { name: 'price', label: 'Giá bán (VND)', type: 'number', required: true, min: 0, step: 1000, value: product && product.price },
                    { name: 'unit', label: 'Đơn vị', value: (product && product.unit) || 'phần', placeholder: 'phần, ly, hộp…' },
                    { name: 'type', label: 'Loại', type: 'select', required: true, value: (product && product.type) || 'POPCORN', options: [
                        { value: 'POPCORN', label: 'Bắp rang' },
                        { value: 'DRINK', label: 'Nước uống' },
                        { value: 'COMBO', label: 'Combo' },
                        { value: 'OTHER', label: 'Khác' }
                    ]}
                ]
            }],
            onSubmit: function (data) {
                var url = isEdit ? '/concession/products/' + product.id : '/concession/products';
                var method = isEdit ? 'PUT' : 'POST';
                hub.api(url, { method: method, body: data })
                    .then(function () { router.go('product'); })
                    .catch(notifyError);
            },
            onCancel: function () { router.go('product'); }
        });
    }
=======
                [statusSelect, branchSelect, movieSelect].forEach(function (el) {
                    el.addEventListener('change', load);
                });
>>>>>>> develop

                function openDialog(opts) {
                    var previousFocus = document.activeElement;
                    var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
                    var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
                    var panel = hub.el('div', {
                        class: 'ws-modal-panel', role: 'dialog',
                        'aria-modal': 'true'
                    });
                    panel.style.maxWidth = '560px';
                    var head = hub.el('div', { class: 'ws-modal-head' }, [
                        hub.el('h2', {}, [opts.title || 'Thao tác'])
                    ]);
                    var body = hub.el('div', { class: 'ws-modal-body' });
                    if (typeof opts.body === 'string') {
                        body.appendChild(hub.el('p', {}, [opts.body]));
                    } else if (opts.body instanceof Node) {
                        body.appendChild(opts.body);
                    }
                    var cancelBtn = hub.el('button', {
                        class: 'ws-btn secondary', type: 'button'
                    }, ['Huỷ']);
                    var confirmBtn = hub.el('button', {
                        class: 'ws-btn primary', type: 'button'
                    }, [opts.confirmLabel || 'Xác nhận']);
                    var footer = hub.el('div', { class: 'ws-form-actions' }, [cancelBtn, confirmBtn]);
                    panel.appendChild(head);
                    panel.appendChild(body);
                    panel.appendChild(footer);
                    overlay.appendChild(backdrop);
                    overlay.appendChild(panel);
                    document.body.appendChild(overlay);
                    function close() {
                        document.body.removeChild(overlay);
                        if (previousFocus && previousFocus.focus) {
                            try { previousFocus.focus(); } catch (e) { /* ignore */ }
                        }
                    }
                    cancelBtn.addEventListener('click', close);
                    backdrop.addEventListener('click', close);
                    confirmBtn.addEventListener('click', function () {
                        try {
                            if (typeof opts.onConfirm === 'function') {
                                opts.onConfirm();
                            }
                            close();
                        } catch (e) {
                            // leave dialog open if caller throws — but our callers
                            // don't throw, so this is just safety.
                        }
                    });
                }

                function openCreateDialog() {
                    if (!branches.length || !movies.length) {
                        hub.notify('Cần có chi nhánh và phim trước khi tạo phân bổ.', 'warn');
                        return;
                    }
                    var movieSel = hub.el('select', { class: 'ws-input' });
                    movies.forEach(function (m) {
                        movieSel.appendChild(hub.el('option', { value: String(m.id) },
                            [m.title]));
                    });
                    var branchSel = hub.el('select', { class: 'ws-input' });
                    branches.forEach(function (b) {
                        branchSel.appendChild(hub.el('option', { value: String(b.id) },
                            [b.name]));
                    });
                    var qtyInput = hub.el('input', {
                        class: 'ws-input', type: 'number', min: '1',
                        value: '1', required: 'required'
                    });
                    var noteInput = hub.el('textarea', {
                        class: 'ws-input', rows: '2',
                        placeholder: 'Ghi chú (tuỳ chọn)'
                    });
                    var formEl = hub.el('div', {}, [
                        hub.el('div', { class: 'ws-form-row' }, [
                            hub.el('div', { class: 'ws-form-col' }, [
                                hub.el('label', {}, ['Phim']), movieSel
                            ]),
                            hub.el('div', { class: 'ws-form-col' }, [
                                hub.el('label', {}, ['Chi nhánh']), branchSel
                            ])
                        ]),
                        hub.el('div', { class: 'ws-form-row' }, [
                            hub.el('div', { class: 'ws-form-col' }, [
                                hub.el('label', {}, ['Số suất phân bổ']), qtyInput
                            ])
                        ]),
                        hub.el('div', { class: 'ws-form-row' }, [
                            hub.el('div', { class: 'ws-form-col' }, [
                                hub.el('label', {}, ['Ghi chú']), noteInput
                            ])
                        ])
                    ]);
                    openDialog({
                        title: 'Tạo phân bổ suất chiếu',
                        body: formEl,
                        confirmLabel: 'Tạo phân bổ',
                        onConfirm: function () {
                            var body = {
                                movieId: movieSel.value,
                                branchId: branchSel.value,
                                allocatedQuantity: qtyInput.value,
                                note: noteInput.value
                            };
                            hub.api('/api/showtime-allocations', {
                                method: 'POST', body: body
                            }).then(function () {
                                hub.notify('Đã tạo phân bổ — Manager sẽ nhận thông báo.', 'ok');
                                load();
                            }).catch(notifyError);
                        }
                    });
                }

                function openEditDialog(a) {
                    var qtyInput = hub.el('input', {
                        class: 'ws-input', type: 'number', min: '0',
                        value: String(a.allocatedQuantity), required: 'required'
                    });
                    var noteInput = hub.el('textarea', {
                        class: 'ws-input', rows: '2',
                        placeholder: 'Lý do cập nhật (tuỳ chọn)'
                    });
                    noteInput.value = a.note || '';
                    var body = hub.el('div', {}, [
                        hub.el('p', { class: 'ws-tone-muted' }, [
                            'Phân bổ hiện tại: ', String(a.allocatedQuantity),
                            ' — Đã tạo: ', String(a.createdQuantity)
                        ]),
                        hub.el('div', { class: 'ws-form-row' }, [
                            hub.el('div', { class: 'ws-form-col' }, [
                                hub.el('label', {}, ['Số suất phân bổ mới']), qtyInput
                            ])
                        ]),
                        hub.el('div', { class: 'ws-form-row' }, [
                            hub.el('div', { class: 'ws-form-col' }, [
                                hub.el('label', {}, ['Ghi chú']), noteInput
                            ])
                        ])
                    ]);
                    openDialog({
                        title: 'Cập nhật phân bổ #' + a.id,
                        body: body,
                        confirmLabel: 'Cập nhật',
                        onConfirm: function () {
                            hub.api('/api/showtime-allocations/' + a.id, {
                                method: 'PUT', body: {
                                    allocatedQuantity: qtyInput.value,
                                    note: noteInput.value
                                }
                            }).then(function () {
                                hub.notify('Đã cập nhật.', 'ok');
                                load();
                            }).catch(notifyError);
                        }
                    });
                }

                load();
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
