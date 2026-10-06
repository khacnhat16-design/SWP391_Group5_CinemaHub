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
                var initialSeats = (screen && screen.seats) || [];

                function redrawSeatPicker() {
                    var rowInput = formEl.querySelector('input[name="rowCount"]');
                    var colInput = formEl.querySelector('input[name="colCount"]');
                    if (!rowInput || !colInput) return;
                    if (hidden.value) {
                        try { initialSeats = JSON.parse(hidden.value); } catch (_) {
                            throw new Error('Không đọc được sơ đồ ghế hiện tại.');
                        }
                    }
                    currentRows = Math.max(1, Math.min(26, parseInt(rowInput.value, 10) || 1));
                    currentCols = Math.max(1, Math.min(30, parseInt(colInput.value, 10) || 1));
                    renderSeatLayoutPicker(seatHost, hidden, currentRows, currentCols, initialSeats);
                }

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
})();
