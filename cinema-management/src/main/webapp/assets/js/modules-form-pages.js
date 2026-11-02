/**
 * modules-form-pages.js — Đăng ký form-pages cho tất cả modules (Promise-chain style).
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
        'CLOSED': 'gray', 'DRAFT': 'gray', 'PUBLISHED': 'green', 'ARCHIVED': 'gray',
        'IN_PROGRESS': 'orange', 'COMPLETED': 'green', 'OVER_ALLOCATED': 'red'
    };

    function statusBadge(label) {
        var tone = STATUS_MAP[label] || 'gray';
        var wrap = document.createElement('span');
        wrap.className = 'ws-badge ws-status-pill ws-badge-' + tone;
        wrap.textContent = label || '';
        return wrap;
    }

    function confirmAction(message, onYes) {
        var previousFocus = document.activeElement;
        var overlay = hub.el('div', {
            class: 'ws-modal-overlay',
            role: 'presentation'
        });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel',
            role: 'alertdialog',
            'aria-modal': 'true',
            'aria-labelledby': 'ws-confirm-title',
            'aria-describedby': 'ws-confirm-message'
        });
        panel.style.maxWidth = '440px';
        var head = hub.el('div', { class: 'ws-modal-head' }, [
            hub.el('h2', { id: 'ws-confirm-title' }, ['Xác nhận thao tác'])
        ]);
        var body = hub.el('div', { class: 'ws-modal-body' }, [
            hub.el('p', { id: 'ws-confirm-message' }, [message])
        ]);
        var cancel = hub.el('button', {
            class: 'ws-btn secondary',
            type: 'button'
        }, ['Huỷ']);
        var confirm = hub.el('button', {
            class: 'ws-btn danger',
            type: 'button'
        }, ['Xác nhận']);
        var footer = hub.el('div', { class: 'ws-form-actions' }, [cancel, confirm]);
        var closed = false;

        function close() {
            if (closed) return;
            closed = true;
            document.removeEventListener('keydown', onKeyDown);
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
            if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
        }

        function onKeyDown(event) {
            if (event.key === 'Escape') close();
        }

        cancel.addEventListener('click', close);
        confirm.addEventListener('click', function () {
            if (closed) return;
            close();
            onYes();
        });
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay || event.target === backdrop) close();
        });
        panel.appendChild(head);
        panel.appendChild(body);
        panel.appendChild(footer);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);
        document.addEventListener('keydown', onKeyDown);
        cancel.focus();
    }

    // Dùng chung modal xác nhận cho các module riêng (modules-voucher.js…).
    window.CinemaHubConfirm = confirmAction;

    function openShowtimeBookings(showtime) {
        var previousFocus = document.activeElement;
        var pageSize = 30;
        var offset = 0;
        var overlay = hub.el('div', { class: 'ws-modal-overlay', role: 'presentation' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', {
            class: 'ws-modal-panel ws-showtime-bookings-modal',
            role: 'dialog',
            'aria-modal': 'true',
            'aria-labelledby': 'showtime-bookings-title'
        });
        var title = [showtime.movieTitle || 'Suất chiếu', 'Ghế đã đặt'].join(' · ');
        var closeButton = hub.el('button', {
            class: 'ws-modal-x',
            type: 'button',
            'aria-label': 'Đóng danh sách ghế đã đặt'
        }, ['×']);
        var heading = hub.el('div', { class: 'ws-modal-head' }, [
            hub.el('h2', { id: 'showtime-bookings-title' }, [title]),
            closeButton
        ]);
        var body = hub.el('div', { class: 'ws-modal-body' });
        var details = [
            showtime.branchName,
            showtime.screenName,
            showtime.startTime ? hub.fmtDateTime(showtime.startTime) : null,
            showtime.endTime ? 'Kết thúc ' + hub.fmtDateTime(showtime.endTime) : null
        ].filter(Boolean).join(' · ');
        body.appendChild(hub.el('p', { class: 'ws-showtime-bookings-meta' }, [
            details || 'Danh sách vé và ghế của suất chiếu này.'
        ]));
        body.appendChild(hub.el('h3', { class: 'ws-showtime-seat-map-title' }, [
            'Sơ đồ ghế phòng chiếu'
        ]));
        var seatMapStatus = hub.el('p', {
            class: 'ws-showtime-bookings-status',
            role: 'status'
        }, ['Đang tải sơ đồ ghế…']);
        body.appendChild(seatMapStatus);
        var seatMap = hub.el('div', { class: 'ws-showtime-seat-map' });
        body.appendChild(seatMap);
        var status = hub.el('p', { class: 'ws-showtime-bookings-status', role: 'status' }, ['Đang tải danh sách ghế…']);
        body.appendChild(status);
        var tableWrap = hub.el('div', { class: 'ws-table-wrap ws-showtime-bookings-table-wrap' });
        body.appendChild(tableWrap);
        var pagination = hub.el('div', { class: 'ws-showtime-bookings-pagination' });
        var pageLabel = hub.el('span', { class: 'ws-muted' });
        var previousButton = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Trước']);
        var nextButton = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Tiếp']);
        pagination.appendChild(pageLabel);
        pagination.appendChild(previousButton);
        pagination.appendChild(nextButton);
        body.appendChild(pagination);

        var closed = false;
        function close() {
            if (closed) return;
            closed = true;
            document.removeEventListener('keydown', onKeyDown);
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
            if (previousFocus && typeof previousFocus.focus === 'function') previousFocus.focus();
        }
        function onKeyDown(event) {
            if (event.key === 'Escape') close();
        }
        closeButton.addEventListener('click', close);
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay || event.target === backdrop) close();
        });
        previousButton.addEventListener('click', function () {
            if (offset >= pageSize) {
                offset -= pageSize;
                loadBookings();
            }
        });
        nextButton.addEventListener('click', function () {
            offset += pageSize;
            loadBookings();
        });
        pagination.hidden = true;

        function loadShowtimeSeatMap() {
            hub.api('/showtime/' + encodeURIComponent(String(showtime.id))).then(function (data) {
                var seats = Array.isArray(data.seats) ? data.seats : [];
                var rows = new Map();
                var counts = { SOLD: 0, HOLD: 0, AVAILABLE: 0, MAINTENANCE: 0 };
                var capacity = 0;
                seats.forEach(function (seat) {
                    var rowLabel = String(seat.rowLabel || '?');
                    if (!rows.has(rowLabel)) rows.set(rowLabel, []);
                    rows.get(rowLabel).push(seat);
                    var seatCapacity = String(seat.seatType || '').toUpperCase() === 'COUPLE' ? 2 : 1;
                    capacity += seatCapacity;
                    if (Object.prototype.hasOwnProperty.call(counts, seat.status)) {
                        counts[seat.status] += seatCapacity;
                    }
                });
                seatMap.replaceChildren();
                if (!seats.length) {
                    seatMapStatus.textContent = 'Phòng chiếu chưa có sơ đồ ghế.';
                    return;
                }

                seatMapStatus.textContent = 'Tổng ' + capacity + ' chỗ · '
                    + counts.SOLD + ' đã đặt · ' + counts.HOLD + ' đang giữ · '
                    + counts.AVAILABLE + ' còn trống'
                    + (counts.MAINTENANCE ? ' · ' + counts.MAINTENANCE + ' bảo trì' : '') + '.';
                seatMap.appendChild(hub.el('div', {
                    class: 'ws-showtime-seat-map-screen',
                    'aria-hidden': 'true'
                }, ['MÀN HÌNH']));

                var grid = hub.el('div', { class: 'ws-showtime-seat-map-grid' });
                Array.from(rows.keys()).sort().forEach(function (rowLabel) {
                    var row = hub.el('div', { class: 'ws-showtime-seat-map-row' });
                    row.appendChild(hub.el('span', {
                        class: 'ws-showtime-seat-map-row-label'
                    }, [rowLabel]));
                    var cells = hub.el('div', { class: 'ws-showtime-seat-map-cells' });
                    var occupiedThrough = 0;
                    rows.get(rowLabel).sort(function (a, b) {
                        return Number(a.colNo) - Number(b.colNo);
                    }).forEach(function (seat) {
                        var seatCol = Number(seat.colNo) || 0;
                        if (seatCol <= occupiedThrough) return;
                        var seatStatus = String(seat.status || 'MAINTENANCE').toUpperCase();
                        var className = seatStatus === 'SOLD' ? 'sold'
                            : seatStatus === 'HOLD' ? 'hold'
                            : seatStatus === 'AVAILABLE' ? 'available' : 'maintenance';
                        var seatCode = rowLabel + String(seat.colNo || '');
                        var seatDisplayCode = seat.seatType === 'COUPLE'
                            ? seatCode + '-' + (seatCol + 1) : seatCode;
                        var statusLabel = seatStatus === 'SOLD' ? 'đã đặt'
                            : seatStatus === 'HOLD' ? 'đang được giữ'
                            : seatStatus === 'AVAILABLE' ? 'còn trống' : 'bảo trì';
                        var seatNode = hub.el('span', {
                            class: 'ws-showtime-seat-map-seat ' + className
                                + (seat.seatType === 'COUPLE' ? ' couple' : ''),
                            title: seatDisplayCode + ' · ' + (seat.seatType || 'STANDARD') + ' · ' + statusLabel,
                            'aria-label': 'Ghế ' + seatDisplayCode + ', ' + statusLabel
                        }, [seat.seatType === 'COUPLE'
                            ? String(seat.colNo) + '-' + (seatCol + 1) : String(seat.colNo || '')]);
                        cells.appendChild(seatNode);
                        if (seat.seatType === 'COUPLE') occupiedThrough = seatCol + 1;
                    });
                    row.appendChild(cells);
                    grid.appendChild(row);
                });
                seatMap.appendChild(grid);
                seatMap.appendChild(hub.el('div', { class: 'ws-showtime-seat-map-legend' }, [
                    ['available', 'Còn trống'],
                    ['hold', 'Đang giữ'],
                    ['sold', 'Đã đặt'],
                    ['maintenance', 'Bảo trì']
                ].map(function (item) {
                    return hub.el('span', { class: 'ws-showtime-seat-map-legend-item' }, [
                        hub.el('span', {
                            class: 'ws-showtime-seat-map-dot ' + item[0],
                            'aria-hidden': 'true'
                        }),
                        item[1]
                    ]);
                })));
            }).catch(function (err) {
                seatMapStatus.className = 'ws-showtime-bookings-status error';
                seatMapStatus.textContent = err.message || 'Không tải được sơ đồ ghế phòng chiếu.';
            });
        }

        function loadBookings() {
            status.className = 'ws-showtime-bookings-status';
            status.textContent = 'Đang tải danh sách ghế…';
            tableWrap.replaceChildren();
            previousButton.disabled = true;
            nextButton.disabled = true;
            var query = new URLSearchParams({
                showtimeId: String(showtime.id),
                limit: String(pageSize),
                offset: String(offset)
            });
            hub.api('/booking/list?' + query.toString()).then(function (data) {
                var items = Array.isArray(data.items) ? data.items : [];
                var total = Number(data.total) || 0;
                status.textContent = total
                    ? 'Có ' + total.toLocaleString('vi-VN') + ' vé đang giữ, đã đặt hoặc đã sử dụng.'
                    : 'Chưa có khách đặt ghế cho suất chiếu này.';
                if (items.length) {
                    var table = hub.el('table', { class: 'ws-table' });
                    var headRow = hub.el('tr');
                    ['Mã vé', 'Khách hàng', 'Ghế', 'Trạng thái'].forEach(function (label) {
                        headRow.appendChild(hub.el('th', {}, [label]));
                    });
                    table.appendChild(hub.el('thead', {}, [headRow]));
                    var tbody = hub.el('tbody');
                    items.forEach(function (ticket) {
                        var row = hub.el('tr');
                        row.appendChild(hub.el('td', {}, [ticket.ticketCode || '—']));
                        row.appendChild(hub.el('td', {}, [ticket.customerName || 'Khách lẻ']));
                        var seatsCell = hub.el('td', { class: 'ws-showtime-bookings-seats' });
                        var seats = Array.isArray(ticket.seats) ? ticket.seats : [];
                        if (!seats.length) {
                            seatsCell.textContent = '—';
                        } else {
                            seats.forEach(function (seat) {
                                seatsCell.appendChild(hub.el('span', { class: 'ws-showtime-seat-chip' }, [
                                    seat.seatCode || ((seat.rowLabel || '') + (seat.colNo || ''))
                                ]));
                            });
                        }
                        row.appendChild(seatsCell);
                        row.appendChild(hub.el('td', {}, [statusBadge(ticket.status)]));
                        tbody.appendChild(row);
                    });
                    table.appendChild(tbody);
                    tableWrap.appendChild(table);
                }
                pageLabel.textContent = total
                    ? 'Hiển thị ' + (offset + 1) + '–' + (offset + items.length) + ' / ' + total
                    : '';
                previousButton.disabled = offset === 0;
                nextButton.disabled = offset + items.length >= total;
                pagination.hidden = total <= pageSize;
            }).catch(function (err) {
                status.className = 'ws-showtime-bookings-status error';
                status.textContent = err.message || 'Không tải được danh sách ghế đã đặt.';
                pagination.hidden = true;
                notifyError(err);
            });
        }

        panel.appendChild(heading);
        panel.appendChild(body);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);
        document.addEventListener('keydown', onKeyDown);
        closeButton.focus();
        loadShowtimeSeatMap();
        loadBookings();
    }

    function notifyError(err) {
        hub.notify(err.message || 'Đã xảy ra lỗi.', 'err');
    }

    /**
     * Mở popup chọn BRM + nhân viên cho một chi nhánh.
     * - mode = 'admin' (mặc định): cho phép đổi cả BRM và staff.
     * - mode = 'staff-only': BRM chỉ thêm/xoá staff, không đổi BRM.
     *
     * @param {Object} branch        Branch row từ list (có id, name, staff)
     * @param {Function} onSaved     callback sau khi lưu thành công
     * @param {String} mode          'admin' | 'staff-only'
     */
    /**
     * Mở màn hình phân công nhân sự cho 1 chi nhánh (toàn trang, không modal).
     * Layout 2 cột: search + list bên trái, tóm tắt + actions sticky bottom.
     *
     * @param {Object} branch        Branch row
     * @param {Function} onSaved     callback sau khi lưu thành công
     * @param {String} mode          'admin' (mặc định) | 'staff-only' (BRM)
     */
    function openBranchStaffAssignmentModal(branch, onSaved, mode) {
        var allowManagerEdit = mode !== 'staff-only';
        Promise.all([
            hub.api('/api/users?role=BRANCH_MANAGER&limit=200'),
            hub.api('/api/users?role=BRANCH_STAFF&limit=200'),
            hub.api('/api/staff-assignments/active-map').catch(function () { return {}; }),
            hub.api('/api/staff-assignments?branchId=' + encodeURIComponent(branch.id) + '&all=true')
                .catch(function () { return []; })
        ]).then(function (results) {
            var managers = (Array.isArray(results[0]) ? results[0] : (results[0].items || []))
                .filter(function (u) { return u.status === 'ACTIVE'; });
            var staff = (Array.isArray(results[1]) ? results[1] : (results[1].items || []))
                .filter(function (u) { return u.status === 'ACTIVE'; });
            var activeMap = results[2] || {};
            var currentAssigned = Array.isArray(results[3]) ? results[3] : [];
            currentAssigned = currentAssigned.map(function (row) {
                return {
                    id: row.id,
                    userId: row.userId || row.staffId || row.user_id,
                    branchId: row.branchId,
                    roleCode: row.roleCode || row.role_code
                };
            });
            var currentByUser = {};
            currentAssigned.forEach(function (row) {
                if (row.userId) currentByUser[String(row.userId)] = row;
            });
            var currentManagerId = null;
            currentAssigned.forEach(function (row) {
                if (row.roleCode === 'BRANCH_MANAGER' && !currentManagerId) {
                    currentManagerId = String(row.userId);
                }
            });

            // ---- Form-page wrapper ----
            var breadcrumb = ['Quản lý',
                { label: 'Chi nhánh', href: ctx + '/console?module=branch' },
                { label: branch.name || ('#' + branch.id), href: ctx + '/console?module=branch&action=edit&id=' + branch.id },
                'Phân công nhân sự'];

            var pickerRoot = hub.el('div', { class: 'ws-bsap-root' });

            // === Sidebar trái: search + filter ===
            var sidebar = hub.el('aside', { class: 'ws-bsap-sidebar' });
            var searchBox = hub.el('div', { class: 'ws-bsap-search' });
            var searchIcon = hub.el('span', { class: 'ws-bsap-search-icon' }, ['\uD83D\uDD0D']);
            var searchInput = hub.el('input', {
                type: 'text',
                class: 'ws-bsap-search-input',
                placeholder: 'Tìm theo tên hoặc email...',
                autocomplete: 'off'
            });
            searchBox.appendChild(searchIcon);
            searchBox.appendChild(searchInput);
            sidebar.appendChild(searchBox);

            // Filter chips: Tất cả / Sẵn sàng / Đang ở / Trùng
            var filterRow = hub.el('div', { class: 'ws-bsap-filter-row' });
            var filterState = { tab: 'all', q: '' };
            function makeFilterChip(id, label) {
                var chip = hub.el('button', {
                    type: 'button', class: 'ws-bsap-chip', 'data-tab': id
                }, [label]);
                chip.addEventListener('click', function () {
                    filterState.tab = id;
                    filterRow.querySelectorAll('.ws-bsap-chip').forEach(function (b) {
                        b.classList.toggle('is-active', b.getAttribute('data-tab') === id);
                    });
                    renderLists();
                });
                return chip;
            }
            ['all', 'available', 'in-branch', 'conflict'].forEach(function (id, i) {
                var labels = { all: 'Tất cả', available: 'Sẵn sàng',
                    'in-branch': 'Đang ở', conflict: 'Trùng' };
                var chip = makeFilterChip(id, labels[id]);
                if (i === 0) chip.classList.add('is-active');
                filterRow.appendChild(chip);
            });
            sidebar.appendChild(filterRow);

            // === Panel phải: chứa 2 list (BRM, Staff) ===
            var main = hub.el('div', { class: 'ws-bsap-main' });

            // Thông tin chi nhánh (header card)
            var branchCard = hub.el('div', { class: 'ws-bsap-branch-card' });
            branchCard.appendChild(hub.el('span', { class: 'ws-bsap-branch-icon' }, ['\uD83C\uDFA4']));
            var branchInfo = hub.el('div', { class: 'ws-bsap-branch-info' });
            branchInfo.appendChild(hub.el('div', { class: 'ws-bsap-branch-name' },
                [branch.name || ('Chi nhánh #' + branch.id)]));
            branchInfo.appendChild(hub.el('div', { class: 'ws-bsap-branch-sub' },
                [(branch.address || '') + (branch.phone ? ' · ' + branch.phone : '')]));
            branchCard.appendChild(branchInfo);
            main.appendChild(branchCard);

            // Hàm tạo 1 user row — dùng <div> (không <label>) để tránh bị
            // form CSS ép layout dọc. Click cả row để toggle picker.
            function buildUserRow(user, pickerEl) {
                var userBranches = activeMap[String(user.id)] || activeMap[user.id] || [];
                var currentAssignment = currentByUser[String(user.id)];
                var isInThisBranch = !!currentAssignment;
                var otherBranches = userBranches.filter(function (n) { return n !== (branch.name || ''); });
                var conflict = otherBranches.length > 0 && !isInThisBranch;
                var displayName = user.fullName || user.email || ('User #' + user.id);
                var initials = (displayName || '?')
                    .split(/\s+/).map(function (p) { return p.charAt(0).toUpperCase(); })
                    .slice(0, 2).join('') || '?';
                var roleLabel = user.role === 'BRANCH_MANAGER' ? 'BRM' : 'NV';
                var row = hub.el('div', { class: 'ws-bsap-row' });
                row.setAttribute('data-state', conflict ? 'conflict'
                    : isInThisBranch ? 'in-branch' : 'available');
                row.setAttribute('data-search', (displayName + ' ' + (user.email || '')).toLowerCase());
                if (conflict) {
                    row.classList.add('is-conflict');
                    pickerEl.disabled = true;
                }
                // Click anywhere trên row sẽ toggle picker (trừ nút "Bỏ gán")
                row.appendChild(pickerEl);
                var avatar = hub.el('div', { class: 'ws-bsap-avatar' }, [initials]);
                avatar.setAttribute('data-role', roleLabel);
                row.appendChild(avatar);
                var info = hub.el('div', { class: 'ws-bsap-info' });
                info.appendChild(hub.el('div', { class: 'ws-bsap-name' }, [displayName]));
                info.appendChild(hub.el('div', { class: 'ws-bsap-email' }, [user.email || '']));
                row.appendChild(info);
                var meta = hub.el('div', { class: 'ws-bsap-meta' });
                if (isInThisBranch) {
                    meta.appendChild(hub.el('span', { class: 'ws-bsap-badge ok' }, ['Đang ở đây']));
                } else if (conflict) {
                    meta.appendChild(hub.el('span', { class: 'ws-bsap-badge err' },
                        ['Đang ở ' + otherBranches.join(', ')]));
                } else if (userBranches.length) {
                    meta.appendChild(hub.el('span', { class: 'ws-bsap-badge warn' },
                        ['Vừa rời ' + userBranches.join(', ')]));
                }
                // Nút "Bỏ gán" — chỉ hiện khi user đang ở chi nhánh này.
                // BRM thấy nút cho Staff; Admin thấy nút cho cả BRM và Staff.
                if (isInThisBranch && currentAssignment
                        && (allowManagerEdit || user.role === 'BRANCH_STAFF')) {
                    var removeBtn = hub.el('button', {
                        type: 'button',
                        class: 'ws-bsap-remove',
                        title: 'Bỏ gán khỏi chi nhánh',
                        'aria-label': 'Bỏ gán ' + displayName
                    }, ['\u2716']); // ✖
                    removeBtn.addEventListener('click', function (event) {
                        event.stopPropagation();
                        removeUserFromBranch(user, currentAssignment, removeBtn);
                    });
                    meta.appendChild(removeBtn);
                }
                row.appendChild(meta);
                // Click row → toggle picker (chỉ khi picker có thể chọn)
                row.addEventListener('click', function (event) {
                    if (event.target.closest('.ws-bsap-remove')) return;
                    if (pickerEl.disabled) return;
                    if (pickerEl.type === 'checkbox') {
                        pickerEl.checked = !pickerEl.checked;
                        pickerEl.dispatchEvent(new Event('change', { bubbles: true }));
                    } else if (pickerEl.type === 'radio') {
                        if (!pickerEl.checked) {
                            pickerEl.checked = true;
                            pickerEl.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    }
                });
                return row;
            }

            // Bỏ gán user khỏi chi nhánh (gọi API DELETE).
            function removeUserFromBranch(user, currentAssignment, btn) {
                var label = user.fullName || user.email || ('User #' + user.id);
                if (!confirm('Bỏ gán "' + label + '" khỏi chi nhánh này?')) return;
                btn.disabled = true;
                hub.api('/api/staff-assignments/' + currentAssignment.id, { method: 'DELETE' })
                    .then(function () {
                        hub.notify('Đã bỏ gán ' + label + '.', 'ok');
                        // Cập nhật local state
                        delete currentByUser[String(user.id)];
                        if (String(user.id) === String(currentManagerId)) currentManagerId = null;
                        currentAssigned = currentAssigned.filter(function (r) {
                            return r.id !== currentAssignment.id;
                        });
                        // Tìm row và render lại
                        var rowEl = btn.closest('.ws-bsap-row');
                        if (rowEl) {
                            // Bỏ class "in-branch" → chuyển sang "available"
                            rowEl.setAttribute('data-state', 'available');
                            // Xóa nút bỏ gán + badge "Đang ở đây"
                            var meta = rowEl.querySelector('.ws-bsap-meta');
                            if (meta) meta.innerHTML = '';
                            if (userBranches) {
                                // No-op guard for unused local
                            }
                            // Nếu là BRM đang được chọn radio, bỏ check
                            var picker = rowEl.querySelector('input[type="radio"], input[type="checkbox"]');
                            if (picker) {
                                picker.checked = false;
                                picker.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        }
                        if (onSaved) onSaved();
                        updateSummary();
                    })
                    .catch(function (err) {
                        btn.disabled = false;
                        notifyError(err);
                    });
            }

            // Section BRM
            var managerBlock = hub.el('section', { class: 'ws-bsap-block' });
            var managerHead = hub.el('div', { class: 'ws-bsap-block-head' });
            managerHead.appendChild(hub.el('h3', {}, ['Branch Manager']));
            var managerDesc = hub.el('p', { class: 'ws-bsap-block-desc' }, [
                allowManagerEdit
                    ? 'Chọn 1 tài khoản BRM active để quản lý chi nhánh.'
                    : 'Branch Manager hiện tại của chi nhánh (chỉ Admin mới có quyền thay đổi).'
            ]);
            managerHead.appendChild(managerDesc);
            managerBlock.appendChild(managerHead);
            var managerList = hub.el('div', { class: 'ws-bsap-list' });
            var managerRadios = [];
            managers.forEach(function (user) {
                var radio = hub.el('input', { type: 'radio', name: 'bsa-manager', value: String(user.id) });
                if (String(user.id) === String(currentManagerId)) radio.checked = true;
                managerRadios.push(radio);
                managerList.appendChild(buildUserRow(user, radio));
            });
            if (!managers.length) {
                managerList.appendChild(hub.el('div', { class: 'ws-bsap-empty' },
                    ['Chưa có tài khoản BRM active nào trong hệ thống.']));
            }
            managerBlock.appendChild(managerList);
            if (!allowManagerEdit) {
                // BRM không được sửa — khóa tất cả radio (read-only hiển thị BRM hiện tại)
                managerRadios.forEach(function (r) { r.disabled = true; });
                managerList.classList.add('is-readonly');
            }
            main.appendChild(managerBlock);

            // Section Staff
            var staffBlock = hub.el('section', { class: 'ws-bsap-block' });
            var staffHead = hub.el('div', { class: 'ws-bsap-block-head' });
            staffHead.appendChild(hub.el('h3', {}, ['Nhân viên']));
            staffHead.appendChild(hub.el('p', { class: 'ws-bsap-block-desc' }, [
                allowManagerEdit
                    ? 'Chọn các nhân viên được phân công cho chi nhánh (có thể chọn nhiều).'
                    : 'Thêm nhân viên vào chi nhánh của bạn.'
            ]));
            staffBlock.appendChild(staffHead);
            var staffList = hub.el('div', { class: 'ws-bsap-list' });
            var staffCheckboxes = [];
            staff.forEach(function (user) {
                var checkbox = hub.el('input', { type: 'checkbox', name: 'bsa-staff', value: String(user.id) });
                if (currentByUser[String(user.id)]) checkbox.checked = true;
                staffCheckboxes.push(checkbox);
                staffList.appendChild(buildUserRow(user, checkbox));
            });
            if (!staff.length) {
                staffList.appendChild(hub.el('div', { class: 'ws-bsap-empty' },
                    ['Chưa có tài khoản STAFF active nào trong hệ thống.']));
            }
            staffBlock.appendChild(staffList);
            main.appendChild(staffBlock);

            pickerRoot.appendChild(sidebar);
            pickerRoot.appendChild(main);

            // ---- Sticky bottom (summary + actions) ----
            var summary = hub.el('div', { class: 'ws-bsap-summary' });
            var summaryText = hub.el('span', { class: 'ws-bsap-summary-text' });
            summary.appendChild(summaryText);
            var actions = hub.el('div', { class: 'ws-bsap-actions' });
            var cancelBtn = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Huỷ']);
            var saveBtn = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['Lưu phân công']);
            actions.appendChild(cancelBtn);
            actions.appendChild(saveBtn);
            summary.appendChild(actions);
            var errorBox = hub.el('div', { class: 'ws-template-error', role: 'alert', hidden: true });
            main.appendChild(errorBox);

            function showError(message) {
                errorBox.textContent = message;
                errorBox.hidden = !message;
            }
            function updateSummary() {
                var selectedBrm = allowManagerEdit
                    ? managerList.querySelector('input[type="radio"]:checked') : null;
                var selectedStaff = staffCheckboxes
                    .filter(function (cb) { return cb.checked && !cb.disabled; });
                // Update row.is-selected (tương thích trình duyệt cũ thay vì :has())
                managerList.querySelectorAll('.ws-bsap-row').forEach(function (row) {
                    var r = row.querySelector('input[type="radio"]');
                    row.classList.toggle('is-selected', !!(r && r.checked));
                });
                staffList.querySelectorAll('.ws-bsap-row').forEach(function (row) {
                    var c = row.querySelector('input[type="checkbox"]');
                    row.classList.toggle('is-selected', !!(c && c.checked));
                });
                var parts = [];
                if (allowManagerEdit) {
                    parts.push(selectedBrm
                        ? 'BRM: ' + (managers.find(function (u) {
                            return String(u.id) === selectedBrm.value; }).fullName || '—')
                        : 'Chưa chọn BRM');
                } else {
                    var brm = managers.find(function (u) {
                        return String(u.id) === String(currentManagerId); });
                    parts.push('BRM: ' + (brm ? (brm.fullName || brm.email) : 'Chưa có'));
                }
                parts.push('Nhân viên: ' + selectedStaff.length);
                summaryText.textContent = parts.join('  ·  ');
            }

            function matchesFilter(row) {
                var state = row.getAttribute('data-state');
                if (filterState.tab !== 'all' && state !== filterState.tab) return false;
                if (filterState.q) {
                    var haystack = row.getAttribute('data-search') || '';
                    if (haystack.indexOf(filterState.q) === -1) return false;
                }
                return true;
            }
            function renderLists() {
                [managerList, staffList].forEach(function (list) {
                    var visible = 0;
                    list.querySelectorAll('.ws-bsap-row').forEach(function (row) {
                        var match = matchesFilter(row);
                        row.classList.toggle('is-hidden', !match);
                        if (match) visible++;
                    });
                    // empty state for filter
                    var empty = list.querySelector('.ws-bsap-empty-filter');
                    if (visible === 0 && filterState.q) {
                        if (!empty) {
                            empty = hub.el('div', { class: 'ws-bsap-empty ws-bsap-empty-filter' },
                                ['Không có kết quả phù hợp.']);
                            list.appendChild(empty);
                        }
                    } else if (empty) {
                        empty.remove();
                    }
                });
                updateSummary();
            }
            // listen picker change
            [].concat(managerRadios, staffCheckboxes).forEach(function (el) {
                el.addEventListener('change', function () {
                    updateSummary();
                });
            });
            // Khởi tạo class is-selected cho các row đã checked sẵn
            managerRadios.concat(staffCheckboxes).forEach(function (el) {
                if (el.checked) {
                    var row = el.closest('.ws-bsap-row');
                    if (row) row.classList.add('is-selected');
                }
            });
            searchInput.addEventListener('input', function () {
                filterState.q = searchInput.value.trim().toLowerCase();
                renderLists();
            });

            // ---- Save / Cancel handlers ----
            cancelBtn.addEventListener('click', function () { router.go('branch'); });
            saveBtn.addEventListener('click', function () {
                showError('');
                var selectedManager = allowManagerEdit
                    ? managerList.querySelector('input[type="radio"]:checked') : null;
                if (allowManagerEdit && !selectedManager) {
                    showError('Vui lòng chọn 1 Branch Manager.');
                    return;
                }
                var selectedStaff = staffCheckboxes
                    .filter(function (cb) { return cb.checked && !cb.disabled; })
                    .map(function (cb) { return cb.value; });
                if (allowManagerEdit && !selectedStaff.length) {
                    showError('Vui lòng chọn ít nhất 1 nhân viên.');
                    return;
                }
                saveBtn.disabled = true;
                var wanted = new Set(selectedStaff.map(String));
                var currentStaffIds = new Set(
                    currentAssigned
                        .filter(function (r) { return r.roleCode === 'BRANCH_STAFF'; })
                        .map(function (r) { return String(r.userId); })
                );
                var currentManager = currentManagerId;
                var newManagerId = selectedManager ? selectedManager.value : currentManager;
                var toAssign = [];
                if (allowManagerEdit
                        && (!currentManager || String(currentManager) !== newManagerId)) {
                    toAssign.push(newManagerId);
                }
                wanted.forEach(function (uid) {
                    if (!currentStaffIds.has(uid)) toAssign.push(uid);
                });
                var toEnd = [];
                if (allowManagerEdit && currentManager
                        && String(currentManager) !== newManagerId) {
                    toEnd.push(currentManager);
                }
                if (allowManagerEdit) {
                    currentStaffIds.forEach(function (uid) {
                        if (!wanted.has(uid)) toEnd.push(uid);
                    });
                }
                function doAssign(userId) {
                    return hub.api('/api/staff-assignments', {
                        method: 'POST',
                        body: { userId: Number(userId), branchId: Number(branch.id) }
                    });
                }
                function doEnd(userId) {
                    var row = currentByUser[String(userId)];
                    if (!row) return Promise.resolve();
                    return hub.api('/api/staff-assignments/' + row.id, { method: 'DELETE' });
                }
                var ops = [];
                toAssign.forEach(function (uid) { ops.push(doAssign(uid)); });
                toEnd.forEach(function (uid) { ops.push(doEnd(uid)); });
                if (!ops.length) {
                    hub.notify('Không có thay đổi để lưu.', 'ok');
                    if (onSaved) onSaved();
                    router.go('branch');
                    return;
                }
                Promise.all(ops).then(function () {
                    hub.notify('Đã lưu phân công nhân sự.', 'ok');
                    if (onSaved) onSaved();
                    router.go('branch');
                }).catch(function (err) {
                    saveBtn.disabled = false;
                    showError(err.message || 'Không lưu được phân công.');
                });
            });

            // Render qua form-page (full page, không modal)
            form.renderFormPage({
                breadcrumb: breadcrumb,
                title: 'Phân công nhân sự',
                subtitle: branch.name || ('Chi nhánh #' + branch.id),
                hideActions: true,
                sections: [],
                renderFooter: function (formEl) {
                    // Thêm class fallback cho browser không hỗ trợ :has()
                    var formHost = formEl.closest('form.ws-form');
                    if (formHost) formHost.classList.add('ws-bsap-form-host');
                    formEl.appendChild(pickerRoot);
                    formEl.appendChild(summary);
                    renderLists();
                }
            });
        }).catch(notifyError);
    }

    /** Render 1-5 sao bằng Unicode glyph (trùng logic modules-rsc.js). */
    function starString(rating) {
        var full = Math.floor(rating);
        var half = (rating - full) >= 0.5 ? 1 : 0;
        var empty = 5 - full - half;
        return '★'.repeat(full) + (half ? '½' : '') + '☆'.repeat(empty);
    }

    function dateValue(value) {
        if (!value) return '';
        var text = String(value);
        return text.length >= 10 ? text.slice(0, 10) : text;
    }

    function openBranchAssignmentModal(user) {
        var overlay = hub.el('div', { class: 'ws-modal-overlay' });
        var backdrop = hub.el('div', { class: 'ws-modal-backdrop' });
        var panel = hub.el('div', { class: 'ws-modal-panel' });
        var head = hub.el('div', { class: 'ws-modal-head' });
        head.appendChild(hub.el('h2', {}, ['Gán chi nhánh']));
        var close = hub.el('button', { class: 'ws-modal-x', type: 'button' }, ['×']);
        head.appendChild(close);
        var body = hub.el('div', { class: 'ws-modal-body' });
        body.appendChild(hub.el('p', { class: 'ws-muted' }, [
            (user.fullName || user.email || ('User #' + user.id)) + ' — ' +
            (user.role === 'BRANCH_MANAGER' ? 'Quản lý chi nhánh' : 'Nhân viên chi nhánh')
        ]));
        var select = hub.el('select', { class: 'ws-input' });
        select.appendChild(hub.el('option', { value: '' }, ['Chọn chi nhánh']));
        var assigned = hub.el('div', { class: 'ws-assignment-list' });
        var status = hub.el('p', { class: 'ws-field-hint' });
        var assignBtn = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['Gán']);
        var footer = hub.el('div', { class: 'ws-form-actions' });
        footer.appendChild(assignBtn);
        body.appendChild(hub.el('label', {}, ['Chi nhánh mới']));
        body.appendChild(select);
        body.appendChild(assigned);
        body.appendChild(status);
        body.appendChild(footer);
        panel.appendChild(head);
        panel.appendChild(body);
        overlay.appendChild(backdrop);
        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function closeModal() { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); }
        close.onclick = closeModal;
        backdrop.onclick = closeModal;

        function renderAssignments(rows) {
            assigned.replaceChildren();
            if (!rows.length) {
                assigned.appendChild(hub.el('p', { class: 'ws-muted' }, ['Chưa được gán chi nhánh nào.']));
                return;
            }
            rows.forEach(function (row) {
                var line = hub.el('div', { class: 'ws-assignment-row' });
                line.appendChild(hub.el('span', {}, [row.branchName || ('Chi nhánh #' + row.branchId)]));
                var remove = hub.el('button', { class: 'ws-btn danger', type: 'button' }, ['Gỡ']);
                remove.onclick = function () {
                    hub.api('/api/staff-assignments/' + row.id, { method: 'DELETE' })
                        .then(function () { return hub.api('/api/staff-assignments?userId=' + user.id); })
                        .then(renderAssignments)
                        .catch(notifyError);
                };
                line.appendChild(remove);
                assigned.appendChild(line);
            });
        }

        Promise.all([
            hub.api('/branch'),
            hub.api('/api/staff-assignments?userId=' + encodeURIComponent(user.id))
        ]).then(function (result) {
            var branches = Array.isArray(result[0]) ? result[0] : (result[0].items || []);
            branches.forEach(function (branch) {
                select.appendChild(hub.el('option', { value: branch.id }, [branch.name]));
            });
            renderAssignments(Array.isArray(result[1]) ? result[1] : []);
        }).catch(function (err) {
            status.textContent = err.message || 'Không tải được dữ liệu chi nhánh.';
            status.className = 'ws-field-hint error';
        });

        assignBtn.onclick = function () {
            if (!select.value) {
                status.textContent = 'Vui lòng chọn chi nhánh.';
                return;
            }
            assignBtn.disabled = true;
            hub.api('/api/staff-assignments', {
                method: 'POST',
                body: { userId: user.id, branchId: select.value }
            }).then(function () {
                status.textContent = 'Đã gán chi nhánh.';
                status.className = 'ws-field-hint success';
                select.value = '';
                return hub.api('/api/staff-assignments?userId=' + encodeURIComponent(user.id));
            }).then(renderAssignments).catch(notifyError)
                .then(function () { assignBtn.disabled = false; });
        };
    }

    function renderUserBranchAssignmentPage(id) {
        var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
        var viewRoot = document.getElementById('viewRoot');
        if (roleMeta !== 'ADMIN') {
            viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                hub.el('h3', {}, ['Không có quyền phân công']),
                hub.el('p', {}, ['Chỉ Admin mới có thể thay đổi chi nhánh làm việc.'])
            ]));
            return;
        }

        var root = hub.el('div', { class: 'ws-user-branch-page' });
        var breadcrumb = hub.el('nav', { class: 'ws-breadcrumb', 'aria-label': 'Breadcrumb' });
        breadcrumb.appendChild(hub.el('a', {
            class: 'ws-breadcrumb-link',
            href: ctx + '/console?module=users'
        }, ['Người dùng']));
        breadcrumb.appendChild(hub.el('span', {
            class: 'ws-breadcrumb-sep',
            'aria-hidden': 'true'
        }, ['/']));
        var breadcrumbCurrent = hub.el('span', {
            class: 'ws-breadcrumb-current',
            'aria-current': 'page'
        }, ['Phân công chi nhánh']);
        breadcrumb.appendChild(breadcrumbCurrent);
        root.appendChild(breadcrumb);

        var header = hub.el('header', { class: 'ws-page-header' });
        var titleEl = hub.el('h1', { class: 'ws-page-title' }, ['Phân công chi nhánh']);
        var subtitleEl = hub.el('p', { class: 'ws-page-subtitle' }, [
            'Chọn chi nhánh làm việc cho Branch Manager hoặc nhân viên.'
        ]);
        header.appendChild(titleEl);
        header.appendChild(subtitleEl);
        root.appendChild(header);

        var personCard = hub.el('section', { class: 'ws-user-branch-person' });
        personCard.appendChild(hub.el('span', { class: 'ws-user-branch-avatar' }, ['…']));
        var personInfo = hub.el('div', { class: 'ws-user-branch-person-info' });
        personInfo.appendChild(hub.el('strong', {}, ['Đang tải người dùng…']));
        personCard.appendChild(personInfo);
        root.appendChild(personCard);

        var panel = hub.el('section', { class: 'ws-user-branch-panel' });
        var panelHead = hub.el('div', { class: 'ws-user-branch-panel-head' });
        var panelTitle = hub.el('div');
        panelTitle.appendChild(hub.el('h2', {}, ['Chi nhánh làm việc']));
        panelTitle.appendChild(hub.el('p', {}, [
            'Tìm kiếm theo tên chi nhánh rồi chọn một hoặc nhiều chi nhánh.'
        ]));
        panelHead.appendChild(panelTitle);
        var selectedCount = hub.el('span', { class: 'ws-user-branch-count' }, ['0 chi nhánh đã chọn']);
        panelHead.appendChild(selectedCount);
        panel.appendChild(panelHead);

        var searchWrap = hub.el('label', { class: 'ws-user-branch-search' });
        searchWrap.appendChild(hub.el('span', { 'aria-hidden': 'true' }, ['⌕']));
        var searchInput = hub.el('input', {
            type: 'search',
            placeholder: 'Tìm chi nhánh…',
            'aria-label': 'Tìm chi nhánh'
        });
        searchWrap.appendChild(searchInput);
        panel.appendChild(searchWrap);

        var error = hub.el('p', { class: 'ws-user-branch-error', role: 'alert', hidden: true });
        panel.appendChild(error);
        var branchList = hub.el('div', { class: 'ws-user-branch-list', 'aria-live': 'polite' });
        panel.appendChild(branchList);

        var footer = hub.el('div', { class: 'ws-user-branch-actions' });
        var cancel = hub.el('a', {
            class: 'ws-btn secondary',
            href: ctx + '/console?module=users'
        }, ['Hủy']);
        var save = hub.el('button', { class: 'ws-btn primary', type: 'button', disabled: true }, [
            'Lưu phân công'
        ]);
        footer.appendChild(cancel);
        footer.appendChild(save);
        panel.appendChild(footer);
        root.appendChild(panel);
        viewRoot.replaceChildren(root);
        if (typeof hub.setWorkspaceHeader === 'function') {
            hub.setWorkspaceHeader('Phân công chi nhánh');
        }

        var branches = [];
        var assignmentsByBranch = {};
        var selectedByBranch = {};
        var checkboxes = {};
        var user;

        function updateSelectionSummary() {
            var count = Object.keys(selectedByBranch).filter(function (branchId) {
                return selectedByBranch[branchId];
            }).length;
            selectedCount.textContent = count + ' chi nhánh đã chọn';
        }

        function renderBranchList() {
            branchList.replaceChildren();
            checkboxes = {};
            var query = searchInput.value.trim().toLocaleLowerCase();
            var visibleBranches = branches.filter(function (branch) {
                return !query || String(branch.name || '').toLocaleLowerCase().indexOf(query) !== -1
                    || String(branch.address || '').toLocaleLowerCase().indexOf(query) !== -1;
            });
            if (!visibleBranches.length) {
                branchList.appendChild(hub.el('p', { class: 'ws-user-branch-empty' }, [
                    query ? 'Không tìm thấy chi nhánh phù hợp.' : 'Chưa có chi nhánh nào.'
                ]));
                updateSelectionSummary();
                return;
            }
            visibleBranches.forEach(function (branch) {
                var branchId = String(branch.id);
                var assignment = assignmentsByBranch[branchId];
                var row = hub.el('label', { class: 'ws-user-branch-row' });
                var checkbox = hub.el('input', {
                    type: 'checkbox',
                    value: branchId,
                    disabled: branch.status !== 'ACTIVE' && !assignment
                });
                if (!Object.prototype.hasOwnProperty.call(selectedByBranch, branchId)) {
                    selectedByBranch[branchId] = !!assignment;
                }
                checkbox.checked = !!selectedByBranch[branchId];
                checkboxes[branchId] = checkbox;
                checkbox.addEventListener('change', function () {
                    selectedByBranch[branchId] = checkbox.checked;
                    row.classList.toggle('is-selected', checkbox.checked);
                    updateSelectionSummary();
                });
                row.classList.toggle('is-selected', checkbox.checked);
                row.appendChild(checkbox);
                var info = hub.el('span', { class: 'ws-user-branch-info' });
                info.appendChild(hub.el('strong', {}, [branch.name || ('Chi nhánh #' + branch.id)]));
                info.appendChild(hub.el('small', {}, [branch.address || 'Chưa có địa chỉ']));
                row.appendChild(info);
                var status = hub.el('span', {
                    class: 'ws-user-branch-status ' + (branch.status === 'ACTIVE' ? 'is-active' : 'is-inactive')
                }, [branch.status === 'ACTIVE' ? 'Đang hoạt động' : 'Ngưng hoạt động']);
                row.appendChild(status);
                branchList.appendChild(row);
            });
            updateSelectionSummary();
        }

        function showError(message) {
            error.textContent = message;
            error.hidden = false;
        }

        searchInput.addEventListener('input', renderBranchList);
        hub.api('/api/users/' + encodeURIComponent(id))
            .then(function (loadedUser) {
                if (loadedUser.role !== 'BRANCH_MANAGER' && loadedUser.role !== 'BRANCH_STAFF') {
                    throw new Error('Chỉ có thể phân công chi nhánh cho Branch Manager hoặc nhân viên.');
                }
                user = loadedUser;
                var displayName = user.fullName || user.email || ('Người dùng #' + user.id);
                personCard.replaceChildren(
                    hub.el('span', { class: 'ws-user-branch-avatar' }, [
                        displayName.split(/\s+/).map(function (part) {
                            return part.charAt(0).toUpperCase();
                        }).slice(0, 2).join('')
                    ]),
                    hub.el('div', { class: 'ws-user-branch-person-info' }, [
                        hub.el('strong', {}, [displayName]),
                        hub.el('span', {}, [
                            (user.role === 'BRANCH_MANAGER' ? 'Branch Manager' : 'Nhân viên')
                                + (user.email ? ' · ' + user.email : '')
                        ])
                    ])
                );
                return Promise.all([
                    hub.api('/branch'),
                    hub.api('/api/staff-assignments?userId=' + encodeURIComponent(id))
                ]);
            })
            .then(function (result) {
                branches = Array.isArray(result[0]) ? result[0] : (result[0].items || []);
                var rows = Array.isArray(result[1]) ? result[1] : [];
                rows.forEach(function (assignment) {
                    assignmentsByBranch[String(assignment.branchId)] = assignment;
                });
                branches.forEach(function (branch) {
                    selectedByBranch[String(branch.id)] =
                        !!assignmentsByBranch[String(branch.id)];
                });
                // Nếu user đã có ít nhất 1 phân công → đổi ngữ nghĩa sang "Thay đổi chi nhánh"
                // cho rõ ràng, tránh hiểu nhầm là phân công lần đầu.
                var hasExisting = Object.keys(assignmentsByBranch).length > 0;
                if (hasExisting) {
                    breadcrumbCurrent.textContent = 'Thay đổi chi nhánh';
                    titleEl.textContent = 'Thay đổi chi nhánh';
                    var currentNames = branches
                        .filter(function (b) { return assignmentsByBranch[String(b.id)]; })
                        .map(function (b) { return b.name || ('Chi nhánh #' + b.id); });
                    subtitleEl.textContent = 'Đang làm việc tại: ' + currentNames.join(', ')
                        + '. Có thể bỏ gán hoặc chuyển sang chi nhánh khác.';
                    save.textContent = 'Lưu thay đổi';
                    if (typeof hub.setWorkspaceHeader === 'function') {
                        hub.setWorkspaceHeader('Thay đổi chi nhánh');
                    }
                }
                renderBranchList();
                save.disabled = false;
            })
            .catch(function (loadError) {
                showError(loadError.message || 'Không tải được dữ liệu phân công.');
            });

        save.addEventListener('click', function () {
            if (!user) return;
            var toRemove = Object.keys(assignmentsByBranch).filter(function (branchId) {
                return !selectedByBranch[branchId];
            }).map(function (branchId) { return assignmentsByBranch[branchId]; });
            var toAdd = Object.keys(selectedByBranch).filter(function (branchId) {
                return selectedByBranch[branchId];
            }).filter(function (branchId) {
                return !assignmentsByBranch[branchId];
            });
            var operations = toRemove.map(function (assignment) {
                return hub.api('/api/staff-assignments/' + assignment.id, { method: 'DELETE' });
            }).concat(toAdd.map(function (branchId) {
                return hub.api('/api/staff-assignments', {
                    method: 'POST',
                    body: { userId: user.id, branchId: Number(branchId) }
                });
            }));
            if (!operations.length) {
                hub.notify('Không có thay đổi phân công.', 'info');
                return;
            }
            error.hidden = true;
            save.disabled = true;
            save.textContent = 'Đang lưu…';
            Promise.allSettled(operations)
                .then(function (results) {
                    var failedOperation = results.find(function (result) {
                        return result.status === 'rejected';
                    });
                    if (failedOperation) {
                        return hub.api('/api/staff-assignments?userId=' + encodeURIComponent(user.id))
                            .then(function (assignments) {
                                assignmentsByBranch = {};
                                var persistedAssignments = Array.isArray(assignments)
                                    ? assignments : (assignments.items || []);
                                persistedAssignments.forEach(function (assignment) {
                                    assignmentsByBranch[String(assignment.branchId)] = assignment;
                                });
                                selectedByBranch = {};
                                branches.forEach(function (branch) {
                                    selectedByBranch[String(branch.id)] =
                                        !!assignmentsByBranch[String(branch.id)];
                                });
                                renderBranchList();
                                save.disabled = false;
                                save.textContent = 'Lưu phân công';
                                var reason = failedOperation.reason
                                    && failedOperation.reason.message
                                    ? ' ' + failedOperation.reason.message : '';
                                showError(
                                    'Một số thay đổi không được lưu. Danh sách đã được đồng bộ với dữ liệu hiện tại.'
                                    + reason);
                            });
                    }
                    hub.notify('Đã lưu phân công chi nhánh.', 'ok');
                    router.go('users');
                })
                .catch(function (saveError) {
                    showError(
                        'Không thể xác nhận trạng thái phân công sau khi lưu. Tải lại trang trước khi thử lại. '
                        + (saveError.message || ''));
                    save.disabled = true;
                    save.textContent = 'Không thể đồng bộ';
                });
        });
    }

    // ================================================================
    //  USERS  (Admin only)
    // ================================================================
    router.register('users', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta !== 'ADMIN' && roleMeta !== 'BRANCH_MANAGER') {
                var viewRoot = document.getElementById('viewRoot');
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không có quyền truy cập']),
                    hub.el('p', {}, ['Chỉ Admin hoặc Manager mới được xem người dùng.'])
                ]));
                return;
            }
            list.render({
                title: 'Người dùng',
                subtitle: 'Quản lý tài khoản nhân viên và khách hàng CinemaHub.',
                addUrl: ctx + '/console?module=users&action=create',
                addLabel: 'Tạo người dùng',
                searchPlaceholder: 'Tìm theo họ tên, email hoặc số điện thoại…',
                pageSize: 20,
                filters: [{
                    name: 'role', label: 'Vai trò',
                    options: [
                        { value: 'ADMIN', label: 'Admin' },
                        { value: 'BRANCH_MANAGER', label: 'Quản lý chi nhánh' },
                        { value: 'BRANCH_STAFF', label: 'Nhân viên chi nhánh' },
                        { value: 'CUSTOMER', label: 'Khách hàng' }
                    ]
                }],
                fetcher: function (qs) {
                    return hub.api('/api/users?' + qs.toString()).then(function (payload) {
                        var items = Array.isArray(payload) ? payload : (payload.items || []);
                        // Lấy active-map {userId: [branchName,...]} để hiển thị cột "Chi nhánh".
                        return hub.api('/api/staff-assignments/active-map').then(function (activeMap) {
                            items.forEach(function (u) {
                                u._branches = (activeMap && (activeMap[u.id] || activeMap[String(u.id)])) || [];
                            });
                            if (Array.isArray(payload)) return items;
                            payload.items = items;
                            return payload;
                        });
                    });
                },
                columns: [
                    { label: 'Email', key: 'email' },
                    { label: 'Số điện thoại', key: 'phone' },
                    { label: 'Họ tên', key: 'fullName' },
                    { label: 'Vai trò', render: function (r) {
                        var map = {
                            'ADMIN': 'Admin',
                            'BRANCH_MANAGER': 'Quản lý chi nhánh',
                            'BRANCH_STAFF': 'Nhân viên',
                            'CUSTOMER': 'Khách hàng'
                        };
                        var span = document.createElement('span');
                        span.textContent = map[r.role] || r.role || '—';
                        return span;
                    }},
                    { label: 'Chi nhánh', render: function (r) {
                        // Chỉ BRM/STAFF mới có thể thuộc chi nhánh.
                        if (r.role !== 'BRANCH_MANAGER' && r.role !== 'BRANCH_STAFF') {
                            return document.createTextNode('—');
                        }
                        var branches = Array.isArray(r._branches) ? r._branches : [];
                        if (!branches.length) {
                            var warn = document.createElement('span');
                            warn.className = 'ws-bsap-badge warn';
                            warn.textContent = 'Chưa gán';
                            return warn;
                        }
                        var wrap = document.createElement('div');
                        branches.forEach(function (name, i) {
                            if (i) wrap.appendChild(document.createTextNode(', '));
                            var badge = document.createElement('span');
                            badge.className = 'ws-bsap-badge ok';
                            badge.style.marginRight = '4px';
                            badge.textContent = name;
                            wrap.appendChild(badge);
                        });
                        return wrap;
                    }},
                    { label: 'Trạng thái', render: function (r) { return statusBadge(r.status); } },
                    { label: 'Ngày tạo', render: function (r) { return r.createdAt ? hub.fmtDateTime(r.createdAt) : '—'; } }
                ],
                actions: function (row) {
                    var arr = [
                        { label: 'Sửa', class: 'secondary', href: ctx + '/console?module=users&action=edit&id=' + row.id }
                    ];
                    if (roleMeta === 'ADMIN'
                            && (row.role === 'BRANCH_MANAGER' || row.role === 'BRANCH_STAFF')) {
                        // Nhân viên đã thuộc ít nhất 1 chi nhánh → dùng nút "Thay đổi chi nhánh"
                        // (màu warning/vàng) thay cho "Phân công" để tránh hiểu nhầm.
                        // Nút "Phân công" (màu primary) chỉ dành cho user chưa được gán.
                        var hasBranch = Array.isArray(row._branches) && row._branches.length > 0;
                        arr.unshift({
                            label: hasBranch ? 'Thay đổi chi nhánh' : 'Phân công',
                            class: hasBranch ? 'warning' : 'primary',
                            href: ctx + '/console?module=users&action=assign&id=' + row.id
                        });
                    }
                    if (row.status === 'ACTIVE') {
                        arr.push({ label: 'Khóa', class: 'danger', onClick: function () {
                            confirmAction('Khóa tài khoản ' + row.email + '?', function () {
                                hub.api('/api/users/' + row.id + '/status', { method: 'POST', body: { status: 'LOCKED' } })
                                    .then(function () {
                                        hub.notify('Đã khóa tài khoản.', 'ok');
                                        router.go('users');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else {
                        arr.push({ label: 'Mở khóa', class: 'secondary', onClick: function () {
                            confirmAction('Mở khóa tài khoản ' + row.email + '?', function () {
                                hub.api('/api/users/' + row.id + '/status', { method: 'POST', body: { status: 'ACTIVE' } })
                                    .then(function () {
                                        hub.notify('Đã mở khóa.', 'ok');
                                        router.go('users');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        assign: function (id) {
            renderUserBranchAssignmentPage(id);
        },
        create: function () {
            form.renderFormPage({
                breadcrumb: ['Quản lý', { label: 'Người dùng', href: ctx + '/console?module=users' }, 'Tạo mới'],
                title: 'Tạo người dùng mới',
                subtitle: 'Tạo tài khoản nhân viên hoặc khách hàng cho hệ thống CinemaHub.',
                submitLabel: 'Tạo người dùng',
                sections: [{
                    title: 'Thông tin cơ bản',
                    description: 'Các trường có dấu * là bắt buộc. Hệ thống sẽ tự sinh mật khẩu ngẫu nhiên và gửi thông tin đăng nhập qua email cho user.',
                    fields: [
                        { name: 'fullName', label: 'Họ và tên', required: true, placeholder: 'Nguyễn Văn A' },
                        { name: 'email', label: 'Email', type: 'email', required: true, placeholder: 'user@example.com', autocomplete: 'email' },
                        { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, pattern: '0[0-9]{9}', patternMessage: 'Số điện thoại phải bắt đầu bằng 0 và có 10 chữ số', placeholder: '0912345678', autocomplete: 'tel' },
                        { name: 'role', label: 'Vai trò', type: 'select', required: true, options: [
                            { value: 'CUSTOMER', label: 'Khách hàng' },
                            { value: 'BRANCH_STAFF', label: 'Nhân viên chi nhánh' },
                            { value: 'BRANCH_MANAGER', label: 'Quản lý chi nhánh' },
                            { value: 'ADMIN', label: 'Admin' }
                        ]}
                    ]
                }],
                onSubmit: function (data) {
                    hub.api('/api/users', { method: 'POST', body: data })
                        .then(function (resp) {
                            if (resp && resp.emailSent) {
                                hub.notify('Đã tạo user và gửi email chứa mật khẩu tạm thời.', 'ok');
                            } else if (resp && resp.generatedPassword) {
                                hub.notify('Đã tạo user. Email gửi thất bại — mật khẩu tạm: ' + resp.generatedPassword, 'warn');
                            } else {
                                hub.notify('Đã tạo user.', 'ok');
                            }
                            router.go('users');
                        })
                        .catch(notifyError);
                },
                onCancel: function () { router.go('users'); }
            });
        },
        edit: function (id) {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            hub.api('/api/users/' + id)
                .then(function (user) {
                    // BRM không được edit user ngoài branch — server trả 403, fallback thông báo.
                    if (roleMeta === 'BRANCH_MANAGER' && user.role === 'ADMIN') {
                        hub.notify('Bạn không có quyền xem tài khoản Admin.', 'warn');
                        router.go('branch-staff');
                        return;
                    }
                    var canManageStatus = roleMeta === 'BRANCH_MANAGER'
                        && user.role === 'BRANCH_STAFF';
                    form.renderFormPage({
                        breadcrumb: roleMeta === 'BRANCH_MANAGER'
                            ? ['Quản lý chi nhánh',
                                { label: 'Nhân viên', href: ctx + '/console?module=branch-staff' },
                                'Chi tiết']
                            : ['Quản lý', { label: 'Người dùng', href: ctx + '/console?module=users' }, 'Chỉnh sửa'],
                        title: roleMeta === 'BRANCH_MANAGER' ? 'Chi tiết nhân viên' : 'Chỉnh sửa người dùng',
                        subtitle: user.fullName || user.email || '',
                        submitLabel: roleMeta === 'ADMIN' ? 'Lưu thay đổi'
                            : (canManageStatus ? 'Lưu trạng thái' : 'Quay lại'),
                        sections: [{
                            title: 'Thông tin cơ bản',
                            fields: [
                                { name: 'fullName', label: 'Họ và tên', required: true, value: user.fullName, disabled: roleMeta !== 'ADMIN' },
                                { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, value: user.phone, pattern: '0[0-9]{9}', disabled: roleMeta !== 'ADMIN' },
                                { name: 'status', label: 'Trạng thái', type: 'select', required: true, value: user.status, options: [
                                    { value: 'ACTIVE', label: 'Hoạt động' },
                                    { value: 'LOCKED', label: 'Bị khóa' },
                                    { value: 'INACTIVE', label: 'Ngưng' }
                                ], disabled: roleMeta !== 'ADMIN' && !canManageStatus}
                            ]
                        }],
                        renderFooter: function (formEl) {
                            if (roleMeta !== 'ADMIN') {
                                ['fullName', 'phone'].forEach(function (name) {
                                    var input = formEl.querySelector('[name="' + name + '"]');
                                    if (input) input.disabled = true;
                                });
                                if (!canManageStatus) {
                                    var statusInput = formEl.querySelector('[name="status"]');
                                    if (statusInput) statusInput.disabled = true;
                                }
                            }
                        },
                        onSubmit: function (data) {
                            if (roleMeta !== 'ADMIN') {
                                if (canManageStatus) {
                                    hub.api('/api/users/' + id + '/status', {
                                        method: 'POST',
                                        body: { status: data.status }
                                    }).then(function () {
                                        hub.notify('Đã cập nhật trạng thái tài khoản.', 'ok');
                                        router.go('branch-staff');
                                    }).catch(notifyError);
                                    return;
                                }
                                router.go(roleMeta === 'BRANCH_MANAGER' ? 'branch-staff' : 'users');
                                return;
                            }
                            hub.api('/api/users/' + id, { method: 'PUT', body: data })
                                .then(function () { router.go('users'); })
                                .catch(notifyError);
                        },
                        onCancel: function () {
                            router.go(roleMeta === 'BRANCH_MANAGER' ? 'branch-staff' : 'users');
                        }
                    });
                })
                .catch(function (err) {
                    // BRM không có quyền xem user ngoài branch — fallback về danh sách.
                    if (err && (err.status === 403 || err.status === 404)) {
                        var roleMeta2 = (document.querySelector('meta[name="user-role"]') || {}).content;
                        if (roleMeta2 === 'BRANCH_MANAGER') {
                            hub.notify('Bạn không có quyền xem tài khoản này.', 'warn');
                            router.go('branch-staff');
                            return;
                        }
                    }
                    notifyError(err);
                });
        }
    });

    // ================================================================
    //  BRANCH (Admin only)
    // ================================================================
    function branchListApiPath(roleMeta) {
        return roleMeta === 'BRANCH_MANAGER' ? '/branch/my-location' : '/branch';
    }

    router.register('branch', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            // BRM không quản lý danh sách chi nhánh — tự redirect sang
            // trang "Nhân viên chi nhánh" để thấy nhân sự thuộc branch mình.
            if (roleMeta === 'BRANCH_MANAGER') {
                window.location.replace(ctx + '/console?module=branch-staff');
                return;
            }
            list.render({
                title: 'Chi nhánh',
                subtitle: 'Quản lý thông tin chi nhánh. Phân công nhân viên được thực hiện ở màn Người dùng.',
                addUrl: roleMeta === 'ADMIN' ? ctx + '/console?module=branch&action=create' : null,
                addLabel: roleMeta === 'ADMIN' ? 'Tạo chi nhánh' : null,
                searchPlaceholder: 'Tìm theo tên, địa chỉ, SĐT…',
                pageSize: 20,
                filters: [{
                    name: 'status', label: 'Trạng thái',
                    options: [
                        { value: 'ACTIVE', label: 'Hoạt động' },
                        { value: 'INACTIVE', label: 'Ngưng hoạt động' }
                    ]
                }],
                sortable: true,
                sortOptions: [
                    { value: 'name', label: 'Tên A→Z' },
                    { value: 'name:desc', label: 'Tên Z→A' },
                    { value: 'createdAt', label: 'Mới nhất' }
                ],
                fetcher: function (qs) {
                    return hub.api(roleMeta === 'ADMIN'
                        ? '/branch/search?' + qs.toString()
                        : branchListApiPath(roleMeta));
                },
                emptyTitle: 'Chưa có chi nhánh',
                emptyMessage: 'Hãy tạo chi nhánh đầu tiên để bắt đầu vận hành.',
                columns: [
                    { label: 'Tên chi nhánh', key: 'name' },
                    { label: 'Địa chỉ', key: 'address' },
                    { label: 'Số điện thoại', key: 'phone' },
                    { label: 'Trạng thái', render: function (r) { return statusBadge(r.status); } }
                ],
                actions: function (row) {
                    var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
                    if (roleMeta !== 'ADMIN') return [];
                    var arr = [
                        { label: 'Sửa', class: 'secondary', href: ctx + '/console?module=branch&action=edit&id=' + row.id }
                    ];
                    if (row.status === 'ACTIVE') {
                        arr.push({ label: 'Ngưng', class: 'danger', onClick: function () {
                            confirmAction('Ngưng hoạt động chi nhánh này?', function () {
                                hub.api('/branch/' + row.id, { method: 'PUT', body: { action: 'deactivate' } })
                                    .then(function () {
                                        hub.notify('Đã ngưng hoạt động chi nhánh.', 'ok');
                                        router.go('branch');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else if (row.status === 'INACTIVE') {
                        arr.push({ label: 'Mở lại', class: 'primary', onClick: function () {
                            confirmAction('Mở lại chi nhánh này?', function () {
                                hub.api('/branch/' + row.id, { method: 'PUT', body: { action: 'restore' } })
                                    .then(function () {
                                        hub.notify('Đã mở lại chi nhánh.', 'ok');
                                        router.go('branch');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () {
            Promise.all([
                hub.api('/api/users?role=BRANCH_MANAGER&limit=200'),
                hub.api('/api/users?role=BRANCH_STAFF&limit=200'),
                // Lấy active-map để LỌC RA user đã thuộc chi nhánh khác —
                // không cho chọn lại vì mỗi nhân viên chỉ nên thuộc 1 chi nhánh
                // tại một thời điểm.
                hub.api('/api/staff-assignments/active-map').catch(function () { return {}; })
            ]).then(function (responses) {
                var activeMap = responses[2] || {};
                function isAssigned(userId) {
                    var list = activeMap[userId] || activeMap[String(userId)];
                    return Array.isArray(list) && list.length > 0;
                }
                var managers = (Array.isArray(responses[0])
                        ? responses[0] : (responses[0].items || []))
                    .filter(function (u) { return u.status === 'ACTIVE' && !isAssigned(u.id); })
                    .map(function (u) {
                        return {
                            value: String(u.id),
                            label: (u.fullName || u.email || ('User #' + u.id))
                                + (u.email ? ' · ' + u.email : '')
                        };
                    });
                var staff = (Array.isArray(responses[1])
                        ? responses[1] : (responses[1].items || []))
                    .filter(function (u) { return u.status === 'ACTIVE' && !isAssigned(u.id); });
                form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Chi nhánh', href: ctx + '/console?module=branch' }, 'Tạo mới'],
                    title: 'Tạo chi nhánh mới',
                    subtitle: 'Chọn một Branch Manager và ít nhất một nhân viên. Danh sách chỉ hiển thị những người hiện chưa thuộc chi nhánh nào — nhân viên đã ở chi nhánh khác cần được chuyển chi nhánh trước.',
                    submitLabel: 'Tạo chi nhánh',
                    sections: [
                        {
                            title: 'Thông tin chi nhánh',
                            fields: [
                                { name: 'name', label: 'Tên chi nhánh', required: true, placeholder: 'VD: Cinema Hà Đông' },
                                { name: 'address', label: 'Địa chỉ', required: true, placeholder: 'Số nhà, đường, phường, quận, TP' },
                                { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, pattern: '0[0-9]{9}', placeholder: '0243xxxxxx' }
                            ]
                        },
                        {
                            title: 'Branch Manager',
                            description: 'Chi nhánh phải có ít nhất 1 Branch Manager để hoạt động.',
                            fields: [
                                { name: 'branchManagerId', label: 'Branch Manager (BRM)', type: 'select',
                                    required: true, options: managers,
                                    placeholder: managers.length ? '-- Chọn Branch Manager --' : 'Chưa có BRM khả dụng (tất cả đã thuộc chi nhánh khác)' }
                            ]
                        }
                    ],
                    renderFooter: function (formEl) {
                        var section = hub.el('section', { class: 'ws-form-section ws-initial-staff' });
                        section.appendChild(hub.el('h2', { class: 'ws-form-section-title' }, [
                            'Nhân viên ban đầu'
                        ]));
                        section.appendChild(hub.el('p', { class: 'ws-form-section-desc' }, [
                            'Chọn ít nhất một Branch Staff đang chưa thuộc chi nhánh nào.'
                        ]));
                        var search = hub.el('input', {
                            type: 'search',
                            class: 'ws-input',
                            placeholder: 'Tìm nhân viên theo tên hoặc email…',
                            'aria-label': 'Tìm nhân viên'
                        });
                        section.appendChild(search);
                        var staffList = hub.el('div', { class: 'ws-initial-staff-list' });
                        var selected = hub.el('input', {
                            type: 'hidden',
                            name: 'staffIds',
                            value: ''
                        });
                        var summary = hub.el('p', {
                            class: 'ws-field-hint',
                            'aria-live': 'polite'
                        });
                        var checkboxes = [];
                        function updateSelection() {
                            var ids = checkboxes.filter(function (item) {
                                return item.checkbox.checked;
                            }).map(function (item) {
                                return item.user.id;
                            });
                            selected.value = ids.join(',');
                            summary.textContent = ids.length
                                ? 'Đã chọn ' + ids.length + ' nhân viên.'
                                : 'Vui lòng chọn ít nhất một nhân viên.';
                        }
                        staff.forEach(function (user) {
                            var label = hub.el('label', { class: 'ws-initial-staff-row' });
                            var checkbox = hub.el('input', {
                                type: 'checkbox',
                                value: String(user.id)
                            });
                            checkbox.addEventListener('change', function () {
                                label.classList.toggle('is-selected', checkbox.checked);
                                updateSelection();
                            });
                            var info = hub.el('span', { class: 'ws-initial-staff-info' });
                            info.appendChild(hub.el('strong', {}, [
                                user.fullName || user.email || ('Nhân viên #' + user.id)
                            ]));
                            info.appendChild(hub.el('small', {}, [user.email || '']));
                            label.appendChild(checkbox);
                            label.appendChild(info);
                            staffList.appendChild(label);
                            checkboxes.push({
                                user: user,
                                checkbox: checkbox,
                                row: label
                            });
                        });
                        if (!staff.length) {
                            staffList.appendChild(hub.el('p', { class: 'ws-muted' }, [
                                'Chưa có tài khoản Branch Staff đang hoạt động và chưa được gán. Hãy tạo nhân viên trước.'
                            ]));
                        }
                        search.disabled = staff.length === 0;
                        search.addEventListener('input', function () {
                            var query = search.value.trim().toLocaleLowerCase();
                            checkboxes.forEach(function (item) {
                                var text = (item.user.fullName || '') + ' ' + (item.user.email || '');
                                item.row.hidden = query
                                    && text.toLocaleLowerCase().indexOf(query) === -1;
                            });
                        });
                        section.appendChild(staffList);
                        section.appendChild(summary);
                        section.appendChild(selected);
                        formEl.appendChild(section);
                        updateSelection();
                    },
                    onSubmit: function (data) {
                        if (!data.staffIds) {
                            return Promise.reject(new Error(
                                    'Chọn ít nhất một Branch Staff để tạo chi nhánh.'));
                        }
                        if (!data.branchManagerId) {
                            return Promise.reject(new Error(
                                    'Chọn một Branch Manager cho chi nhánh.'));
                        }
                        return hub.api('/branch', { method: 'POST', body: data })
                            .then(function () { router.go('branch'); });
                    },
                    onCancel: function () { router.go('branch'); }
                });
            }).catch(notifyError);
        },
        edit: function (id) {
            hub.api('/branch')
                .then(function (list) {
                    var branch = (list || []).find(function (b) { return String(b.id) === String(id); });
                    if (!branch) throw new Error('Không tìm thấy chi nhánh');
                    form.renderFormPage({
                        breadcrumb: ['Quản lý', { label: 'Chi nhánh', href: ctx + '/console?module=branch' }, 'Chỉnh sửa'],
                        title: 'Chỉnh sửa chi nhánh',
                        subtitle: branch.name,
                        submitLabel: 'Lưu thay đổi',
                        sections: [{
                            title: 'Thông tin chi nhánh',
                            fields: [
                                { name: 'name', label: 'Tên chi nhánh', required: true, value: branch.name },
                                { name: 'address', label: 'Địa chỉ', required: true, value: branch.address },
                                { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true, value: branch.phone, pattern: '0[0-9]{9}' }
                            ]
                        }],
                        onSubmit: function (data) {
                            hub.api('/branch/' + id, { method: 'PUT', body: data })
                                .then(function () { router.go('branch'); })
                                .catch(notifyError);
                        },
                        onCancel: function () { router.go('branch'); }
                    });
                })
                .catch(notifyError);
        }
    });

    /**
     * Module "Nhân viên chi nhánh" — dành cho BRM.
     * Hiển thị danh sách nhân viên (BRM + STAFF) thuộc chi nhánh BRM phụ trách,
     * cho phép tạo tài khoản nhân viên mới, bỏ gán, xem chi tiết.
     */
    router.register('branch-staff', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta !== 'BRANCH_MANAGER') {
                var viewRoot = document.getElementById('viewRoot');
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không có quyền truy cập']),
                    hub.el('p', {}, ['Chỉ Branch Manager mới được xem nhân viên chi nhánh.'])
                ]));
                return;
            }
            list.render({
                title: 'Nhân viên chi nhánh',
                subtitle: 'Danh sách nhân viên (BRM + Staff) thuộc chi nhánh của bạn. Có thể tạo tài khoản mới hoặc bỏ gán.',
                addUrl: ctx + '/console?module=branch-staff&action=create',
                addLabel: 'Tạo tài khoản nhân viên',
                searchPlaceholder: 'Tìm theo tên, email, số điện thoại…',
                pageSize: 50,
                filters: [{
                    name: 'role', label: 'Vai trò',
                    options: [
                        { value: 'BRANCH_MANAGER', label: 'Branch Manager' },
                        { value: 'BRANCH_STAFF', label: 'Nhân viên' }
                    ]
                }],
                fetcher: function (qs) {
                    return hub.api(branchListApiPath(roleMeta)).then(function (branches) {
                        if (!Array.isArray(branches) || !branches.length) {
                            return { items: [], total: 0, branch: null };
                        }
                        var branch = branches[0];
                        return hub.api('/api/staff-assignments?branchId='
                                + encodeURIComponent(branch.id) + '&all=true')
                            .then(function (rows) {
                                var list2 = Array.isArray(rows) ? rows : [];
                                var items = list2.map(function (r) {
                                    // Backend có thể trả assignmentId hoặc id — luôn
                                    // ưu tiên assignmentId cho action DELETE.
                                    var assignmentId = r.assignmentId || r.id;
                                    var userId = r.userId || r.staffId || r.user_id;
                                    return {
                                        id: userId,                 // userId cho list-page
                                        assignmentId: assignmentId,  // assignmentId cho DELETE
                                        userId: userId,
                                        fullName: r.fullName,
                                        email: r.email,
                                        role: r.roleCode,
                                        status: 'ACTIVE'
                                    };
                                });
                                var roleFilter = qs.get('role');
                                if (roleFilter && roleFilter !== 'all') {
                                    items = items.filter(function (it) {
                                        return it.role === roleFilter;
                                    });
                                }
                                var q = (qs.get('q') || '').toLowerCase().trim();
                                if (q) {
                                    items = items.filter(function (it) {
                                        return (it.fullName || '').toLowerCase().indexOf(q) !== -1
                                            || (it.email || '').toLowerCase().indexOf(q) !== -1;
                                    });
                                }
                                return { items: items, total: items.length, branch: branch };
                            });
                    });
                },
                emptyTitle: 'Chưa có nhân viên',
                emptyMessage: 'Bấm "Tạo tài khoản nhân viên" để thêm người vào chi nhánh.',
                columns: [
                    { label: 'Họ tên', key: 'fullName' },
                    { label: 'Email', key: 'email' },
                    { label: 'Vai trò', render: function (r) {
                        var badge = document.createElement('span');
                        if (r.role === 'BRANCH_MANAGER') {
                            badge.className = 'ws-bsap-badge ok';
                            badge.textContent = 'Branch Manager';
                        } else {
                            badge.className = 'ws-bsap-badge warn';
                            badge.textContent = 'Nhân viên';
                        }
                        return badge;
                    } }
                ],
                actions: function (row) {
                    var arr = [
                        { label: 'Chi tiết', class: 'secondary',
                          href: ctx + '/console?module=users&action=edit&id=' + row.userId }
                    ];
                    if (row.role !== 'BRANCH_MANAGER') {
                        arr.push({ label: 'Bỏ gán', class: 'danger', onClick: function () {
                            var label = row.fullName || row.email || ('User #' + row.userId);
                            confirmAction('Bỏ gán "' + label + '" khỏi chi nhánh này?', function () {
                                hub.api('/api/staff-assignments/' + row.assignmentId,
                                        { method: 'DELETE' })
                                    .then(function () {
                                        hub.notify('Đã bỏ gán ' + label + '.', 'ok');
                                        router.go('branch-staff');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta !== 'BRANCH_MANAGER') {
                notifyError(new Error('Không có quyền tạo tài khoản.'));
                return;
            }
            var roleOptions = [{ value: 'BRANCH_STAFF', label: 'Nhân viên chi nhánh' }];
            form.renderFormPage({
                breadcrumb: ['Quản lý chi nhánh',
                    { label: 'Nhân viên', href: ctx + '/console?module=branch-staff' },
                    'Tạo mới'],
                title: 'Tạo tài khoản nhân viên',
                subtitle: 'Hệ thống tự sinh mật khẩu và gửi qua email cho nhân viên.',
                submitLabel: 'Tạo tài khoản',
                sections: [{
                    title: 'Thông tin tài khoản',
                    description: 'Các trường có dấu * là bắt buộc.',
                    fields: [
                        { name: 'fullName', label: 'Họ và tên', required: true,
                          placeholder: 'Nguyễn Văn A' },
                        { name: 'email', label: 'Email', type: 'email', required: true,
                          placeholder: 'staff@example.com', autocomplete: 'email' },
                        { name: 'phone', label: 'Số điện thoại', type: 'tel', required: true,
                          pattern: '0[0-9]{9}',
                          patternMessage: 'Số điện thoại phải bắt đầu bằng 0 và có 10 chữ số',
                          placeholder: '0912345678', autocomplete: 'tel' },
                        { name: 'role', label: 'Vai trò', type: 'select', required: true,
                          options: roleOptions,
                          disabled: roleMeta !== 'ADMIN',
                          value: 'BRANCH_STAFF' }
                    ]
                }],
                renderFooter: function (formEl) {
                    var note = hub.el('div', { class: 'ws-form-section' });
                    note.appendChild(hub.el('p', { class: 'ws-muted' }, [
                        'Tài khoản sau khi tạo sẽ được tự động gán vào chi nhánh của bạn.'
                    ]));
                    formEl.appendChild(note);
                },
                onSubmit: function (data) {
                    return hub.api('/api/users', { method: 'POST', body: data })
                        .then(function (resp) {
                            if (resp && resp.generatedPassword) {
                                hub.notify('Đã tạo tài khoản. Mật khẩu tạm: '
                                        + resp.generatedPassword
                                        + (resp.emailSent ? ' (đã gửi email)' : ''), 'ok', 8000);
                            } else {
                                hub.notify('Đã tạo tài khoản nhân viên.', 'ok');
                            }
                            if (resp && resp.assignmentError) {
                                hub.notify('Lỗi gán chi nhánh: ' + resp.assignmentError, 'warn', 6000);
                            }
                            router.go('branch-staff');
                        });
                },
                onCancel: function () { router.go('branch-staff'); }
            });
        }
    });


    // ================================================================
    //  MOVIE (Admin only)
    // ================================================================
    // (Hàm renderMoviePriceRules đã được lược bỏ cùng module "Bảng giá vé".)

    function renderMovieForm(movie, genres, branches) {
        var isEdit = !!movie;
        branches = branches || [];
        var movieTemplatePicker = null;
        var roleMeta = document.querySelector('meta[name="user-role"]');

        function openMovieEdit(movieId, selections) {
            var query = new URLSearchParams({
                module: 'movie',
                action: 'edit',
                id: String(movieId)
            });
            if (selections && selections.length) {
                query.set('templateAssignments', selections.map(function (selection) {
                    return selection.branchIds[0] + ':' + selection.templateId;
                }).join(','));
            }
            window.location.href = ctx + '/console?' + query.toString();
        }

        function finishTemplateApply(movieId, selections) {
            if (!selections || !selections.length) {
                router.go('movie');
                return Promise.resolve();
            }
            function keepEditing(message, failedSelections) {
                hub.notify('Phim đã được lưu nhưng giá mẫu chưa hoàn tất. ' + message
                    + ' Có thể thử lại trong màn hình chỉnh sửa phim.', 'err');
                openMovieEdit(movieId, failedSelections || selections);
            }
            return Promise.all(selections.map(function (selection) {
                return hub.api('/pricing-templates/' + encodeURIComponent(selection.templateId)
                    + '/preview', {
                        method: 'POST',
                        body: JSON.stringify({ movieId: movieId, branchIds: selection.branchIds }),
                        contentType: 'application/json;charset=UTF-8'
                    }).then(function (preview) {
                        return { selection: selection, preview: preview };
                    });
            })).then(function (prepared) {
                var conflicts = [];
                prepared.forEach(function (item) {
                    (item.preview.branches || []).filter(function (branch) {
                        return branch.conflict;
                    }).forEach(function (branch) {
                        conflicts.push({ selection: item.selection, branch: branch });
                    });
                });
                if (conflicts.length) {
                    var detail = conflicts.map(function (item) {
                        var branch = item.selection.branches[0];
                        return branch.name + ' · ' + item.selection.templateName
                            + ': ' + item.branch.existingRuleCount + ' khung giá hiện có';
                    }).join('\n');
                    if (!window.confirm('Các chi nhánh sau đã có giá cho phim:\n' + detail
                            + '\n\nThay thế toàn bộ giá tại các chi nhánh bị ảnh hưởng?')) {
                        keepEditing('Bạn đã hủy thao tác áp dụng.', selections);
                        return null;
                    }
                }
                return prepared.reduce(function (chain, item) {
                    return chain.then(function (results) {
                        var expectedState = {};
                        (item.preview.branches || []).forEach(function (branch) {
                            expectedState[String(branch.branchId)] = branch.stateToken;
                        });
                        var hasConflict = (item.preview.branches || []).some(function (branch) {
                            return branch.conflict;
                        });
                        return hub.api('/pricing-templates/'
                            + encodeURIComponent(item.selection.templateId) + '/apply', {
                                method: 'POST',
                                body: JSON.stringify({
                                    movieId: movieId,
                                    branchIds: item.selection.branchIds,
                                    expectedState: expectedState,
                                    replaceExisting: hasConflict
                                }),
                                contentType: 'application/json;charset=UTF-8'
                            }).then(function (result) {
                                return results.concat(result.branches || []);
                            }).catch(function (error) {
                                if (error.status === 409 && error.payload
                                        && Array.isArray(error.payload.results)) {
                                    return results.concat(error.payload.results);
                                }
                                return results.concat(item.selection.branches.map(function (branch) {
                                    return {
                                        branchId: branch.id,
                                        status: 'FAILED',
                                        message: error.message || 'Không thể áp dụng mẫu giá.'
                                    };
                                }));
                            });
                    });
                }, Promise.resolve([]));
            }).then(function (results) {
                    if (results === null) return;
                    var applied = results.filter(function (branch) {
                        return branch.status === 'APPLIED';
                    });
                    var failed = results.filter(function (branch) {
                        return branch.status !== 'APPLIED';
                    });
                    if (!failed.length) {
                        hub.notify('Đã áp dụng mẫu giá cho ' + applied.length + ' chi nhánh.', 'ok');
                        router.go('movie');
                        return;
                    }
                    var failedSelections = selections.filter(function (selection) {
                        return failed.some(function (branch) {
                            return selection.branchIds.some(function (branchId) {
                                return String(branchId) === String(branch.branchId);
                            });
                        });
                    });
                    var details = failed.map(function (branch) {
                        var target = selections.reduce(function (found, selection) {
                            return found || selection.branches.find(function (item) {
                                return String(item.id) === String(branch.branchId);
                            });
                        }, null);
                        return (target ? target.name : ('Chi nhánh #' + branch.branchId))
                            + ': ' + (branch.message || branch.status);
                    }).join(' · ');
                    keepEditing(details, failedSelections);
                }).catch(function (error) {
                keepEditing(error.message || 'Không thể áp dụng mẫu giá.', selections);
            });
        }

        // Trạng thái poster: lưu URL hiện tại; nếu upload mới thì thay đổi URL này.
        // Khi không upload ảnh mới, URL cũ được giữ nguyên → không làm mất ảnh cũ.
        var posterState = {
            currentUrl: (movie && movie.posterUrl) || '',
            pendingFile: null
        };

        var sections = [{
            title: 'Thông tin cơ bản',
            description: 'Mô tả và chi tiết phim.',
            fields: [
                { name: 'title', label: 'Tên phim', required: true, value: movie && movie.title, fullWidth: true },
                { name: 'author', label: 'Tác giả / Đạo diễn', required: false,
                  value: movie && movie.author, placeholder: 'VD: Christopher Nolan, Trần Anh Hùng…',
                  maxLength: 200,
                  },
                { name: 'durationMin', label: 'Thời lượng (phút)', type: 'number', required: true, min: 1, max: 600, value: movie && movie.durationMin },
                { name: 'genre', label: 'Thể loại phim', type: 'checkbox-group',
                  className: 'ws-genre-field', required: true,
                  value: movie && movie.genre ? movie.genre.split(/\s*[,/;]\s*/).filter(Boolean) : [],
                  options: genres.map(function (genre) { return { value: genre.name, label: genre.name }; }),
                  hint: 'Chọn một hoặc nhiều thể loại.' },
                { name: 'rating', label: 'Giới hạn tuổi', type: 'select', required: true,
                  value: (movie && movie.rating) || 'P',
                  hint: 'Chọn phân loại độ tuổi phù hợp.', options: [
                    { value: 'P', label: 'P - Mọi lứa tuổi' },
                    { value: 'C13', label: 'C13 - Trên 13 tuổi' },
                    { value: 'C16', label: 'C16 - Trên 16 tuổi' },
                    { value: 'C18', label: 'C18 - Trên 18 tuổi' }
                ]},
                { name: 'releaseDate', label: 'Ngày phát hành', type: 'date', required: true, value: dateValue(movie && movie.releaseDate) },
                { name: 'endDate', label: 'Ngày kết thúc', type: 'date', required: true, value: dateValue(movie && movie.endDate) },
                { name: 'status', label: 'Trạng thái', type: 'select', required: true, value: (movie && movie.status) || 'DRAFT', options: [
                    { value: 'DRAFT', label: 'Bản nháp' },
                    { value: 'PUBLISHED', label: 'Đang phát hành' },
                    { value: 'ARCHIVED', label: 'Đã lưu trữ' }
                ]},
                { name: 'description', label: 'Mô tả', type: 'textarea', required: false, value: movie && movie.description, rows: 5, fullWidth: true,
                  hint: 'Mô tả ngắn gọn nội dung phim để hiển thị cho khách hàng.' }
            ]
        }];

        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phim', href: ctx + '/console?module=movie' }, isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa phim' : 'Thêm phim mới',
            subtitle: isEdit ? movie.title : 'Tạo bản ghi phim mới trong hệ thống.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo phim',
            sections: sections,
            // renderFooter: chèn khối poster upload + hidden posterUrl sau form (sau sections)
            renderFooter: function (formEl) {
                if (window.CinemaHubPricingTemplates && roleMeta
                        && (roleMeta.content === 'ADMIN' || roleMeta.content === 'BRANCH_MANAGER')) {
                    var queryParams = new URLSearchParams(window.location.search);
                    var requestedBranches = (queryParams.get('branchIds') || '')
                        .split(',').filter(Boolean);
                    movieTemplatePicker = window.CinemaHubPricingTemplates.createBranchApplyPicker({
                        branches: branches,
                        preselectedTemplateId: queryParams.get('templateId'),
                        preselectedBranchIds: requestedBranches,
                        preselectedAssignments: queryParams.get('templateAssignments')
                    });
                    formEl.appendChild(movieTemplatePicker.element);
                }

                var wrap = hub.el('section', { class: 'ws-form-section ws-form-poster-section' });
                wrap.appendChild(hub.el('h2', { class: 'ws-form-section-title' }, ['Poster phim']));
                wrap.appendChild(hub.el('p', { class: 'ws-form-section-desc' },
                    ['Chọn ảnh poster để upload qua Cloudinary. Nếu không chọn ảnh mới, hệ thống sẽ giữ nguyên poster hiện tại.']));

                var grid = hub.el('div', { class: 'ws-poster-uploader' });

                // Preview box
                var preview = hub.el('div', { class: 'ws-poster-preview', id: 'moviePosterPreview' });
                function renderPreview() {
                    preview.replaceChildren();
                    if (posterState.currentUrl) {
                        var img = hub.el('img', { src: posterState.currentUrl, alt: 'Poster' });
                        preview.appendChild(img);
                    } else {
                        preview.appendChild(hub.el('div', { class: 'ws-poster-empty' }, ['Chưa có poster']));
                    }
                }
                renderPreview();
                grid.appendChild(preview);

                // Controls
                var controls = hub.el('div', { class: 'ws-poster-controls' });
                var fileInput = hub.el('input', {
                    type: 'file', accept: 'image/jpeg,image/jpg,image/png,image/webp',
                    id: 'moviePosterFile', name: 'moviePosterFile'
                });
                controls.appendChild(hub.el('label', { for: 'moviePosterFile', class: 'ws-btn secondary' }, ['Chọn ảnh mới']));
                controls.appendChild(fileInput);

                var hint = hub.el('p', { class: 'ws-field-hint' },
                    ['Hỗ trợ JPEG/PNG/WEBP, tối đa 5MB. Ảnh sẽ được upload lên Cloudinary.']);
                controls.appendChild(hint);

                // Status line
                var status = hub.el('div', { class: 'ws-poster-status', id: 'moviePosterStatus' });
                controls.appendChild(status);

                var hidden = hub.el('input', {
                    type: 'hidden', id: 'fld-posterUrl', name: 'posterUrl',
                    value: posterState.currentUrl || ''
                });
                controls.appendChild(hidden);

                // Upload-on-change handler: tải file lên Cloudinary, cập nhật hidden posterUrl.
                fileInput.addEventListener('change', function () {
                    var f = fileInput.files && fileInput.files[0];
                    if (!f) return;

                    if (f.size > 5 * 1024 * 1024) {
                        status.textContent = 'File vượt quá 5MB.';
                        status.className = 'ws-poster-status error';
                        fileInput.value = '';
                        return;
                    }

                    status.textContent = 'Đang upload lên Cloudinary...';
                    status.className = 'ws-poster-status uploading';
                    var oldUrl = posterState.currentUrl;
                    hub.uploadFile(f, 'file').then(function (data) {
                        posterState.currentUrl = data.url || '';
                        hidden.value = posterState.currentUrl;
                        posterState.pendingFile = f.name;
                        renderPreview();
                        status.textContent = 'Đã upload: ' + (data.filename || f.name);
                        status.className = 'ws-poster-status success';
                    }).catch(function (err) {
                        status.textContent = 'Upload thất bại: ' + (err.message || 'lỗi không xác định') + '. Giữ nguyên ảnh cũ.';
                        status.className = 'ws-poster-status error';
                        posterState.currentUrl = oldUrl;
                        hidden.value = oldUrl;
                        fileInput.value = '';
                        renderPreview();
                    });
                });

                grid.appendChild(controls);
                wrap.appendChild(grid);

                // Reference hidden input in formData so onSubmit can pick it up.
                // (Already name="posterUrl" so collectData() will include it.)

                formEl.appendChild(wrap);
            },
            onSubmit: function (data) {
                // posterUrl đã được đồng bộ với hidden input ở trên.
                // Nếu user không upload ảnh mới, posterUrl giữ nguyên giá trị ban đầu (movie.posterUrl cũ).
                var pickerReady = movieTemplatePicker
                    ? movieTemplatePicker.prepareSelection() : Promise.resolve([]);
                return pickerReady.then(function (templateSelections) {
                    if (typeof data.author === 'string') data.author = data.author.trim();
                    templateSelections = templateSelections || [];
                    if (isEdit) {
                        return hub.api('/movie/' + movie.id, { method: 'PUT', body: data })
                            .then(function () {
                                if (templateSelections.length) {
                                    return finishTemplateApply(movie.id, templateSelections);
                                }
                                router.go('movie');
                            });
                    }
                    return hub.api('/movie', { method: 'POST', body: data })
                        .then(function (createdMovie) {
                            if (templateSelections.length) {
                                return finishTemplateApply(createdMovie.id, templateSelections);
                            }
                            hub.notify('Tạo phim thành công.', 'ok');
                            router.go('movie');
                        });
                });
            },
            onCancel: function () { router.go('movie'); }
        });
    }

    router.register('movie', {
        list: function () {
            var roleMeta = document.querySelector('meta[name="user-role"]');
            var isAdmin = !!roleMeta && roleMeta.content === 'ADMIN';
            list.render({
                title: 'Phim',
                subtitle: 'Danh mục phim trong hệ thống và trạng thái phát hành.',
                addUrl: isAdmin ? ctx + '/console?module=movie&action=create' : null,
                addLabel: isAdmin ? 'Thêm phim' : null,
                searchPlaceholder: 'Tìm theo tên phim…',
                pageSize: 20,
                filters: [
                    { name: 'status', label: 'Trạng thái', options: [
                        { value: 'DRAFT', label: 'Bản nháp' },
                        { value: 'PUBLISHED', label: 'Đang phát hành' },
                        { value: 'ARCHIVED', label: 'Đã lưu trữ' }
                    ]},
                    { name: 'rating', label: 'Giới hạn tuổi', options: [
                        { value: 'P', label: 'P - Mọi lứa tuổi' },
                        { value: 'C13', label: 'C13 - Trên 13 tuổi' },
                        { value: 'C16', label: 'C16 - Trên 16 tuổi' },
                        { value: 'C18', label: 'C18 - Trên 18 tuổi' }
                    ]}
                ],
                sortable: true,
                sortOptions: [
                    { value: 'title', label: 'Tên A→Z' },
                    { value: 'releaseDate', label: 'Ngày phát hành' }
                ],
                fetcher: function (qs) {
                    var movieRequest = hub.api('/movie/search?' + qs.toString());
                    return movieRequest.then(function (movieResponse) {
                        var movies = Array.isArray(movieResponse) ? movieResponse
                            : movieResponse && Array.isArray(movieResponse.items)
                                ? movieResponse.items : null;
                        if (!movies) {
                            throw new Error('Dữ liệu danh sách phim không hợp lệ.');
                        }
                        if (Array.isArray(movieResponse)) return movies;
                        return Object.assign({}, movieResponse, { items: movies });
                    });
                },
                emptyTitle: 'Chưa có phim',
                emptyMessage: 'Danh mục phim đang trống. Hãy thêm phim đầu tiên.',
                columns: [
                    { label: 'Poster', render: function (m) {
                        if (!m.posterUrl) return document.createTextNode('—');
                        var img = document.createElement('img');
                        img.src = m.posterUrl;
                        img.alt = m.title || '';
                        // Lazy + referrerpolicy giúp Cloudinary không bị hotlink-protected;
                        // onerror fallback tự ẩn cell khi URL chết — không để gãy layout.
                        img.loading = 'lazy';
                        img.referrerPolicy = 'no-referrer';
                        img.crossOrigin = 'anonymous';
                        img.style.cssText = 'width:48px;height:64px;object-fit:cover;border-radius:6px;display:block;background:#f3f4f6';
                        img.addEventListener('error', function () {
                            img.replaceWith(document.createTextNode('—'));
                        });
                        return img;
                    }, width: '70px' },
                    { label: 'Tên phim', key: 'title' },
                    { label: 'Thể loại', key: 'genre' },
                    { label: 'Thời lượng', render: function (m) { return m.durationMin ? (m.durationMin + ' phút') : '—'; } },
                    { label: 'Giới hạn tuổi', key: 'rating' },
                    { label: 'Đánh giá', render: function (m) {
                        // Backend MovieController enrich sẵn averageRating + reviewCount.
                        // Hiển thị "★ X.X / 5  (N)" — nếu chưa có review thì "—".
                        var avg = (m.averageRating != null) ? Number(m.averageRating) : 0;
                        var count = (m.reviewCount != null) ? Number(m.reviewCount) : 0;
                        if (!count || isNaN(count) || count <= 0) {
                            return document.createTextNode('—');
                        }
                        var span = document.createElement('span');
                        span.className = 'ws-movie-rating';
                        var full = Math.floor(avg);
                        var half = (avg - full) >= 0.5 ? 1 : 0;
                        var empty = 5 - full - half;
                        var stars = '★'.repeat(Math.max(full, 0))
                            + (half ? '½' : '')
                            + '☆'.repeat(Math.max(empty, 0));
                        span.appendChild(document.createTextNode(stars + ' '));
                        var strong = document.createElement('strong');
                        strong.textContent = avg.toFixed(1) + '/5';
                        span.appendChild(strong);
                        var small = document.createElement('small');
                        small.className = 'ws-muted';
                        small.style.marginLeft = '6px';
                        small.textContent = '(' + count + ')';
                        span.appendChild(small);
                        return span;
                    } },
                    { label: 'Trạng thái', render: function (m) { return statusBadge(m.status); } }
                ],
                actions: function (row) {
                    if (!isAdmin && row.status !== 'PUBLISHED') return [];
                    if (isAdmin) {
                    var arr = [
                        { label: 'Sửa', class: 'secondary', href: ctx + '/console?module=movie&action=edit&id=' + row.id },
                        // BR-PR-01 — Admin cũng có thể vào trang sửa giá của từng phim,
                        // danh sách chi nhánh sẽ hiện ra sau khi chọn phim.
                        { label: 'Sửa giá vé', class: 'secondary', href: ctx + '/console?module=movie-price&action=list&movieId=' + encodeURIComponent(row.id) }
                    ];
                    if (row.status === 'DRAFT') {
                        arr.push({ label: 'Phát hành', class: 'primary', onClick: function () {
                            confirmAction('Phát hành phim này?', function () {
                                hub.api('/movie/' + row.id, { method: 'PUT', body: { action: 'publish' } })
                                    .then(function () {
                                        hub.notify('Đã phát hành phim.', 'ok');
                                        router.go('movie');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else if (row.status === 'PUBLISHED') {
                        arr.push({ label: 'Lưu trữ', class: 'danger', onClick: function () {
                            confirmAction('Lưu trữ phim này?', function () {
                                hub.api('/movie/' + row.id, { method: 'PUT', body: { action: 'archive' } })
                                    .then(function () {
                                        hub.notify('Đã lưu trữ phim.', 'ok');
                                        router.go('movie');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else if (row.status === 'ARCHIVED') {
                        arr.push({ label: 'Mở lại', class: 'primary', onClick: function () {
                            confirmAction('Mở lại phim này?', function () {
                                hub.api('/movie/' + row.id, { method: 'PUT', body: { action: 'restore' } })
                                    .then(function () {
                                        hub.notify('Đã mở lại phim.', 'ok');
                                        router.go('movie');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                    }
                    // BR-PR-01 — Manager chỉ thấy phim PUBLISHED và có thể tạo suất chiếu.
                    return [
                        {
                            label: 'Tạo suất chiếu',
                            class: 'primary',
                            href: ctx + '/console?module=showtime&action=create&movieId='
                                + encodeURIComponent(row.id) + '&releaseDate='
                                + encodeURIComponent(dateValue(row.releaseDate))
                        }
                    ];
                }
            });
        },
        create: function () {
            var roleMeta = document.querySelector('meta[name="user-role"]');
            var isManager = !!roleMeta && roleMeta.content === 'BRANCH_MANAGER';
            var branchesRequest = isManager ? hub.api('/branch/my-location') : hub.api('/branch/');
            Promise.all([hub.api('/movie/genres'), branchesRequest]).then(function (data) {
                renderMovieForm(null, data[0], data[1]);
            }).catch(notifyError);
        },
        edit: function (id) {
            var roleMeta = document.querySelector('meta[name="user-role"]');
            var isManager = !!roleMeta && roleMeta.content === 'BRANCH_MANAGER';
            var branchesRequest = isManager ? hub.api('/branch/my-location') : hub.api('/branch/');
            Promise.all([
                hub.api('/movie/' + id),
                hub.api('/movie/genres'),
                branchesRequest
            ])
                .then(function (data) {
                    renderMovieForm(data[0], data[1], data[2]);
                })
                .catch(notifyError);
        }
    });

    function renderGenreForm(genre) {
        var isEdit = !!genre;
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Thể loại phim', href: ctx + '/console?module=genres' },
                isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa thể loại phim' : 'Thêm thể loại phim',
            subtitle: 'Danh mục thể loại được dùng khi tạo phim và lọc phim cho khách hàng.',
            submitLabel: isEdit ? 'Lưu thể loại' : 'Tạo thể loại',
            sections: [{
                title: 'Thông tin thể loại',
                fields: [{
                    name: 'name', label: 'Tên thể loại', required: true, value: genre && genre.name,
                    maxLength: 100
                }]
            }],
            onSubmit: function (data) {
                hub.api(isEdit ? '/movie/genres/' + genre.id : '/movie/genres', {
                    method: isEdit ? 'PUT' : 'POST', body: data
                }).then(function () {
                    router.go('genres');
                }).catch(notifyError);
            },
            onCancel: function () { router.go('genres'); }
        });
    }

    router.register('genres', {
        list: function () {
            list.render({
                title: 'Thể loại phim',
                subtitle: 'Thêm, sửa hoặc xóa thể loại dùng trong danh mục phim.',
                addUrl: ctx + '/console?module=genres&action=create',
                addLabel: 'Thêm thể loại',
                fetcher: function () {
                    return hub.api('/movie/genres').then(function (items) {
                        return { items: items, total: items.length, offset: 0, limit: items.length || 1 };
                    });
                },
                emptyTitle: 'Chưa có thể loại',
                emptyMessage: 'Hãy thêm thể loại phim đầu tiên.',
                columns: [
                    { label: 'Tên thể loại', key: 'name' }
                ],
                actions: function (genre) {
                    return [
                        { label: 'Sửa', class: 'secondary',
                          href: ctx + '/console?module=genres&action=edit&id=' + genre.id },
                        { label: 'Xóa', class: 'danger', onClick: function () {
                            confirmAction('Xóa thể loại "' + genre.name + '"?', function () {
                                hub.api('/movie/genres/' + genre.id, { method: 'DELETE' })
                                    .then(function () {
                                        hub.notify('Đã xóa thể loại phim.', 'ok');
                                        router.go('genres');
                                    }).catch(notifyError);
                            });
                        }}
                    ];
                }
            });
        },
        create: function () { renderGenreForm(null); },
        edit: function (id) {
            hub.api('/movie/genres').then(function (genres) {
                var genre = genres.find(function (item) { return String(item.id) === String(id); });
                if (!genre) throw new Error('Không tìm thấy thể loại phim.');
                renderGenreForm(genre);
            }).catch(notifyError);
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

    function renderSeatLayoutPicker(container, hiddenLayout, rowCount, colCount, initialSeats, archivedPositions) {
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
        archivedPositions = archivedPositions || new Set();
        (initialSeats || []).forEach(function (seat) {
            if ((!seat.status || seat.status === 'ACTIVE')
                    && seat.rowLabel.charCodeAt(0) - 65 < rowCount
                    && Number(seat.colNo) <= colCount) {
                layout.set(seat.rowLabel + ':' + seat.colNo, seat.seatType);
            }
        });
        Array.from(layout.entries()).forEach(function (entry) {
            if (entry[1] !== 'COUPLE' || layout.get(entry[0]) !== 'COUPLE') return;
            var parts = entry[0].split(':');
            var pairedPosition = parts[0] + ':' + (Number(parts[1]) + 1);
            layout.delete(pairedPosition);
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
            hiddenLayout.dispatchEvent(new Event('change'));
            var counts = { STANDARD: 0, VIP: 0, COUPLE: 0 };
            layout.forEach(function (type) { counts[type] = (counts[type] || 0) + 1; });
            var capacity = counts.STANDARD + counts.VIP + counts.COUPLE * 2;
            summary.textContent = 'Tổng ' + capacity + ' chỗ · Thường ' + counts.STANDARD
                + ' · VIP ' + counts.VIP + ' · Đôi ' + counts.COUPLE + ' ghế (2 chỗ/ghế)';
            layout.forEach(function (type, key) {
                var parts = key.split(':');
                if (type === 'COUPLE' && Number(parts[1]) >= colCount) {
                    summary.textContent += ' · Lỗi: ghế đôi cuối hàng cần thêm một cột.';
                }
            });
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
            ['Ghế đôi chiếm 2 cột liền nhau: chọn ô bên trái để tạo một ghế đôi. Ô trống sẽ không xuất hiện khi khách đặt vé.']));

        function paint(cell) {
            var key = cell.getAttribute('data-row') + ':' + cell.getAttribute('data-col');
            if (painted.has(key)) return;
            painted.add(key);
            var rowLabel = cell.getAttribute('data-row');
            var colNo = Number(cell.getAttribute('data-col'));
            var previousKey = rowLabel + ':' + (colNo - 1);
            var nextKey = rowLabel + ':' + (colNo + 1);
            var coupleAnchor = layout.get(key) === 'COUPLE' ? key
                : layout.get(previousKey) === 'COUPLE' ? previousKey : null;

            if (selectedTool === 'COUPLE') {
                if (coupleAnchor) return;
                if (colNo >= colCount) {
                    summary.textContent = 'Ghế đôi cần 2 cột liền nhau; không thể đặt tại cột cuối.';
                    return;
                }
                if (layout.get(previousKey) === 'COUPLE') layout.delete(previousKey);
                if (layout.get(nextKey) === 'COUPLE') layout.delete(nextKey);
                layout.set(key, 'COUPLE');
                layout.delete(nextKey);
            } else if (coupleAnchor) {
                layout.delete(coupleAnchor);
                if (selectedTool !== 'REMOVE') {
                    var anchorCol = Number(coupleAnchor.split(':')[1]);
                    layout.set(coupleAnchor, selectedTool);
                    if (anchorCol < colCount) {
                        layout.set(rowLabel + ':' + (anchorCol + 1), selectedTool);
                    }
                }
            } else if (selectedTool === 'REMOVE') {
                layout.delete(key);
            } else {
                layout.set(key, selectedTool);
            }

            grid.querySelectorAll('.seat-layout-cell').forEach(function (seatCell) {
                var cellRow = seatCell.getAttribute('data-row');
                var cellCol = Number(seatCell.getAttribute('data-col'));
                var cellKey = cellRow + ':' + cellCol;
                var cellType = layout.get(cellKey);
                var isCouplePartner = layout.get(cellRow + ':' + (cellCol - 1)) === 'COUPLE';
                var displayedType = isCouplePartner ? 'COUPLE' : cellType;
                var isMaintenance = !displayedType && archivedPositions.has(cellKey);
                seatCell.classList.toggle('is-empty', !displayedType && !isMaintenance);
                seatCell.classList.toggle('is-maintenance', isMaintenance);
                seatCell.classList.toggle('is-vip', displayedType === 'VIP');
                seatCell.classList.toggle('is-couple', displayedType === 'COUPLE');
                seatCell.disabled = isMaintenance;
                seatCell.textContent = displayedType ? String(cellCol) : isMaintenance ? '×' : '';
                seatCell.setAttribute('aria-label', cellRow + cellCol
                    + (isCouplePartner ? ' phần ghế đôi' : cellType ? ' ' + cellType
                        : isMaintenance ? ' - đang bảo trì' : ' - không có ghế'));
            });
            syncLayout();
        }

        for (var r = 0; r < rowCount; r++) {
            var rowLabel = String.fromCharCode(65 + r);
            var row = el('div', { class: 'seat-row' });
            row.appendChild(el('span', { class: 'seat-row-label seat-layout-row-label' }, [rowLabel]));
            for (var c = 1; c <= colCount; c++) {
                var key = rowLabel + ':' + c;
                var type = layout.get(key);
                var isCouplePartner = layout.get(rowLabel + ':' + (c - 1)) === 'COUPLE';
                var displayedType = isCouplePartner ? 'COUPLE' : type;
                var isMaintenance = !displayedType && archivedPositions.has(key);
                var cell = el('button', {
                    type: 'button',
                    class: 'seat-cell seat-layout-cell' + (displayedType ? '' : ' is-empty')
                        + (displayedType === 'VIP' ? ' is-vip' : '')
                        + (displayedType === 'COUPLE' ? ' is-couple' : '')
                        + (isMaintenance ? ' is-maintenance' : ''),
                    'data-row': rowLabel,
                    'data-col': String(c),
                    'aria-label': rowLabel + c + (isCouplePartner ? ' phần ghế đôi'
                        : type ? ' ' + type : isMaintenance ? ' - đang bảo trì' : ' - không có ghế')
                }, [displayedType ? String(c) : isMaintenance ? '×' : '']);
                cell.disabled = isMaintenance;
                cell.classList.toggle('is-empty', !displayedType && !isMaintenance);
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
                // Section sơ đồ ghế
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
                if (archivedSeats.length) {
                    desc.textContent = 'Ô gạch chéo là ghế bảo trì; chọn đúng ghế trong danh sách bên dưới để mở lại.';
                }
                var archivedSeatSection = null;

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
                        var seatCode = position.replace(':', '');
                        button.textContent = restored ? 'Đã chọn mở lại ' + seatCode
                            : (inBounds ? 'Mở lại ghế ' + seatCode
                                : seatCode + ' (ngoài kích thước phòng)');
                        button.setAttribute('aria-label', button.textContent);
                    });
                }

                function redrawSeatPicker() {
                    var rowInput = formEl.querySelector('input[name="rowCount"]');
                    var colInput = formEl.querySelector('input[name="colCount"]');
                    if (!rowInput || !colInput) return;
                    if (hidden.value) {
                        try { initialSeats = JSON.parse(hidden.value); } catch (_) {
                            throw new Error('Không đọc được sơ đồ ghế hiện tại.');
                        }
                    }
                    var nextRows = Math.max(1, Math.min(26, parseInt(rowInput.value, 10) || 1));
                    var nextCols = Math.max(1, Math.min(30, parseInt(colInput.value, 10) || 1));
                    if (nextRows > currentRows || nextCols > currentCols) {
                        var positions = new Set(initialSeats.map(function (seat) {
                            return seat.rowLabel + ':' + seat.colNo;
                        }));
                        var coupleAnchors = new Set(initialSeats.filter(function (seat) {
                            return seat.seatType === 'COUPLE';
                        }).map(function (seat) {
                            return seat.rowLabel + ':' + seat.colNo;
                        }));
                        for (var rowIndex = 0; rowIndex < nextRows; rowIndex++) {
                            var rowLabel = String.fromCharCode(65 + rowIndex);
                            for (var colNo = 1; colNo <= nextCols; colNo++) {
                                var position = rowLabel + ':' + colNo;
                                var occupiedByCouple = coupleAnchors.has(rowLabel + ':' + (colNo - 1));
                                var addedByResize = rowIndex >= currentRows || colNo > currentCols;
                                if (addedByResize && !positions.has(position)
                                        && !archivedPositions.has(position) && !occupiedByCouple) {
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
                    renderSeatLayoutPicker(seatHost, hidden, currentRows, currentCols,
                        initialSeats, archivedPositions);
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
                    var archivedActions = document.createElement('div');
                    archivedActions.className = 'seat-archived-actions';
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
                                notifyError(new Error('Không đọc được sơ đồ ghế hiện tại.'));
                                return;
                            }
                            if (!desiredSeats.some(function (item) {
                                return item.rowLabel === seat.rowLabel
                                    && Number(item.colNo) === Number(seat.colNo);
                            })) {
                                desiredSeats.push({
                                    rowLabel: seat.rowLabel,
                                    colNo: Number(seat.colNo),
                                    seatType: seat.seatType
                                });
                                hidden.value = JSON.stringify(desiredSeats);
                                initialSeats = desiredSeats;
                                redrawSeatPicker();
                            }
                        });
                        archivedActions.appendChild(button);
                    });
                    archivedSeatSection.appendChild(archivedActions);
                    section.appendChild(archivedSeatSection);
                    hidden.addEventListener('change', updateArchivedSeatButtons);
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
                var initialBranchId = params.get('branchId')
                    || (roleMeta === 'ADMIN' ? '' : (branches[0] && branches[0].id));
                // selectedBranchId là closure thay đổi theo filter — addUrl/actions dùng
                // giá trị này thay vì DOM querySelector (không có scope tới toolbar).
                var selectedBranchId = initialBranchId;
                list.render({
                    title: 'Phòng chiếu & sơ đồ ghế',
                    subtitle: 'Quản lý phòng chiếu và sơ đồ ghế cho từng chi nhánh.',
                    addUrl: function () {
                        return ctx + '/console?module=screen&action=create'
                            + (selectedBranchId
                                ? '&branchId=' + encodeURIComponent(selectedBranchId)
                                : '');
                    },
                    addLabel: 'Tạo phòng chiếu',
                    searchPlaceholder: 'Tìm theo mã hoặc tên phòng…',
                    pageSize: 20,
                    filters: [{
                        name: 'branchId', label: 'Chi nhánh',
                        initialValue: initialBranchId ? String(initialBranchId) : '',
                        options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; })
                    }],
                    fetcher: function (qs) {
                        var branchParam = qs.get('branchId');
                        selectedBranchId = branchParam || initialBranchId;
                        var url = branchParam
                            ? '/screen?branchId=' + encodeURIComponent(branchParam)
                            : '/screen';
                        return hub.api(url).then(function (result) {
                            var branchNames = new Map((branches || []).map(function (branch) {
                                return [String(branch.id), branch.name];
                            }));
                            var screens = Array.isArray(result) ? result : [];
                            screens.forEach(function (screen) {
                                screen.branchName = branchNames.get(String(screen.branchId)) || '—';
                            });
                            // Backend /screen chưa hỗ trợ q; lọc client-side theo mã/tên.
                            var q = (qs.get('q') || '').trim().toLowerCase();
                            if (q) {
                                screens = screens.filter(function (s) {
                                    return (s.code || '').toLowerCase().indexOf(q) !== -1
                                        || (s.name || '').toLowerCase().indexOf(q) !== -1;
                                });
                            }
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
                        } else if (row.status === 'INACTIVE') {
                            arr.push({ label: 'Mở lại', class: 'primary', onClick: function () {
                                confirmAction('Mở lại phòng chiếu này?', function () {
                                    hub.api('/screen/' + row.id, { method: 'PUT', body: { action: 'restore' } })
                                        .then(function () {
                                            hub.notify('Đã mở lại phòng chiếu.', 'ok');
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
    //  SHOWTIME (Admin + Manager)
    // ================================================================
    router.register('showtime', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            Promise.all([
                hub.api(branchListApiPath(roleMeta)).catch(function () { return []; }),
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
                        { label: 'Ghế đã đặt', render: function (showtime) {
                            var button = hub.el('button', {
                                class: 'ws-btn ws-btn-sm secondary',
                                type: 'button',
                                'aria-label': 'Xem suất chiếu và sơ đồ ghế đã đặt của '
                                    + (showtime.movieTitle || 'suất chiếu')
                            }, ['Xem ghế đã đặt']);
                            button.addEventListener('click', function () {
                                openShowtimeBookings(showtime);
                            });
                            return button;
                        } },
                        { label: 'Chi nhánh', key: 'branchName' },
                        { label: 'Phòng', key: 'screenName' },
                        { label: 'Bắt đầu', render: function (s) { return s.startTime ? hub.fmtDateTime(s.startTime) : '—'; } },
                        { label: 'Kết thúc', render: function (s) { return s.endTime ? hub.fmtDateTime(s.endTime) : '—'; } },
                        { label: 'Trạng thái', render: function (s) { return statusBadge(s.status); } }
                    ],
                    actions: function (row) {
                        var actions = [];
                        if (row.status === 'CANCELLED') {
                            actions.push({
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
                            });
                            return actions;
                        }
                        if (row.status !== 'OPEN') return actions;
                        actions.push(
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
                        );
                        return actions;
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            var params = new URLSearchParams(window.location.search);
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            var requestedBranchId = params.get('branchId') || '';
            var requestedMovieId = params.get('movieId') || '';
            var requestedReleaseDate = params.get('releaseDate') || '';
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
                hub.api(branchListApiPath(roleMeta)).catch(function () { return []; }),
                requestedMovieId ? hub.api('/movie/' + encodeURIComponent(requestedMovieId))
                    : hub.api('/movie')
            ]).then(function (results) {
                var branches = (results[0] || []).filter(function (branch) {
                    return branch.status === 'ACTIVE';
                });
                var movies = requestedMovieId
                    ? (results[1] ? [results[1]] : [])
                    : (results[1] || []);
                var initialBranch = branches.find(function (branch) {
                    return String(branch.id) === String(requestedBranchId);
                }) || branches[0];
                var initialBranchId = initialBranch ? String(initialBranch.id) : '';
                var screensPromise = initialBranchId
                    ? hub.api('/screen?branchId=' + encodeURIComponent(initialBranchId))
                        .then(function (items) {
                            return (Array.isArray(items) ? items : []).filter(function (screen) {
                                return screen.status === 'ACTIVE';
                            });
                        })
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
                var movieReleaseDate = requestedMovie
                    ? (requestedMovie.releaseDate || requestedReleaseDate)
                    : '';
                var showDateMin = movieReleaseDate && movieReleaseDate > today
                    ? movieReleaseDate
                    : today;
                var initialShowDate = '';
                if (requestedMovie && movieReleaseDate && requestedMovie.endDate) {
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
                                  if (requestedMovie && (value < movieReleaseDate || value > requestedMovie.endDate)) {
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
                                .then(function (list) {
                                    populateScreens((Array.isArray(list) ? list : []).filter(function (screen) {
                                        return screen.status === 'ACTIVE';
                                    }));
                                })
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
                        return hub.api('/showtime', { method: 'POST', body: data })
                            .then(function () { router.go('showtime'); });
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
    //  INVENTORY (Admin + Manager)
    // ================================================================
    router.register('inventory', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            hub.api(branchListApiPath(roleMeta)).then(function (branches) {
                var params = new URLSearchParams(window.location.search);
                // Manager chỉ quản lý đúng chi nhánh của mình, controller ép scope
                // -> dùng branch[0] (hoặc branchId từ URL) và KHÔNG hiển thị filter.
                var initialBranchId = params.get('branchId') || (branches[0] && branches[0].id);
                // selectedBranchId thay đổi theo dropdown filter — closure dùng cho
                // href "Nhập/Điều chỉnh" trong actions (tránh reference toolbar không có scope).
                var selectedBranchId = initialBranchId;
                var showBranchFilter = roleMeta === 'ADMIN';
                var filters = [];
                if (showBranchFilter) {
                    filters.push({
                        name: 'branchId', label: 'Chi nhánh',
                        initialValue: initialBranchId ? String(initialBranchId) : '',
                        options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; })
                    });
                }
                list.render({
                    title: 'Kho hàng',
                    subtitle: 'Theo dõi và điều chỉnh tồn kho tại từng chi nhánh.',
                    addUrl: function () {
                        return ctx + '/console?module=inventory&action=create&branchId=' + (selectedBranchId || '');
                    },
                    addLabel: 'Nhập kho',
                    searchPlaceholder: 'Tìm theo tên sản phẩm…',
                    pageSize: 50,
                    filters: filters,
                    // InventoryController chỉ nhận branchId; lọc chi nhánh trên server
                    // rồi lọc theo tên sản phẩm client-side.
                    fetcher: function (qs) {
                        var branchParam = qs.get('branchId');
                        selectedBranchId = branchParam || initialBranchId;
                        var url = '/inventory' + (branchParam ? '?branchId=' + encodeURIComponent(branchParam) : '');
                        return hub.api(url).then(function (list) {
                            var items = Array.isArray(list) ? list : [];
                            var q = (qs.get('q') || '').trim().toLowerCase();
                            if (q) {
                                items = items.filter(function (s) {
                                    return (s.name || '').toLowerCase().indexOf(q) !== -1;
                                });
                            }
                            return { items: items, total: items.length };
                        });
                    },
                    emptyTitle: 'Chưa có tồn kho',
                    emptyMessage: 'Chi nhánh chưa có sản phẩm nào trong kho.',
                    columns: [
                        { label: 'Sản phẩm', key: 'name' },
                        { label: 'Loại', key: 'type' },
                        { label: 'Số lượng', render: function (s) { return (s.qty || 0) + ' ' + (s.unit || ''); } },
                        { label: 'Giá bán', render: function (s) { return hub.fmtMoney(s.price); } }
                    ],
                    actions: function (row) {
                        return [
                            { label: 'Nhập', class: 'secondary', href: ctx + '/console?module=inventory&action=create&branchId=' + (selectedBranchId || '') + '&productId=' + row.productId },
                            { label: 'Điều chỉnh', class: 'danger', href: ctx + '/console?module=inventory&action=edit&branchId=' + (selectedBranchId || '') + '&productId=' + row.productId }
                        ];
                    }
                });
            }).catch(notifyError);
        },
        create: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            Promise.all([
                hub.api(branchListApiPath(roleMeta)).catch(function () { return []; }),
                hub.api('/concession/products').catch(function () { return []; })
            ]).then(function (results) {
                var branches = results[0] || [];
                var products = results[1] || [];
                var params = new URLSearchParams(window.location.search);
                var preselectedBranch = params.get('branchId');
                var preselectedProduct = params.get('productId');
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Kho hàng', href: ctx + '/console?module=inventory' }, 'Nhập kho'],
                    title: 'Nhập kho',
                    subtitle: 'Thêm số lượng tồn cho sản phẩm tại chi nhánh.',
                    submitLabel: 'Nhập kho',
                    sections: [{
                        title: 'Thông tin nhập kho',
                        fields: [
                            { name: 'branchId', label: 'Chi nhánh', type: 'select', required: true,
                              value: preselectedBranch,
                              options: (branches || []).map(function (b) { return { value: String(b.id), label: b.name }; }) },
                            { name: 'productId', label: 'Sản phẩm', type: 'select', required: true,
                              value: preselectedProduct,
                              options: (products || []).map(function (p) { return { value: String(p.id), label: p.name }; }) },
                            { name: 'quantity', label: 'Số lượng nhập', type: 'number', required: true, min: 1 },
                            { name: 'reason', label: 'Lý do / Ghi chú', required: true, value: 'Nhập kho',
                              hint: 'Bắt buộc để theo dõi lịch sử tồn kho.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/inventory', { method: 'POST', body: data })
                            .then(function () { router.go('inventory'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('inventory'); }
                });
            }).catch(notifyError);
        },
        edit: function (id) {
            hub.api('/concession/products').then(function (products) {
                var params = new URLSearchParams(window.location.search);
                var branchId = params.get('branchId');
                var productId = params.get('productId') || id;
                var product = (products || []).find(function (p) { return String(p.id) === String(productId); });
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Kho hàng', href: ctx + '/console?module=inventory' }, 'Điều chỉnh'],
                    title: 'Điều chỉnh tồn kho',
                    subtitle: product ? product.name : 'Điều chỉnh chênh lệch tồn kho',
                    submitLabel: 'Lưu điều chỉnh',
                    sections: [{
                        title: 'Thông tin điều chỉnh',
                        description: 'Số dương = thêm, số âm = giảm. Lý do bắt buộc.',
                        fields: [
                            { name: 'branchId', label: 'Chi nhánh', type: 'hidden', required: true, value: branchId },
                            { name: 'productId', label: 'Sản phẩm', type: 'hidden', required: true, value: productId },
                            { name: 'quantity', label: 'Chênh lệch (+ hoặc -)', type: 'number', required: true, step: 1 },
                            { name: 'reason', label: 'Lý do', required: true, value: 'Kiểm kê',
                              hint: 'Tối thiểu 5 ký tự để audit trail.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        data.action = 'adjust';
                        hub.api('/inventory', { method: 'POST', body: data })
                            .then(function () { router.go('inventory'); })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('inventory'); }
                });
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  SHIFT (Staff + Manager)
    // ================================================================

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

    router.register('product', {
        list: function () {
            list.render({
                title: 'Sản phẩm F&B',
                subtitle: 'Danh mục bắp nước và combo đang được quản lý.',
                addUrl: ctx + '/console?module=product&action=create',
                addLabel: 'Tạo sản phẩm',
                searchPlaceholder: 'Tìm theo tên sản phẩm…',
                pageSize: 20,
                filters: [{
                    name: 'type', label: 'Loại',
                    options: [
                        { value: 'POPCORN', label: 'Bắp rang' },
                        { value: 'DRINK', label: 'Nước uống' },
                        { value: 'COMBO', label: 'Combo' },
                        { value: 'OTHER', label: 'Khác' }
                    ]
                }],
                sortable: true,
                sortOptions: [
                    { value: 'name', label: 'Tên A→Z' },
                    { value: 'price', label: 'Giá' }
                ],
                // /concession/products trả về toàn bộ; lọc + search client-side.
                fetcher: function (qs) {
                    return hub.api('/concession/products').then(function (list) {
                        var items = Array.isArray(list) ? list : [];
                        var q = (qs.get('q') || '').trim().toLowerCase();
                        var type = qs.get('type') || '';
                        if (type) {
                            items = items.filter(function (p) { return p.type === type; });
                        }
                        if (q) {
                            items = items.filter(function (p) {
                                return (p.name || '').toLowerCase().indexOf(q) !== -1;
                            });
                        }
                        return { items: items, total: items.length };
                    });
                },
                emptyTitle: 'Chưa có sản phẩm',
                emptyMessage: 'Danh mục bắp nước đang trống.',
                columns: [
                    { label: 'Tên sản phẩm', key: 'name' },
                    { label: 'Loại', key: 'type' },
                    { label: 'Đơn vị', key: 'unit' },
                    { label: 'Giá bán', render: function (r) { return hub.fmtMoney(r.price); } },
                    { label: 'Trạng thái', render: function (r) { return statusBadge(r.status); } }
                ],
                actions: function (row) {
                    var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=product&action=edit&id=' + row.id }];
                    if (row.status === 'ACTIVE') {
                        arr.push({ label: 'Ngưng', class: 'danger', onClick: function () {
                            confirmAction('Ngưng kinh doanh sản phẩm này?', function () {
                                hub.api('/concession/products/' + row.id, { method: 'PUT', body: { action: 'deactivate' } })
                                    .then(function () {
                                        hub.notify('Đã ngưng sản phẩm.', 'ok');
                                        router.go('product');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    } else {
                        // INACTIVE hoac trang thai khac -> cho phep mo lai.
                        arr.push({ label: 'Mở lại', class: 'primary', onClick: function () {
                            confirmAction('Mở lại kinh doanh sản phẩm này?', function () {
                                hub.api('/concession/products/' + row.id, { method: 'PUT', body: { action: 'activate' } })
                                    .then(function () {
                                        hub.notify('Đã mở lại sản phẩm.', 'ok');
                                        router.go('product');
                                    })
                                    .catch(notifyError);
                            });
                        }});
                    }
                    return arr;
                }
            });
        },
        create: function () { renderProductForm(null); },
        edit: function (id) {
            hub.api('/concession/products').then(function (list) {
                var product = (list || []).find(function (p) { return String(p.id) === String(id); });
                if (!product) throw new Error('Không tìm thấy sản phẩm');
                renderProductForm(product);
            }).catch(notifyError);
        }
    });

    // ================================================================
    //  BOOKING / TICKET (Customer history + Admin/Manager overview)
    // ================================================================
    router.register('booking', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta === 'ADMIN' || roleMeta === 'BRANCH_MANAGER' || roleMeta === 'BRANCH_STAFF') {
                list.render({
                    title: roleMeta === 'BRANCH_STAFF' ? 'Lịch sử vé tại chi nhánh' : 'Đặt vé & vé bán',
                    subtitle: roleMeta === 'ADMIN'
                        ? 'Danh sách vé và ghế đã đặt trong hệ thống.'
                        : 'Danh sách vé và ghế đã đặt tại chi nhánh được phân công.',
                    pageSize: 20,
                    searchPlaceholder: 'Tìm theo mã vé hoặc tên khách…',
                    filters: [{
                        name: 'status', label: 'Trạng thái', options: [
                            { value: 'CONFIRMED', label: 'Đã xác nhận' },
                            { value: 'USED', label: 'Đã sử dụng' },
                            { value: 'CANCELLED', label: 'Đã hủy' }
                        ]
                    }],
                    fetcher: function (qs) {
                        return hub.api('/booking/list?' + qs.toString()).then(function (data) {
                            return {
                                items: (data && data.items) || [],
                                total: (data && typeof data.total === 'number') ? data.total : 0
                            };
                        });
                    },
                    emptyTitle: 'Chưa có vé nào',
                    emptyMessage: 'Hệ thống chưa có vé bán nào.',
                    columns: [
                        { label: 'Mã vé', key: 'ticketCode' },
                        { label: 'Khách hàng', key: 'customerName' },
                        { label: 'Phim', key: 'movie' },
                        { label: 'Ghế đã đặt', render: function (t) {
                            return Array.isArray(t.seats) && t.seats.length
                                ? t.seats.map(function (seat) { return seat.seatCode; }).join(', ')
                                : '—';
                        } },
                        { label: 'Giờ chiếu', render: function (t) { return t.showtimeStart ? hub.fmtDateTime(t.showtimeStart) : '—'; } },
                        { label: 'Tổng tiền', render: function (t) { return hub.fmtMoney(t.totalAmount); } },
                        { label: 'Trạng thái', render: function (t) { return statusBadge(t.status); } }
                    ]
                });
                return;
            }
            // Customer view = own tickets (with cancel button)
            var viewRoot = document.getElementById('viewRoot');
            viewRoot.replaceChildren(hub.el('div', { class: 'ws-page' }, [
                hub.el('div', { class: 'ws-page-header' }, [
                    hub.el('h1', { class: 'ws-page-title' }, ['Vé của tôi']),
                    hub.el('p', { class: 'ws-page-subtitle' }, ['Lịch sử vé đã đặt.'])
                ])
            ]));
            if (new URLSearchParams(window.location.search).get('payment') === 'review') {
                viewRoot.querySelector('.ws-page').appendChild(hub.el('div', {
                    class: 'flash flash-warn'
                }, [
                    'Cổng thanh toán báo đã thu tiền nhưng thời gian giữ ghế đã hết. Giao dịch đang chờ đối soát thủ công; vui lòng không thanh toán lại và liên hệ hỗ trợ.'
                ]));
            }
            Promise.all([
                hub.api('/booking/mine'),
                hub.api('/api/refunds/mine')
            ]).then(function (results) {
                var list = results[0];
                var refundData = results[1];
                if (!refundData || !Array.isArray(refundData.items)) {
                    throw new Error('Không tải được trạng thái yêu cầu hoàn tiền. Vui lòng thử lại.');
                }
                var pendingRefunds = Object.create(null);
                refundData.items.forEach(function (refund) {
                    if (refund.status === 'PENDING') {
                        pendingRefunds[String(refund.ticketId)] = refund;
                    }
                });
                // Tổng giao dịch = tổng tiền các vé đã thanh toán (CONFIRMED/USED);
                // tổng hoàn = tổng refund_amount của vé CANCELLED. Hiển thị phía trên
                // bảng để user nắm nhanh chi tiêu và tiền được hoàn.
                var txTotal = list.reduce(function (sum, t) {
                    if (t.status === 'CONFIRMED' || t.status === 'USED') {
                        return sum + (Number(t.totalAmount) || 0);
                    }
                    return sum;
                }, 0);
                var refundTotal = list.reduce(function (sum, t) {
                    if (t.status === 'CANCELLED') {
                        return sum + (Number(t.refundAmount) || 0);
                    }
                    return sum;
                }, 0);
                var paidCount = list.filter(function (t) {
                    return t.status === 'CONFIRMED' || t.status === 'USED';
                }).length;
                var summaryBar = hub.el('div', { class: 'ws-card my-tickets-summary' }, [
                    hub.el('div', { class: 'my-tickets-summary-item' }, [
                        hub.el('small', { class: 'muted' }, ['Tổng tiền giao dịch']),
                        hub.el('strong', {}, [hub.fmtMoney(txTotal)])
                    ]),
                    hub.el('div', { class: 'my-tickets-summary-item' }, [
                        hub.el('small', { class: 'muted' }, ['Tổng tiền đã hoàn']),
                        hub.el('strong', { class: 'text-ok' }, [hub.fmtMoney(refundTotal)])
                    ]),
                    hub.el('div', { class: 'my-tickets-summary-item' }, [
                        hub.el('small', { class: 'muted' }, ['Số vé đã thanh toán']),
                        hub.el('strong', {}, [String(paidCount)])
                    ])
                ]);
                viewRoot.querySelector('.ws-page').appendChild(summaryBar);
                if (!list || !list.length) {
                    viewRoot.querySelector('.ws-page').appendChild(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Chưa có vé']),
                        hub.el('p', {}, ['Đặt vé từ mục Lịch chiếu.']),
                        hub.el('a', { href: ctx + '/console?module=discovery', class: 'ws-btn primary' }, ['Xem lịch chiếu'])
                    ]));
                    return;
                }
                var table = hub.el('table', { class: 'ws-table' });
                table.innerHTML = '<thead><tr><th>Mã vé</th><th>Suất chiếu</th><th>Ghế</th><th>Tổng tiền</th><th>Trạng thái</th><th></th></tr></thead>';
                var tbody = hub.el('tbody');
                list.forEach(function (t) {
                    var tr = hub.el('tr');
                    var showtime = [t.movieTitle, t.branchName, t.screenName, t.startTime ? hub.fmtDateTime(t.startTime) : null].filter(Boolean).join(' · ');
                    var seats = (t.seats || []).map(function (s) { return s.seatCode || (s.rowLabel + s.colNo); }).join(', ');
                    tr.appendChild(hub.el('td', {}, [t.ticketCode || '—']));
                    tr.appendChild(hub.el('td', {}, [showtime || '—']));
                    tr.appendChild(hub.el('td', {}, [seats || '—']));
                    tr.appendChild(hub.el('td', {}, [hub.fmtMoney(t.totalAmount)]));
                    var pendingRefund = pendingRefunds[String(t.id)];
                    tr.appendChild(hub.el('td', {}, [pendingRefund
                        ? hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-orange' }, ['Chờ hoàn tiền'])
                        : statusBadge(t.status)]));
                    var actions = hub.el('td', { class: 'row-actions' });
                    if (pendingRefund) {
                        actions.appendChild(hub.el('span', { class: 'sub muted' }, ['Đang chờ xác nhận']));
                    } else if (t.status === 'CONFIRMED' && !t.showtimePassed) {
                        var refundBtn = hub.el('button', { class: 'ws-btn ws-btn-sm primary', type: 'button' }, ['Yêu cầu hoàn tiền']);
                        refundBtn.addEventListener('click', function () {
                            openRefundForm(t);
                        });
                        actions.appendChild(refundBtn);
                    } else if (t.status === 'CONFIRMED' && t.showtimePassed) {
                        actions.appendChild(hub.el('span', { class: 'sub muted' }, ['Đã qua giờ']));
                    } else if (t.status === 'CANCELLED' && t.refundAmount) {
                        actions.appendChild(hub.el('span', { class: 'sub muted' },
                            ['Đã hoàn ' + hub.fmtMoney(t.refundAmount)]));
                    } else {
                        actions.appendChild(hub.el('span', { class: 'sub muted' }, ['—']));
                    }
                    tr.appendChild(actions);
                    tbody.appendChild(tr);
                });
                table.appendChild(tbody);
                var wrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
                wrap.appendChild(table);
                viewRoot.querySelector('.ws-page').appendChild(wrap);
            }).catch(notifyError);
        }
    });

    /**
     * Mở modal yêu cầu hoàn tiền cho vé CONFIRMED.
     * Form: thông tin cá nhân (snapshot từ account), thông tin NH, lý do.
     */
    function openRefundForm(ticket) {
        var pendingProfile = hub.api('/api/profile');
        var requestList = hub.api('/api/refunds/mine');

        Promise.all([pendingProfile, requestList]).then(function (results) {
            if (!results[0] || !results[0].user || !results[0].bankAccount) {
                throw new Error('Dữ liệu hồ sơ ngân hàng không hợp lệ. Vui lòng tải lại trang.');
            }
            if (!results[1] || !Array.isArray(results[1].items)) {
                throw new Error('Không tải được danh sách yêu cầu hoàn tiền. Vui lòng thử lại.');
            }
            var existing = results[1].items;
            var pending = existing.find(function (r) {
                return r.ticketId === ticket.id && r.status === 'PENDING';
            });
            if (pending) {
                if (typeof openRefundStatusModal === 'function') {
                    openRefundStatusModal(pending);
                } else {
                    hub.notify('Yêu cầu hoàn tiền của bạn đang chờ duyệt.', 'warn');
                }
                return;
            }
            var account = results[0];
            renderRefundFormModal(ticket, account.user || {}, account.bankAccount || {});
        }).catch(notifyError);
    }

    function renderRefundFormModal(ticket, profile, bankAccount) {
        var backdrop = hub.el('div', { class: 'modal-backdrop refund-modal-backdrop' });
        var modal = hub.el('div', {
            class: 'modal-card refund-modal',
            role: 'dialog',
            'aria-modal': 'true',
            'aria-labelledby': 'refund-dialog-title'
        });
        var body = hub.el('div', { class: 'modal-body' });
        var form = hub.el('form', { class: 'ws-form refund-form' });
        var closeModal = function () {
            if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
        };
        var modalHeader = hub.el('div', { class: 'refund-modal-header' }, [
            hub.el('div', {}, [
                hub.el('h2', { id: 'refund-dialog-title' }, ['Yêu cầu hoàn tiền']),
                hub.el('p', { class: 'muted' }, ['Kiểm tra thông tin trước khi gửi yêu cầu.'])
            ])
        ]);
        var closeButton = hub.el('button', {
            type: 'button', class: 'refund-modal-close', 'aria-label': 'Đóng'
        }, ['×']);
        closeButton.addEventListener('click', closeModal);
        modalHeader.appendChild(closeButton);
        body.appendChild(modalHeader);
        backdrop.addEventListener('click', function (event) {
            if (event.target === backdrop) closeModal();
        });

        function addField(def) {
            var wrap = hub.el('div', { class: 'ws-field' });
            wrap.appendChild(hub.el('label', { for: def.id }, [def.label]));
            var input;
            if (def.type === 'textarea') {
                input = hub.el('textarea', {
                    id: def.id, name: def.id, rows: '3',
                    class: 'ws-input', maxlength: def.maxLength || 500
                });
                input.placeholder = def.placeholder || '';
            } else {
                input = hub.el('input', {
                    id: def.id, name: def.id, type: def.type || 'text',
                    class: 'ws-input', maxlength: def.maxLength || 120
                });
                input.placeholder = def.placeholder || '';
            }
            if (def.required) input.required = true;
            if (def.pattern) input.pattern = def.pattern;
            if (def.value) input.value = def.value;
            wrap.appendChild(input);
            if (def.help) wrap.appendChild(hub.el('small', { class: 'ws-help' }, [def.help]));
            form.appendChild(wrap);
            return input;
        }

        body.appendChild(hub.el('p', { class: 'refund-summary' }, [
            'Vé ', hub.el('strong', {}, [ticket.ticketCode || ('#' + ticket.id)]),
            ' · Tổng tiền ', hub.el('strong', {}, [hub.fmtMoney(ticket.totalAmount)]), '.'
        ]));

        form.appendChild(hub.el('h3', { class: 'refund-section' }, ['Thông tin cá nhân']));
        var fullName = addField({
            id: 'refund-fullname', label: 'Họ và tên', required: true,
            maxLength: 100, value: profile.fullName || ''
        });
        var phone = addField({
            id: 'refund-phone', label: 'Số điện thoại', type: 'tel', required: true,
            pattern: '0[0-9]{9}', value: profile.phone || '',
            help: 'Định dạng 0xxxxxxxxx (10 chữ số).'
        });
        var email = addField({
            id: 'refund-email', label: 'Email', type: 'email', required: true,
            maxLength: 120, value: profile.email || ''
        });

        form.appendChild(hub.el('h3', { class: 'refund-section' }, ['Thông tin ngân hàng nhận hoàn']));
        form.appendChild(hub.el('p', { class: 'refund-bank-intro' }, [
            'Chọn tài khoản đã lưu trong hồ sơ hoặc nhập tài khoản khác cho yêu cầu này.'
        ]));
        var hasSavedBankAccount = !!(bankAccount.bankName && bankAccount.bankAccountName
            && bankAccount.bankAccountNumber);
        var bankOptions = hub.el('div', {
            class: 'refund-bank-options',
            role: 'radiogroup',
            'aria-label': 'Chọn tài khoản nhận hoàn tiền'
        });
        var savedRadio = null;
        if (hasSavedBankAccount) {
            savedRadio = hub.el('input', {
                type: 'radio', name: 'refund-bank-source', value: 'saved',
                class: 'refund-bank-radio'
            });
            savedRadio.checked = true;
            var accountNumber = String(bankAccount.bankAccountNumber);
            var maskedAccountNumber = accountNumber.length > 4
                ? '•••• ' + accountNumber.slice(-4) : accountNumber;
            bankOptions.appendChild(hub.el('label', { class: 'refund-bank-option' }, [
                savedRadio,
                hub.el('span', { class: 'refund-bank-option-content' }, [
                    hub.el('strong', {}, ['Tài khoản trong hồ sơ']),
                    hub.el('small', {}, [
                        bankAccount.bankName + ' · ' + bankAccount.bankAccountName
                    ]),
                    hub.el('small', { class: 'refund-bank-account-number' }, [
                        maskedAccountNumber
                    ])
                ])
            ]));
        } else {
            bankOptions.appendChild(hub.el('p', { class: 'refund-bank-empty' }, [
                'Bạn chưa lưu tài khoản ngân hàng trong hồ sơ.'
            ]));
        }
        var manualRadio = hub.el('input', {
            type: 'radio', name: 'refund-bank-source', value: 'manual',
            class: 'refund-bank-radio'
        });
        manualRadio.checked = !hasSavedBankAccount;
        bankOptions.appendChild(hub.el('label', { class: 'refund-bank-option' }, [
            manualRadio,
            hub.el('span', { class: 'refund-bank-option-content' }, [
                hub.el('strong', {}, ['Nhập tài khoản khác']),
                hub.el('small', {}, [
                    'Chỉ áp dụng cho yêu cầu hoàn tiền này, không thay đổi hồ sơ.'
                ])
            ])
        ]));
        form.appendChild(bankOptions);
        form.appendChild(hub.el('a', {
            class: 'refund-bank-profile-link',
            href: ctx + '/console?module=profile',
            target: '_blank',
            rel: 'noopener',
            title: 'Mở hồ sơ ở tab mới. Sau khi lưu, hãy mở lại yêu cầu hoàn tiền để tải thông tin mới.'
        }, [hasSavedBankAccount
            ? 'Cập nhật tài khoản trong hồ sơ ↗'
            : 'Thêm tài khoản vào hồ sơ ↗']));

        var manualBankFields = hub.el('div', { class: 'refund-bank-manual' });
        var bankName = addField({
            id: 'refund-bank-name', label: 'Tên ngân hàng', required: true,
            maxLength: 120, placeholder: 'VD: Vietcombank, Techcombank…'
        });
        var bankAccountName = addField({
            id: 'refund-bank-account-name', label: 'Chủ tài khoản', required: true,
            maxLength: 120, placeholder: 'Tên chủ tài khoản (đúng trên sổ)'
        });
        var bankAccountNumber = addField({
            id: 'refund-bank-account-number', label: 'Số tài khoản', required: true,
            maxLength: 40, pattern: '[0-9]{6,40}',
            help: 'Chỉ chữ số, 6–40 ký tự.'
        });
        [bankName, bankAccountName, bankAccountNumber].forEach(function (input) {
            manualBankFields.appendChild(input.parentNode);
        });
        form.appendChild(manualBankFields);
        function updateBankSource() {
            var useSavedAccount = !!(savedRadio && savedRadio.checked);
            manualBankFields.hidden = useSavedAccount;
            [bankName, bankAccountName, bankAccountNumber].forEach(function (input) {
                input.disabled = useSavedAccount;
                input.required = !useSavedAccount;
            });
        }
        if (savedRadio) savedRadio.addEventListener('change', updateBankSource);
        manualRadio.addEventListener('change', updateBankSource);
        updateBankSource();

        form.appendChild(hub.el('h3', { class: 'refund-section' }, ['Thông tin vé cần hoàn']));
        form.appendChild(hub.el('div', { class: 'ws-readonly' }, [
            'Mã vé: ', hub.el('strong', {}, [ticket.ticketCode || '—'])
        ]));
        form.appendChild(hub.el('div', { class: 'ws-readonly' }, [
            'Số tiền dự kiến hoàn: ',
            hub.el('strong', {}, [hub.fmtMoney(ticket.totalAmount || 0)]),
            ' (theo chính sách bậc thang của rạp).'
        ]));
        addField({
            id: 'refund-reason', label: 'Lý do (tuỳ chọn)', type: 'textarea',
            maxLength: 500, placeholder: 'Mô tả ngắn về lý do hoàn tiền…'
        });

        var errBox = hub.el('div', { class: 'ws-errors', hidden: true });
        body.appendChild(errBox);

        var actions = hub.el('div', { class: 'modal-actions' });
        var cancel = hub.el('button', { type: 'button', class: 'ws-btn secondary' }, ['Đóng']);
        cancel.addEventListener('click', closeModal);
        var submit = hub.el('button', { type: 'submit', class: 'ws-btn primary' },
            ['Gửi yêu cầu hoàn tiền']);
        actions.appendChild(cancel);
        actions.appendChild(submit);
        form.appendChild(actions);
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            errBox.hidden = true;
            errBox.innerHTML = '';
            var payload = {
                ticketId: ticket.id,
                customerFullName: fullName.value,
                phone: phone.value,
                email: email.value,
                bankName: savedRadio && savedRadio.checked ? bankAccount.bankName : bankName.value,
                bankAccountName: savedRadio && savedRadio.checked
                    ? bankAccount.bankAccountName : bankAccountName.value,
                bankAccountNumber: savedRadio && savedRadio.checked
                    ? bankAccount.bankAccountNumber : bankAccountNumber.value,
                reason: document.getElementById('refund-reason').value
            };
            submit.disabled = true;
            hub.api('/api/refunds', { method: 'POST', body: payload })
                .then(function (r) {
                    hub.notify('Yêu cầu hoàn tiền đã gửi. Vui lòng đợi quản lý duyệt.', 'ok');
                    closeModal();
                    if (typeof router !== 'undefined' && router.go) router.go('booking');
                })
                .catch(function (e) {
                    submit.disabled = false;
                    var msg = (e && e.payload && e.payload.message) || (e && e.message) || 'Không thể gửi yêu cầu.';
                    errBox.hidden = false;
                    errBox.textContent = msg;
                });
        });
        body.appendChild(form);
        modal.appendChild(body);
        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);
    }

    // ================================================================
    //  REFUND CONSOLE (Manager duyệt yêu cầu hoàn tiền)
    // ================================================================
    router.register('refunds', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta !== 'BRANCH_MANAGER') {
                viewRoot.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('p', {}, ['Chỉ Quản lý chi nhánh được quản lý yêu cầu hoàn tiền.'])
                ]));
                return;
            }
            var initialStatus = new URLSearchParams(window.location.search).get('status') || 'PENDING';
            viewRoot.replaceChildren(buildRefundListView(initialStatus));
            attachRefundListHandlers(viewRoot, initialStatus);
        }
    });

    function buildRefundListView(statusFilter) {
        var page = hub.el('div', { class: 'ws-page' });
        page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
            hub.el('h1', { class: 'ws-page-title' }, ['Yêu cầu hoàn tiền']),
            hub.el('p', { class: 'ws-page-subtitle' },
                ['Duyệt các yêu cầu hoàn tiền từ khách hàng — hệ thống sẽ gửi email thông báo tới khách khi duyệt.'])
        ]));
        var tabs = hub.el('div', { class: 'ws-tabs', role: 'tablist' });
        ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'].forEach(function (status) {
            var label = ({PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt',
                REJECTED: 'Đã từ chối', CANCELLED: 'Đã hủy'})[status];
            var cls = 'ws-tab' + (status === statusFilter ? ' is-active' : '');
            tabs.appendChild(hub.el('button', {
                type: 'button', class: cls,
                'data-status': status,
                role: 'tab',
                'aria-selected': String(status === statusFilter)
            }, [label + ' (' + status.toLowerCase() + ')']));
        });
        page.appendChild(tabs);
        var container = hub.el('div', { class: 'ws-card refund-list', 'data-status': statusFilter });
        container.appendChild(hub.el('div', { class: 'ws-empty' }, [
            hub.el('p', { class: 'muted' }, ['Đang tải…'])
        ]));
        page.appendChild(container);
        return page;
    }

    function attachRefundListHandlers(viewRoot, statusFilter) {
        // Tabs
        viewRoot.querySelectorAll('.ws-tab').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var newStatus = btn.getAttribute('data-status');
                if (newStatus === statusFilter) return;
                var url = new URL(location.href);
                url.searchParams.set('status', newStatus);
                history.replaceState(null, '', url.pathname + '?' + url.searchParams.toString());
                viewRoot.replaceChildren(buildRefundListView(newStatus));
                attachRefundListHandlers(viewRoot, newStatus);
            });
        });
        loadRefundList(viewRoot, statusFilter);
    }

    function loadRefundList(viewRoot, statusFilter) {
        var container = viewRoot.querySelector('.refund-list');
        hub.api('/api/refunds/console?status=' + encodeURIComponent(statusFilter))
            .then(function (data) {
                var items = (data && data.items) || [];
                renderRefundList(container, items);
            })
            .catch(function (e) {
                container.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('p', { class: 'muted' }, ['Không thể tải: '
                        + (e.message || 'Lỗi không xác định.')])
                ]));
            });
    }

    function renderRefundList(container, items) {
        container.replaceChildren();
        if (!items.length) {
            container.appendChild(hub.el('div', { class: 'ws-empty' }, [
                hub.el('p', { class: 'muted' }, ['Không có yêu cầu nào trong mục này.'])
            ]));
            return;
        }
        var table = hub.el('table', { class: 'ws-table' });
        table.innerHTML = '<thead><tr>'
            + '<th>Mã vé</th><th>Khách</th><th>Ngân hàng</th><th>Số tiền</th>'
            + '<th>Trạng thái</th><th>Ngày tạo</th><th></th></tr></thead>';
        var tbody = hub.el('tbody');
        items.forEach(function (r) {
            var tr = hub.el('tr');
            tr.appendChild(hub.el('td', {}, [r.ticketCode || '—']));
            var name = hub.el('td', {}, [
                r.customerFullName || '—',
                hub.el('br'),
                hub.el('small', { class: 'muted' }, [r.customerPhone || ''])
            ]);
            tr.appendChild(name);
            tr.appendChild(hub.el('td', {}, [
                (r.bankName || '—') + ' · ' + (r.bankAccountNumber || '—'),
                hub.el('br'),
                hub.el('small', { class: 'muted' }, [r.bankAccountName || ''])
            ]));
            tr.appendChild(hub.el('td', {}, [hub.fmtMoney(r.ticketAmount)]));
            tr.appendChild(hub.el('td', {}, [refundStatusBadge(r.status)]));
            tr.appendChild(hub.el('td', {}, [r.createdAt ? hub.fmtDateTime(r.createdAt) : '—']));
            var actions = hub.el('td', { class: 'row-actions' });
            if (r.status === 'PENDING') {
                var approveBtn = hub.el('button', {
                    class: 'ws-btn ws-btn-sm primary', type: 'button',
                    'data-action': 'approve', 'data-id': r.id
                }, ['Duyệt']);
                var rejectBtn = hub.el('button', {
                    class: 'ws-btn ws-btn-sm danger', type: 'button',
                    'data-action': 'reject', 'data-id': r.id
                }, ['Từ chối']);
                actions.appendChild(approveBtn);
                actions.appendChild(rejectBtn);
            } else {
                actions.appendChild(hub.el('span', { class: 'sub muted' },
                    [r.decidedAt ? ('Xử lý: ' + hub.fmtDateTime(r.decidedAt)) : '—']));
            }
            tr.appendChild(actions);
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        var wrap = hub.el('div', { class: 'ws-card-inner' });
        wrap.appendChild(table);
        container.appendChild(wrap);
        container.querySelectorAll('button[data-action]').forEach(function (btn) {
            btn.addEventListener('click', function () {
                handleRefundDecision(btn.getAttribute('data-id'),
                    btn.getAttribute('data-action') === 'approve', container);
            });
        });
    }

    function refundStatusBadge(status) {
        var map = {
            PENDING: ['warn', 'Chờ duyệt'],
            APPROVED: ['ok', 'Đã duyệt'],
            REJECTED: ['err', 'Đã từ chối'],
            CANCELLED: ['muted', 'Đã hủy']
        };
        var pair = map[status] || ['muted', status];
        return hub.el('span', { class: 'badge badge-' + pair[0] }, [pair[1]]);
    }

    function handleRefundDecision(id, approve, container) {
        confirmAction((approve ? 'Duyệt' : 'Từ chối')
            + ' yêu cầu hoàn tiền #' + id + '?', function () {
            var endpoint = '/api/refunds/' + encodeURIComponent(id)
                + (approve ? '/approve' : '/reject');
            hub.api(endpoint, { method: 'POST', body: {} })
                .then(function () {
                    hub.notify((approve ? 'Đã duyệt' : 'Đã từ chối') + ' yêu cầu hoàn tiền.', 'ok');
                    loadRefundList({ querySelector: function () { return container; } }, 'PENDING');
                })
                .catch(function (e) {
                    hub.notify((e && e.message) || 'Không thể xử lý yêu cầu.', 'err');
                });
        });
    }

    // ================================================================
    //  CONCESSION-REFUNDS (F&B refund approval)
    // ================================================================
    router.register('concession-refunds', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            hub.setWorkspaceHeader('Yêu cầu hoàn tiền F&B');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Yêu cầu hoàn tiền F&B']),
                hub.el('p', { class: 'ws-page-subtitle' }, [
                    'Hoàn 100% số tiền đã thanh toán và điểm đã sử dụng; không giới hạn thời gian yêu cầu.'
                ])
            ]));
            var tableWrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:auto' });
            page.appendChild(tableWrap);
            viewRoot.replaceChildren(page);

            function load() {
                tableWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [
                    hub.el('div', { class: 'ws-spinner' })
                ]));
                hub.api('/concession/refunds/console?status=PENDING').then(function (data) {
                    var requests = (data && data.items) || [];
                    if (!requests.length) {
                        tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Không có yêu cầu đang chờ']),
                            hub.el('p', {}, ['Yêu cầu hoàn tiền F&B mới sẽ xuất hiện tại đây.'])
                        ]));
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Đơn</th><th>Ngân hàng nhận tiền</th>'
                        + '<th>Lý do</th><th>Hoàn tiền</th><th>Yêu cầu lúc</th><th></th></tr></thead>';
                    var tbody = hub.el('tbody');
                    requests.forEach(function (request) {
                        var bank = request.bankName + ' · ' + request.bankAccountName
                            + ' · ' + request.bankAccountNumber;
                        var amount = hub.fmtMoney(request.refundAmount || 0)
                            + (request.refundPoints > 0 ? ' + ' + request.refundPoints + ' điểm' : '');
                        var actions = hub.el('td');
                        var note = hub.el('textarea', {
                            class: 'ws-input',
                            rows: '2',
                            maxlength: '500',
                            placeholder: 'Ghi chú xử lý / lý do từ chối'
                        });
                        var approve = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['Duyệt hoàn']);
                        var reject = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Từ chối']);
                        approve.addEventListener('click', function () {
                            if (!window.confirm('Xác nhận chuyển ' + hub.fmtMoney(request.refundAmount)
                                    + ' về tài khoản ' + request.bankAccountNumber
                                    + ' và hoàn ' + (request.refundPoints || 0) + ' điểm?')) return;
                            approve.disabled = true;
                            hub.api('/concession/refunds/' + request.id + '/approve', {
                                method: 'POST',
                                body: { managerNote: note.value.trim() || 'Đã xử lý hoàn tiền 100% qua ngân hàng.' }
                            }).then(load).catch(function (error) {
                                approve.disabled = false;
                                notifyError(error);
                            });
                        });
                        reject.addEventListener('click', function () {
                            if (!note.value.trim()) {
                                hub.notify('Vui lòng nhập lý do từ chối.', 'err');
                                return;
                            }
                            reject.disabled = true;
                            hub.api('/concession/refunds/' + request.id + '/reject', {
                                method: 'POST',
                                body: { managerNote: note.value.trim() }
                            }).then(load).catch(function (error) {
                                reject.disabled = false;
                                notifyError(error);
                            });
                        });
                        actions.append(note, approve, reject);
                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', {}, [request.orderCode || ('#' + request.orderId)]),
                            hub.el('td', {}, [bank]),
                            hub.el('td', {}, [request.reason || '—']),
                            hub.el('td', {}, [amount]),
                            hub.el('td', {}, [request.createdAt ? hub.fmtDateTime(request.createdAt) : '—']),
                            actions
                        ]));
                    });
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);
                }).catch(function (error) {
                    tableWrap.replaceChildren(hub.el('div', { class: 'ws-flash err' }, [
                        error.message || 'Không thể tải yêu cầu hoàn tiền.'
                    ]));
                });
            }
            load();
        }
    });

    // ================================================================
    //  CONCESSION-ORDERS (F&B Staff orders)
    // ================================================================
    router.register('concession-orders', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            var branchesRequest = roleMeta === 'ADMIN'
                ? hub.api('/branch/my-location')
                : Promise.resolve([]);
            branchesRequest.then(function (branches) {
                var filters = [{
                    name: 'status', label: 'Trạng thái', options: [
                        { value: 'PENDING', label: 'Chờ thanh toán' },
                        { value: 'READY_FOR_PICKUP', label: 'Sẵn sàng nhận' },
                        { value: 'FULFILLED', label: 'Đã giao' },
                        { value: 'CANCELLED', label: 'Đã hủy' },
                        { value: 'EXPIRED_NO_SHOW', label: 'Quá hạn' }
                    ]
                }];
                if (roleMeta === 'ADMIN') {
                    filters.unshift({
                        name: 'branchId', label: 'Chi nhánh',
                        options: (branches || []).map(function (b) {
                            return { value: String(b.id), label: b.name };
                        })
                    });
                }
                list.render({
                    title: 'Đơn hàng Bắp & Nước',
                    subtitle: 'Danh sách đơn F&B tại chi nhánh.',
                    searchPlaceholder: 'Tìm mã đơn / tên / SĐT…',
                    pageSize: 20,
                    filters: filters,
                    toolbarRight: [hub.el('a', {
                        href: ctx + '/console?module=concession-pickup',
                        class: 'ws-btn secondary'
                    }, ['📷 Quét mã nhận'])],
                    fetcher: function (qs) { return hub.api('/concession/orders?' + qs.toString()); },
                    emptyTitle: 'Chưa có đơn hàng',
                    emptyMessage: 'Chi nhánh chưa có đơn F&B nào.',
                    columns: [
                        { label: 'Mã đơn', key: 'orderCode' },
                        { label: 'Chi nhánh', key: 'branchName' },
                        { label: 'Khách hàng', key: 'customerName' },
                        { label: 'Tổng tiền', render: function (o) { return hub.fmtMoney(o.totalAmount); } },
                        { label: 'Mã nhận', key: 'pickupCode' },
                        { label: 'Trạng thái', render: function (o) { return statusBadge(o.status); } },
                        { label: 'Ngày tạo', render: function (o) {
                            return o.createdAt ? hub.fmtNotificationDateTime(o.createdAt) : '—';
                        } }
                    ],
                    actions: function (row) {
                        return [{ label: 'Chi tiết', class: 'secondary', href: ctx + '/console?module=concession-orders&action=view&id=' + row.id }];
                    }
                });
            }).catch(notifyError);
        },
        view: function (id) {
            var viewRoot = document.getElementById('viewRoot');
            hub.setWorkspaceHeader('Chi tiết đơn F&B');
            var page = hub.el('div', { class: 'ws-page' });
            var card = hub.el('section', { class: 'ws-card' }, [
                hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })])
            ]);
            page.appendChild(card);
            viewRoot.replaceChildren(page);

            if (!id) {
                card.replaceChildren(hub.el('p', { class: 'ws-muted' }, ['Không xác định được đơn hàng.']));
                return;
            }

            hub.api('/concession/orders/' + encodeURIComponent(id)).then(function (order) {
                card.replaceChildren();
                card.appendChild(hub.el('div', { class: 'ws-card-header' }, [
                    hub.el('div', {}, [
                        hub.el('h2', {}, ['Đơn ' + (order.orderCode || ('#' + order.id))]),
                        hub.el('p', { class: 'ws-muted' }, [
                            (order.branchName || (order.branchId ? 'Chi nhánh #' + order.branchId : 'Chi nhánh chưa xác định')) + ' · ' +
                            (order.createdAt ? hub.fmtNotificationDateTime(order.createdAt) : '—')
                        ])
                    ]),
                    statusBadge(order.status)
                ]));

                var lines = Array.isArray(order.lines) ? order.lines : [];
                var table = hub.el('table', { class: 'ws-table' }, [
                    hub.el('thead', {}, [hub.el('tr', {}, [
                        hub.el('th', {}, ['Sản phẩm']),
                        hub.el('th', {}, ['SL']),
                        hub.el('th', {}, ['Đơn giá']),
                        hub.el('th', {}, ['Thành tiền'])
                    ])])
                ]);
                var body = hub.el('tbody');
                lines.forEach(function (line) {
                    body.appendChild(hub.el('tr', {}, [
                        hub.el('td', {}, [line.productName || ('#' + line.productId)]),
                        hub.el('td', {}, [String(line.qty || 0)]),
                        hub.el('td', {}, [hub.fmtMoney(line.unitPrice || 0)]),
                        hub.el('td', {}, [hub.fmtMoney(line.subtotal || 0)])
                    ]));
                });
                table.appendChild(body);
                card.appendChild(table);
                card.appendChild(hub.el('div', { class: 'ws-form-actions' }, [
                    hub.el('strong', {}, ['Tổng cộng: ' + hub.fmtMoney(order.totalAmount || 0)])
                ]));

                if (order.pickupCode) {
                    card.appendChild(hub.el('p', {}, [
                        hub.el('strong', {}, ['Mã nhận hàng: ']),
                        order.pickupCode
                    ]));
                }
                var back = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Về danh sách đơn']);
                back.addEventListener('click', function () { router.go('concession-orders'); });
                card.appendChild(back);
            }).catch(function (error) {
                card.replaceChildren(
                    hub.el('p', { class: 'ws-flash err' }, [error.message || 'Không tải được chi tiết đơn hàng.']),
                    hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Về danh sách đơn'])
                );
                card.querySelector('button').addEventListener('click', function () {
                    router.go('concession-orders');
                });
            });
        }
    });

    // ================================================================
    //  REPORT (Admin + Manager)
    // ================================================================
    router.register('report', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var params = new URLSearchParams(window.location.search);
            var now = new Date();
            var today = now.toISOString().slice(0, 10);
            var monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);

            var page = hub.el('div', { class: 'ws-page', style: 'max-width:1200px;margin:0 auto;padding-bottom:40px;' });
            page.appendChild(hub.el('div', { class: 'ws-page-header', style: 'margin-bottom:20px;' }, [
                hub.el('h1', { class: 'ws-page-title', style: 'font-size:1.6rem;font-weight:700;color:#1e293b;margin:0 0 6px 0;' }, ['Báo cáo doanh thu & vận hành']),
                hub.el('p', { class: 'ws-page-subtitle', style: 'color:#64748b;margin:0;font-size:0.9rem;' }, ['Tổng hợp dữ liệu bán vé, doanh số bắp nước (F&B) và vận hành theo chi nhánh.'])
            ]));

            // Toolbar chọn khoảng thời gian + phím tắt nhanh
            var toolbar = hub.el('div', { class: 'ws-card', style: 'background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:16px 20px;margin-bottom:20px;display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px;box-shadow:0 1px 3px rgba(0,0,0,0.04);' });
            var left = hub.el('div', { style: 'display:flex;align-items:center;flex-wrap:wrap;gap:12px;' });

            var roleMeta = (document.querySelector('meta[name="user-role"]') || document.querySelector('meta[name="role"]') || {}).content || '';
            var isAdmin = (roleMeta === 'ADMIN');
            var branchSelect = null;
            if (isAdmin) {
                var curBranch = params.get('branchId') || (window.__currentDashboardBranchId || '');
                branchSelect = hub.el('select', { style: 'padding:7px 12px;border:1px solid #cbd5e1;border-radius:8px;font-size:0.9rem;background:#fff;font-weight:600;color:#1e293b;cursor:pointer;' }, [
                    hub.el('option', { value: '', selected: !curBranch }, ['Tất cả chi nhánh (Toàn chuỗi)'])
                ]);
                if (curBranch) {
                    branchSelect.appendChild(hub.el('option', { value: curBranch, selected: true }, ['Chi nhánh #' + curBranch]));
                }
                hub.api('/branch').then(function (branches) {
                    var branchList = Array.isArray(branches) ? branches : [];
                    branchSelect.innerHTML = '';
                    branchSelect.appendChild(hub.el('option', { value: '', selected: !curBranch }, ['Tất cả chi nhánh (Toàn chuỗi)']));
                    branchList.forEach(function (b) {
                        var isSel = String(curBranch) === String(b.id);
                        var opt = hub.el('option', { value: String(b.id) }, [b.name ? b.name : '#' + b.id]);
                        if (isSel) opt.selected = true;
                        branchSelect.appendChild(opt);
                    });
                    if (curBranch) branchSelect.value = curBranch;
                }).catch(function () {});
                branchSelect.addEventListener('change', function () {
                    window.__currentDashboardBranchId = branchSelect.value;
                    load();
                });
                left.appendChild(hub.el('label', { style: 'display:flex;align-items:center;gap:6px;font-size:0.88rem;font-weight:600;color:#475569;' }, ['Chi nhánh:', branchSelect]));
            }

            var fromInput = hub.el('input', { type: 'date', value: params.get('from') || monthAgo, 'aria-label': 'Từ ngày', style: 'padding:7px 12px;border:1px solid #cbd5e1;border-radius:8px;font-size:0.9rem;background:#f8fafc;' });
            var toInput = hub.el('input', { type: 'date', value: params.get('to') || today, 'aria-label': 'Đến ngày', style: 'padding:7px 12px;border:1px solid #cbd5e1;border-radius:8px;font-size:0.9rem;background:#f8fafc;' });
            left.appendChild(hub.el('label', { style: 'display:flex;align-items:center;gap:6px;font-size:0.88rem;font-weight:600;color:#475569;' }, ['Từ ngày:', fromInput]));
            left.appendChild(hub.el('label', { style: 'display:flex;align-items:center;gap:6px;font-size:0.88rem;font-weight:600;color:#475569;' }, ['Đến ngày:', toInput]));

            // Nút bấm nhanh
            var quickFilters = hub.el('div', { style: 'display:flex;align-items:center;gap:6px;' });
            function makeQuickBtn(text, getRange) {
                var btn = hub.el('button', { type: 'button', class: 'ws-btn secondary small', style: 'padding:6px 12px;font-size:0.8rem;border-radius:6px;background:#f1f5f9;color:#334155;border:1px solid #e2e8f0;cursor:pointer;' }, [text]);
                btn.addEventListener('click', function () {
                    var r = getRange();
                    fromInput.value = r.from;
                    toInput.value = r.to;
                    load();
                });
                return btn;
            }
            quickFilters.appendChild(makeQuickBtn('Hôm nay', function () {
                var t = new Date().toISOString().slice(0, 10);
                return { from: t, to: t };
            }));
            quickFilters.appendChild(makeQuickBtn('7 ngày qua', function () {
                var t = new Date().toISOString().slice(0, 10);
                var f = new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
                return { from: f, to: t };
            }));
            quickFilters.appendChild(makeQuickBtn('30 ngày qua', function () {
                var t = new Date().toISOString().slice(0, 10);
                var f = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
                return { from: f, to: t };
            }));
            quickFilters.appendChild(makeQuickBtn('Tháng này', function () {
                var d = new Date();
                var f = new Date(d.getFullYear(), d.getMonth(), 1).toISOString().slice(0, 10);
                var t = d.toISOString().slice(0, 10);
                return { from: f, to: t };
            }));
            left.appendChild(quickFilters);
            toolbar.appendChild(left);

            var right = hub.el('div', { style: 'display:flex;align-items:center;gap:10px;' });
            var runBtn = hub.el('button', { class: 'ws-btn primary', type: 'button', style: 'padding:8px 20px;font-weight:600;border-radius:8px;background:#2563eb;color:#fff;border:none;cursor:pointer;' }, ['Xem báo cáo']);
            right.appendChild(runBtn);
            toolbar.appendChild(right);
            page.appendChild(toolbar);

            // Container thẻ KPI 6 ô đẹp mắt
            var kpis = hub.el('div', { style: 'display:grid;grid-template-columns:repeat(auto-fit, minmax(180px, 1fr));gap:14px;margin-bottom:24px;' });
            page.appendChild(kpis);

            // Tabs chuyển đổi bảng chi tiết
            var tabsContainer = hub.el('div', { style: 'display:flex;gap:8px;margin-bottom:16px;border-bottom:2px solid #e2e8f0;padding-bottom:8px;flex-wrap:wrap;' });
            var currentTab = 'orders'; // 'orders' | 'fnb' | 'movies' | 'daily'
            var tabBtnOrders = hub.el('button', { type: 'button', class: 'ws-tab-btn active', style: 'padding:8px 18px;border-radius:8px;font-weight:600;font-size:0.92rem;border:none;cursor:pointer;background:#2563eb;color:#fff;transition:all .15s;' }, ['Đơn hàng F&B']);
            var tabBtnFnb = hub.el('button', { type: 'button', class: 'ws-tab-btn', style: 'padding:8px 18px;border-radius:8px;font-weight:600;font-size:0.92rem;border:none;cursor:pointer;background:#f1f5f9;color:#475569;transition:all .15s;' }, ['Sản phẩm F&B']);
            var tabBtnMovies = hub.el('button', { type: 'button', class: 'ws-tab-btn', style: 'padding:8px 18px;border-radius:8px;font-weight:600;font-size:0.92rem;border:none;cursor:pointer;background:#f1f5f9;color:#475569;transition:all .15s;' }, ['Vé xem phim']);
            var tabBtnDaily = hub.el('button', { type: 'button', class: 'ws-tab-btn', style: 'padding:8px 18px;border-radius:8px;font-weight:600;font-size:0.92rem;border:none;cursor:pointer;background:#f1f5f9;color:#475569;transition:all .15s;' }, ['Chi tiết theo Ngày']);
            tabsContainer.appendChild(tabBtnOrders);
            tabsContainer.appendChild(tabBtnFnb);
            tabsContainer.appendChild(tabBtnMovies);
            tabsContainer.appendChild(tabBtnDaily);
            page.appendChild(tabsContainer);

            var tableWrap = hub.el('div', { class: 'ws-card', style: 'background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,0.04);padding:0;' });
            page.appendChild(tableWrap);
            viewRoot.replaceChildren(page);

            var reportData = null;

            function renderTabsHeader() {
                var orderCount = (reportData && reportData.orders && reportData.orders.length) || 0;
                var fnbCount = (reportData && reportData.products && reportData.products.length) || 0;
                var movCount = (reportData && reportData.movies && reportData.movies.length) || 0;
                var dayCount = (reportData && reportData.daily && reportData.daily.length) || 0;

                tabBtnOrders.textContent = 'Đơn hàng F&B (' + orderCount + ')';
                tabBtnFnb.textContent = 'Món & Combo (' + fnbCount + ')';
                tabBtnMovies.textContent = 'Vé xem phim (' + movCount + ')';
                tabBtnDaily.textContent = 'Chi tiết theo Ngày (' + dayCount + ')';

                var activeStyle = 'padding:8px 18px;border-radius:8px;font-weight:600;font-size:0.92rem;border:none;cursor:pointer;background:#2563eb;color:#fff;';
                var inactiveStyle = 'padding:8px 18px;border-radius:8px;font-weight:600;font-size:0.92rem;border:none;cursor:pointer;background:#f1f5f9;color:#475569;';

                tabBtnOrders.style.cssText = currentTab === 'orders' ? activeStyle : inactiveStyle;
                tabBtnFnb.style.cssText = (currentTab === 'fnb' ? activeStyle : inactiveStyle) + (fnbCount > 0 ? '' : 'display:none;');
                tabBtnMovies.style.cssText = currentTab === 'movies' ? activeStyle : inactiveStyle;
                tabBtnDaily.style.cssText = currentTab === 'daily' ? activeStyle : inactiveStyle;
            }

            var PAGE_SIZE = 10;
            var tabPages = { orders: 0, fnb: 0, movies: 0, daily: 0 };

            function renderPagination(total, page, pageSize, onPageChange) {
                if (total <= pageSize) return null;
                var totalPages = Math.ceil(total / pageSize);
                var wrap = hub.el('div', { class: 'ws-pagination', style: 'padding:12px 18px;display:flex;align-items:center;justify-content:space-between;border-top:1px solid #e2e8f0;background:#fff;' });
                var from = page * pageSize + 1;
                var to = Math.min((page + 1) * pageSize, total);
                var info = hub.el('div', { class: 'ws-pagination-info', style: 'font-size:0.85rem;color:#64748b;font-weight:500;' }, [from + '–' + to + ' / ' + total + ' mục']);
                wrap.appendChild(info);

                var controls = hub.el('div', { class: 'ws-pagination-controls', style: 'display:flex;align-items:center;gap:6px;' });
                var prevBtn = hub.el('button', {
                    class: 'ws-pagination-btn',
                    type: 'button',
                    style: 'padding:4px 12px;border-radius:6px;border:1px solid #cbd5e1;background:#fff;cursor:pointer;font-size:0.85rem;color:#334155;',
                    'aria-label': 'Trang trước'
                }, ['Trước']);
                prevBtn.disabled = page === 0;
                if (page === 0) prevBtn.style.opacity = '0.5';
                prevBtn.addEventListener('click', function () { onPageChange(page - 1); });
                controls.appendChild(prevBtn);

                for (var p = 0; p < totalPages; p++) {
                    if (totalPages > 7) {
                        if (p > 0 && p < page - 1 && p !== 0) {
                            if (p === 1) controls.appendChild(hub.el('span', { style: 'padding:0 4px;color:#9ca3af;' }, ['…']));
                            continue;
                        }
                        if (p < totalPages - 1 && p > page + 1 && p !== totalPages - 1) {
                            if (p === totalPages - 2) controls.appendChild(hub.el('span', { style: 'padding:0 4px;color:#9ca3af;' }, ['…']));
                            continue;
                        }
                    }
                    (function (pg) {
                        var isActive = (pg === page);
                        var btn = hub.el('button', {
                            class: 'ws-pagination-btn' + (isActive ? ' active' : ''),
                            type: 'button',
                            style: 'min-width:32px;height:32px;padding:0 8px;border-radius:6px;font-size:0.85rem;font-weight:600;cursor:pointer;' +
                                (isActive ? 'background:#2563eb;color:#fff;border:1px solid #2563eb;' : 'background:#fff;color:#334155;border:1px solid #cbd5e1;')
                        }, [String(pg + 1)]);
                        btn.addEventListener('click', function () { onPageChange(pg); });
                        controls.appendChild(btn);
                    })(p);
                }

                var nextBtn = hub.el('button', {
                    class: 'ws-pagination-btn',
                    type: 'button',
                    style: 'padding:4px 12px;border-radius:6px;border:1px solid #cbd5e1;background:#fff;cursor:pointer;font-size:0.85rem;color:#334155;',
                    'aria-label': 'Trang sau'
                }, ['Sau']);
                nextBtn.disabled = page >= totalPages - 1;
                if (page >= totalPages - 1) nextBtn.style.opacity = '0.5';
                nextBtn.addEventListener('click', function () { onPageChange(page + 1); });
                controls.appendChild(nextBtn);

                wrap.appendChild(controls);
                return wrap;
            }

            tabBtnOrders.addEventListener('click', function () { currentTab = 'orders'; tabPages.orders = 0; renderTabsHeader(); renderTable(); });
            tabBtnFnb.addEventListener('click', function () { currentTab = 'fnb'; tabPages.fnb = 0; renderTabsHeader(); renderTable(); });
            tabBtnMovies.addEventListener('click', function () { currentTab = 'movies'; tabPages.movies = 0; renderTabsHeader(); renderTable(); });
            tabBtnDaily.addEventListener('click', function () { currentTab = 'daily'; tabPages.daily = 0; renderTabsHeader(); renderTable(); });

            function createKpiCard(label, valStr, accentColor) {
                var card = hub.el('div', {
                    style: 'background:#ffffff;border:1px solid #e2e8f0;border-top:4px solid ' + accentColor + ';border-radius:10px;padding:14px 16px;box-shadow:0 1px 3px rgba(0,0,0,0.04);'
                });
                card.appendChild(hub.el('div', { style: 'font-size:0.8rem;font-weight:700;text-transform:uppercase;color:#64748b;letter-spacing:0.3px;margin-bottom:6px;' }, [label]));
                card.appendChild(hub.el('div', { style: 'font-size:1.45rem;font-weight:700;color:' + accentColor + ';line-height:1.2;' }, [valStr]));
                return card;
            }

            function renderTable() {
                if (!reportData) return;
                tableWrap.replaceChildren();

                if (currentTab === 'orders') {
                    var allOrders = reportData.orders || [];
                    if (!allOrders.length) {
                        tableWrap.appendChild(hub.el('div', { class: 'ws-empty', style: 'padding:40px 20px;text-align:center;' }, [
                            hub.el('h3', { style: 'color:#475569;font-size:1.1rem;margin-bottom:6px;' }, ['Chưa có đơn F&B nào trong khoảng ngày đã chọn']),
                            hub.el('p', { style: 'color:#94a3b8;font-size:0.88rem;margin:0;' }, ['Dữ liệu đơn bắp nước sẽ hiển thị khi khách mua tại quầy POS hoặc đặt trực tuyến trên web.'])
                        ]));
                        return;
                    }
                    var totalOrderRev = 0;
                    allOrders.forEach(function (o) {
                        var netAmount = (o.totalAmount || 0) - (o.pointsDiscount || 0);
                        if (o.status === 'READY_FOR_PICKUP' || o.status === 'FULFILLED') {
                            totalOrderRev += netAmount;
                        }
                    });

                    var curPage = tabPages.orders || 0;
                    var maxPage = Math.max(0, Math.ceil(allOrders.length / PAGE_SIZE) - 1);
                    if (curPage > maxPage) curPage = maxPage;
                    tabPages.orders = curPage;

                    var pagedOrders = allOrders.slice(curPage * PAGE_SIZE, (curPage + 1) * PAGE_SIZE);

                    var table = hub.el('table', { class: 'ws-table', style: 'width:100%;margin:0;' });
                    table.innerHTML = '<thead style="background:#f8fafc;border-bottom:1px solid #e2e8f0;">' +
                        '<tr>' +
                        '<th style="padding:12px 16px;text-align:left;font-size:0.85rem;color:#475569;">Mã đơn</th>' +
                        '<th style="padding:12px 16px;text-align:left;font-size:0.85rem;color:#475569;">Khách hàng</th>' +
                        '<th style="padding:12px 16px;text-align:left;font-size:0.85rem;color:#475569;">Chi nhánh</th>' +
                        '<th style="padding:12px 16px;text-align:right;font-size:0.85rem;color:#475569;">Doanh thu</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Trạng thái</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Thời gian đặt</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Thao tác</th>' +
                        '</tr></thead>';
                    var tbody = hub.el('tbody');
                    pagedOrders.forEach(function (o) {
                        var netAmount = (o.totalAmount || 0) - (o.pointsDiscount || 0);
                        var statusLabel = (o.status === 'READY_FOR_PICKUP')
                            ? '<span style="background:#e0f2fe;color:#0284c7;padding:3px 10px;border-radius:6px;font-size:0.75rem;font-weight:700;">Chờ nhận</span>'
                            : (o.status === 'FULFILLED'
                                ? '<span style="background:#ecfdf5;color:#059669;padding:3px 10px;border-radius:6px;font-size:0.75rem;font-weight:700;">Đã nhận</span>'
                                : (o.status === 'CANCELLED'
                                    ? '<span style="background:#fef2f2;color:#dc2626;padding:3px 10px;border-radius:6px;font-size:0.75rem;font-weight:700;">Đã hủy</span>'
                                    : '<span style="background:#f1f5f9;color:#475569;padding:3px 10px;border-radius:6px;font-size:0.75rem;font-weight:700;">' + (o.status || '—') + '</span>'));

                        var tr = hub.el('tr', { style: 'border-bottom:1px solid #f1f5f9;' });
                        var timeStr = '—';
                        if (o.createdAt) {
                            if (hub.fmtNotificationDateTime) timeStr = hub.fmtNotificationDateTime(o.createdAt);
                            else if (hub.fmtDateTime) timeStr = hub.fmtDateTime(o.createdAt);
                            else timeStr = String(o.createdAt).replace('T', ' ').slice(0, 19);
                        }
                        tr.innerHTML = '<td style="padding:12px 16px;font-weight:700;color:#2563eb;">' + (o.orderCode || ('#' + o.id)) + '</td>' +
                            '<td style="padding:12px 16px;color:#1e293b;font-weight:500;">' + (o.customerName || 'Khách vãng lai') + '</td>' +
                            '<td style="padding:12px 16px;color:#64748b;">' + (o.branchName || '—') + '</td>' +
                            '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#059669;font-size:1rem;">' + hub.fmtMoney(netAmount) + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;">' + statusLabel + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;color:#64748b;font-size:0.85rem;">' + timeStr + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;">' +
                            '<a href="' + ctx + '/console?module=concession-orders&action=view&id=' + o.id + '" class="ws-btn secondary" style="padding:4px 10px;font-size:0.78rem;font-weight:600;border-radius:6px;text-decoration:none;display:inline-block;border:1px solid #cbd5e1;background:#fff;color:#334155;">Chi tiết</a>' +
                            '</td>';
                        tbody.appendChild(tr);
                    });
                    var tfoot = hub.el('tfoot', { style: 'background:#f8fafc;border-top:2px solid #cbd5e1;' });
                    tfoot.innerHTML = '<tr>' +
                        '<td colspan="3" style="padding:12px 16px;font-weight:700;color:#1e293b;text-transform:uppercase;">Tổng cộng (' + allOrders.length + ' đơn)</td>' +
                        '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#059669;font-size:1.1rem;">' + hub.fmtMoney(totalOrderRev) + '</td>' +
                        '<td colspan="3"></td>' +
                        '</tr>';
                    table.appendChild(tbody);
                    table.appendChild(tfoot);
                    tableWrap.appendChild(table);

                    var pag = renderPagination(allOrders.length, curPage, PAGE_SIZE, function (newPage) {
                        tabPages.orders = newPage;
                        renderTable();
                    });
                    if (pag) tableWrap.appendChild(pag);

                } else if (currentTab === 'fnb') {
                    var allProds = reportData.products || [];
                    if (!allProds.length) {
                        tableWrap.appendChild(hub.el('div', { class: 'ws-empty', style: 'padding:40px 20px;text-align:center;' }, [
                            hub.el('h3', { style: 'color:#475569;font-size:1.1rem;margin-bottom:6px;' }, ['Chưa có thống kê theo món trong khoảng ngày đã chọn']),
                            hub.el('p', { style: 'color:#94a3b8;font-size:0.88rem;margin:0;' }, ['Vui lòng chuyển qua tab "Đơn hàng F&B" để xem chi tiết danh sách từng đơn hàng.'])
                        ]));
                        return;
                    }
                    var totalQty = 0;
                    var totalFnbRev = 0;
                    allProds.forEach(function (p) {
                        totalQty += (p.qtySold || 0);
                        totalFnbRev += (p.revenue || 0);
                    });

                    var curPage = tabPages.fnb || 0;
                    var maxPage = Math.max(0, Math.ceil(allProds.length / PAGE_SIZE) - 1);
                    if (curPage > maxPage) curPage = maxPage;
                    tabPages.fnb = curPage;

                    var pagedProds = allProds.slice(curPage * PAGE_SIZE, (curPage + 1) * PAGE_SIZE);

                    var table = hub.el('table', { class: 'ws-table', style: 'width:100%;margin:0;' });
                    table.innerHTML = '<thead style="background:#f8fafc;border-bottom:1px solid #e2e8f0;">' +
                        '<tr>' +
                        '<th style="padding:12px 16px;text-align:left;font-size:0.85rem;color:#475569;">Sản phẩm / Combo</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Phân loại</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Đơn vị</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Số lượng đã bán</th>' +
                        '<th style="padding:12px 16px;text-align:right;font-size:0.85rem;color:#475569;">Doanh thu</th>' +
                        '</tr></thead>';
                    var tbody = hub.el('tbody');
                    pagedProds.forEach(function (p) {
                        var typeBadge = (p.type === 'COMBO')
                            ? '<span style="background:#e0e7ff;color:#4338ca;padding:2px 8px;border-radius:6px;font-size:0.75rem;font-weight:700;">COMBO</span>'
                            : (p.type === 'DRINK' ? '<span style="background:#ecfdf5;color:#047857;padding:2px 8px;border-radius:6px;font-size:0.75rem;font-weight:600;">NƯỚC UỐNG</span>'
                            : (p.type === 'POPCORN' ? '<span style="background:#fef3c7;color:#b45309;padding:2px 8px;border-radius:6px;font-size:0.75rem;font-weight:600;">BẮP RANG</span>'
                            : '<span style="background:#f1f5f9;color:#475569;padding:2px 8px;border-radius:6px;font-size:0.75rem;font-weight:600;">KHÁC</span>'));

                        var tr = hub.el('tr', { style: 'border-bottom:1px solid #f1f5f9;' });
                        tr.innerHTML = '<td style="padding:12px 16px;font-weight:600;color:#1e293b;">' + (p.name || '—') + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;">' + typeBadge + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;color:#64748b;">' + (p.unit || 'Phần') + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;font-weight:700;font-size:1.05rem;color:#1e293b;">' + (p.qtySold || 0) + '</td>' +
                            '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#059669;font-size:1rem;">' + hub.fmtMoney(p.revenue || 0) + '</td>';
                        tbody.appendChild(tr);
                    });
                    var tfoot = hub.el('tfoot', { style: 'background:#f8fafc;border-top:2px solid #cbd5e1;' });
                    tfoot.innerHTML = '<tr>' +
                        '<td colspan="3" style="padding:12px 16px;font-weight:700;color:#1e293b;text-transform:uppercase;">Tổng cộng F&B</td>' +
                        '<td style="padding:12px 16px;text-align:center;font-weight:700;font-size:1.1rem;color:#1e293b;">' + totalQty + '</td>' +
                        '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#059669;font-size:1.1rem;">' + hub.fmtMoney(totalFnbRev) + '</td>' +
                        '</tr>';
                    table.appendChild(tbody);
                    table.appendChild(tfoot);
                    tableWrap.appendChild(table);

                    var pag = renderPagination(allProds.length, curPage, PAGE_SIZE, function (newPage) {
                        tabPages.fnb = newPage;
                        renderTable();
                    });
                    if (pag) tableWrap.appendChild(pag);

                } else if (currentTab === 'movies') {
                    var allMovies = reportData.movies || [];
                    if (!allMovies.length) {
                        tableWrap.appendChild(hub.el('div', { class: 'ws-empty', style: 'padding:40px 20px;text-align:center;' }, [
                            hub.el('h3', { style: 'color:#475569;font-size:1.1rem;margin-bottom:6px;' }, ['Chưa có dữ liệu bán vé trong khoảng ngày đã chọn']),
                            hub.el('p', { style: 'color:#94a3b8;font-size:0.88rem;margin:0;' }, ['Báo cáo vé sẽ xuất hiện khi có vé được xác nhận hoặc sử dụng.'])
                        ]));
                        return;
                    }
                    var totalTickets = 0;
                    var totalMovieRev = 0;
                    allMovies.forEach(function (r) {
                        totalTickets += (r.ticketsSold || 0);
                        totalMovieRev += (r.revenue || 0);
                    });

                    var curPage = tabPages.movies || 0;
                    var maxPage = Math.max(0, Math.ceil(allMovies.length / PAGE_SIZE) - 1);
                    if (curPage > maxPage) curPage = maxPage;
                    tabPages.movies = curPage;

                    var pagedMovies = allMovies.slice(curPage * PAGE_SIZE, (curPage + 1) * PAGE_SIZE);

                    var table = hub.el('table', { class: 'ws-table', style: 'width:100%;margin:0;' });
                    table.innerHTML = '<thead style="background:#f8fafc;border-bottom:1px solid #e2e8f0;">' +
                        '<tr>' +
                        '<th style="padding:12px 16px;text-align:left;font-size:0.85rem;color:#475569;">Tên phim</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Số vé đã bán</th>' +
                        '<th style="padding:12px 16px;text-align:right;font-size:0.85rem;color:#475569;">Doanh thu bán vé</th>' +
                        '</tr></thead>';
                    var tbody = hub.el('tbody');
                    pagedMovies.forEach(function (r) {
                        var tr = hub.el('tr', { style: 'border-bottom:1px solid #f1f5f9;' });
                        tr.innerHTML = '<td style="padding:12px 16px;font-weight:600;color:#1e293b;">' + (r.title || '—') + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;font-weight:700;font-size:1.05rem;color:#1e293b;">' + (r.ticketsSold || 0) + '</td>' +
                            '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#2563eb;font-size:1rem;">' + hub.fmtMoney(r.revenue || 0) + '</td>';
                        tbody.appendChild(tr);
                    });
                    var tfoot = hub.el('tfoot', { style: 'background:#f8fafc;border-top:2px solid #cbd5e1;' });
                    tfoot.innerHTML = '<tr>' +
                        '<td style="padding:12px 16px;font-weight:700;color:#1e293b;text-transform:uppercase;">Tổng cộng Vé</td>' +
                        '<td style="padding:12px 16px;text-align:center;font-weight:700;font-size:1.1rem;color:#1e293b;">' + totalTickets + '</td>' +
                        '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#2563eb;font-size:1.1rem;">' + hub.fmtMoney(totalMovieRev) + '</td>' +
                        '</tr>';
                    table.appendChild(tbody);
                    table.appendChild(tfoot);
                    tableWrap.appendChild(table);

                    var pag = renderPagination(allMovies.length, curPage, PAGE_SIZE, function (newPage) {
                        tabPages.movies = newPage;
                        renderTable();
                    });
                    if (pag) tableWrap.appendChild(pag);

                } else if (currentTab === 'daily') {
                    var allDaily = reportData.daily || [];
                    if (!allDaily.length) {
                        tableWrap.appendChild(hub.el('div', { class: 'ws-empty', style: 'padding:40px 20px;text-align:center;' }, [
                            hub.el('h3', { style: 'color:#475569;font-size:1.1rem;margin-bottom:6px;' }, ['Chưa có dữ liệu theo ngày trong khoảng đã chọn']),
                            hub.el('p', { style: 'color:#94a3b8;font-size:0.88rem;margin:0;' }, ['Dữ liệu sẽ xuất hiện khi có phát sinh bán vé hoặc bắp nước.'])
                        ]));
                        return;
                    }

                    var curPage = tabPages.daily || 0;
                    var maxPage = Math.max(0, Math.ceil(allDaily.length / PAGE_SIZE) - 1);
                    if (curPage > maxPage) curPage = maxPage;
                    tabPages.daily = curPage;

                    var pagedDaily = allDaily.slice(curPage * PAGE_SIZE, (curPage + 1) * PAGE_SIZE);

                    var table = hub.el('table', { class: 'ws-table', style: 'width:100%;margin:0;' });
                    table.innerHTML = '<thead style="background:#f8fafc;border-bottom:1px solid #e2e8f0;">' +
                        '<tr>' +
                        '<th style="padding:12px 16px;text-align:left;font-size:0.85rem;color:#475569;">Ngày</th>' +
                        '<th style="padding:12px 16px;text-align:right;font-size:0.85rem;color:#475569;">Tổng doanh thu</th>' +
                        '<th style="padding:12px 16px;text-align:center;font-size:0.85rem;color:#475569;">Số vé bán</th>' +
                        '<th style="padding:12px 16px;text-align:right;font-size:0.85rem;color:#475569;">Tiền hoàn trả</th>' +
                        '</tr></thead>';
                    var tbody = hub.el('tbody');
                    pagedDaily.forEach(function (d) {
                        var dayStr = d.day ? (typeof d.day === 'string' ? d.day : d.day.year + '-' + String(d.day.month).padStart(2, '0') + '-' + String(d.day.day).padStart(2, '0')) : '—';
                        var tr = hub.el('tr', { style: 'border-bottom:1px solid #f1f5f9;' });
                        tr.innerHTML = '<td style="padding:12px 16px;font-weight:600;color:#1e293b;">' + dayStr + '</td>' +
                            '<td style="padding:12px 16px;text-align:right;font-weight:700;color:#dc2626;font-size:1rem;">' + hub.fmtMoney(d.revenue || 0) + '</td>' +
                            '<td style="padding:12px 16px;text-align:center;font-weight:600;color:#1e293b;">' + (d.ticketsSold || 0) + '</td>' +
                            '<td style="padding:12px 16px;text-align:right;color:#ea580c;font-weight:600;">' + hub.fmtMoney(d.refundTotal || 0) + '</td>';
                        tbody.appendChild(tr);
                    });
                    table.appendChild(tbody);
                    tableWrap.appendChild(table);

                    var pag = renderPagination(allDaily.length, curPage, PAGE_SIZE, function (newPage) {
                        tabPages.daily = newPage;
                        renderTable();
                    });
                    if (pag) tableWrap.appendChild(pag);
                }
            }

            function load() {
                tabPages = { orders: 0, fnb: 0, movies: 0, daily: 0 };
                kpis.replaceChildren(hub.el('div', { class: 'ws-list-loading', style: 'grid-column: 1 / -1;padding:30px;text-align:center;' }, [hub.el('div', { class: 'ws-spinner' })]));
                tableWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading', style: 'padding:40px;text-align:center;' }, [hub.el('div', { class: 'ws-spinner' })]));
                var qs = new URLSearchParams();
                if (fromInput.value) qs.set('from', fromInput.value);
                if (toInput.value) qs.set('to', toInput.value);
                if (branchSelect && branchSelect.value) qs.set('branchId', branchSelect.value);

                var orderQs = new URLSearchParams();
                if (fromInput.value) orderQs.set('from', fromInput.value);
                if (toInput.value) orderQs.set('to', toInput.value);
                if (branchSelect && branchSelect.value) orderQs.set('branchId', branchSelect.value);
                orderQs.set('limit', '100');

                Promise.all([
                    hub.api('/report/summary?' + qs.toString()),
                    hub.api('/concession/orders?' + orderQs.toString()).catch(function () { return { items: [] }; })
                ]).then(function (results) {
                    reportData = results[0] || {};
                    var ordResp = results[1] || {};
                    var concessionOrders = (ordResp && ordResp.items) || [];
                    if (!reportData.orders || !reportData.orders.length) {
                        reportData.orders = concessionOrders;
                    }

                    var rev = reportData.revenue || {};
                    var ticketRev = typeof rev.ticketRevenue === 'number' ? rev.ticketRevenue : (rev.revenue || 0);
                    var fnbRev = typeof rev.fnbRevenue === 'number' ? rev.fnbRevenue : 0;
                    var fnbOrders = typeof rev.fnbOrdersSold === 'number' ? rev.fnbOrdersSold : 0;

                    // Đối soát nếu backend trả về fnbOrders = 0 nhưng có danh sách đơn F&B thực tế
                    if ((!fnbOrders || !fnbRev) && reportData.orders && reportData.orders.length) {
                        var soldFnbCount = 0;
                        var calcFnbRev = 0;
                        reportData.orders.forEach(function (o) {
                            if (o.status === 'READY_FOR_PICKUP' || o.status === 'FULFILLED') {
                                soldFnbCount++;
                                calcFnbRev += ((o.totalAmount || 0) - (o.pointsDiscount || 0));
                            }
                        });
                        if (!fnbOrders) fnbOrders = soldFnbCount;
                        if (!fnbRev) fnbRev = calcFnbRev;
                    }

                    kpis.replaceChildren(
                        createKpiCard('Tổng doanh thu', hub.fmtMoney(rev.revenue || ((ticketRev || 0) + (fnbRev || 0))), '#dc2626'),
                        createKpiCard('Doanh thu vé', hub.fmtMoney(ticketRev), '#2563eb'),
                        createKpiCard('Doanh thu F&B', hub.fmtMoney(fnbRev), '#059669'),
                        createKpiCard('Số vé bán', String(rev.ticketsSold || 0), '#7c3aed'),
                        createKpiCard('Số đơn F&B', String(fnbOrders), '#0891b2'),
                        createKpiCard('Tiền hoàn trả', hub.fmtMoney(rev.refundTotal || 0), '#ea580c')
                    );

                    // Tổng hợp theo ngày nếu API backend chưa có bảng ngày
                    if ((!reportData.daily || !reportData.daily.length) && (reportData.orders && reportData.orders.length)) {
                        var dayMap = {};
                        reportData.orders.forEach(function (o) {
                            var day = o.createdAt ? String(o.createdAt).substring(0, 10) : 'Khác';
                            if (!dayMap[day]) dayMap[day] = { day: day, revenue: 0, ticketsSold: 0, refundTotal: 0 };
                            var net = (o.totalAmount || 0) - (o.pointsDiscount || 0);
                            if (o.status === 'READY_FOR_PICKUP' || o.status === 'FULFILLED') {
                                dayMap[day].revenue += net;
                            } else if (o.status === 'CANCELLED') {
                                dayMap[day].refundTotal += net;
                            }
                        });
                        if (reportData.movies && reportData.movies.length) {
                            var targetDay = toInput.value || fromInput.value || Object.keys(dayMap)[0] || '2026-11-01';
                            if (!dayMap[targetDay]) dayMap[targetDay] = { day: targetDay, revenue: 0, ticketsSold: 0, refundTotal: 0 };
                            reportData.movies.forEach(function (m) {
                                dayMap[targetDay].revenue += (m.revenue || 0);
                                dayMap[targetDay].ticketsSold += (m.ticketsSold || 0);
                            });
                        }
                        reportData.daily = Object.values(dayMap).sort(function (a, b) { return a.day > b.day ? -1 : 1; });
                    }

                    // Tự động chuyển tab: ưu tiên tab có dữ liệu để người dùng thấy ngay
                    if (reportData.orders && reportData.orders.length > 0) {
                        currentTab = 'orders';
                    } else if (reportData.movies && reportData.movies.length > 0) {
                        currentTab = 'movies';
                    } else if (reportData.products && reportData.products.length > 0) {
                        currentTab = 'fnb';
                    } else if (reportData.daily && reportData.daily.length > 0) {
                        currentTab = 'daily';
                    }
                    renderTabsHeader();
                    renderTable();
                }).catch(function (e) {
                    notifyError(e);
                    kpis.replaceChildren();
                    tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty', style: 'padding:30px;text-align:center;' }, [
                        hub.el('h3', { style: 'color:#dc2626;' }, ['Không tải được dữ liệu báo cáo']),
                        hub.el('p', {}, [e.message])
                    ]));
                });
            }

            runBtn.addEventListener('click', load);
            load();
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
                            hub.el('h3', {}, ['Không có thông báo']),
                            hub.el('p', {}, ['Bạn sẽ nhận thông báo khi có hoạt động mới.'])
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
                            var action = hub.showtimeScheduleReminderAction(n)
                                || hub.refundRequestAction(n);
                            var body = hub.repairMojibakeText(
                                action ? action.body : (n.body || n.title || '—'));
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
                            if (action && action.urgent !== undefined) {
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
                                }, [action.label || 'Xếp lịch ngay']));
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
    //  CUSTOMER-POINTS (Staff adjust loyalty)
    //  - Vào trang: load tất cả CUSTOMER (mới nhất trước)
    //  - Search box: lọc theo email / SĐT / tên (q=…)
    //  - Click 1 khách → load ledger bên phải
    // ================================================================
    router.register('customer-points', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Điều chỉnh điểm khách hàng']),
                hub.el('p', { class: 'ws-page-subtitle' }, [
                    'Tra cứu nhanh bằng email hoặc SĐT — hoặc tìm trong danh sách bên dưới.'
                ])
            ]));

            // ================================================================
            // QUICK LOOKUP — tra cứu nhanh bằng email/phone → hiện điểm ngay
            // ================================================================
            var quickCard = hub.el('div', { class: 'ws-card', style: 'margin-bottom:16px' });
            var quickRow = hub.el('div', { style: 'display:flex;gap:10px;align-items:flex-end;flex-wrap:wrap' });
            var quickEmailWrap = hub.el('div', { style: 'flex:1;min-width:200px' });
            quickEmailWrap.appendChild(hub.el('label', { class: 'ws-label', style: 'margin-bottom:4px;display:block' }, ['Email khách hàng']));
            var quickEmail = hub.el('input', {
                type: 'email', id: 'ql-email',
                placeholder: 'example@email.com',
                style: 'width:100%;padding:9px 12px;border:1px solid var(--ws-line);border-radius:8px;box-sizing:border-box'
            });
            quickEmailWrap.appendChild(quickEmail);

            var quickPhoneWrap = hub.el('div', { style: 'flex:1;min-width:150px' });
            quickPhoneWrap.appendChild(hub.el('label', { class: 'ws-label', style: 'margin-bottom:4px;display:block' }, ['Số điện thoại']));
            var quickPhone = hub.el('input', {
                type: 'tel', id: 'ql-phone',
                placeholder: '0901234567',
                style: 'width:100%;padding:9px 12px;border:1px solid var(--ws-line);border-radius:8px;box-sizing:border-box'
            });
            quickPhoneWrap.appendChild(quickPhone);

            var quickBtn = hub.el('button', { type: 'button', class: 'ws-btn primary' }, ['Tra cứu']);
            quickBtn.style.height = '38px';
            quickRow.appendChild(quickEmailWrap);
            quickRow.appendChild(quickPhoneWrap);
            quickRow.appendChild(quickBtn);
            quickCard.appendChild(quickRow);

            // Kết quả tra cứu
            var quickResult = hub.el('div', { id: 'ql-result', style: 'margin-top:12px;display:none' });
            quickCard.appendChild(quickResult);
            page.appendChild(quickCard);

            // Layout 2 cột: list customers (trái) + ledger (phải)
            var layout = hub.el('div', { class: 'ws-loyalty-layout', style: 'display:grid;grid-template-columns:380px 1fr;gap:16px;align-items:start' });

            // --- Cột trái: search + danh sách khách ---
            var leftCol = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
            var searchWrap = hub.el('div', { style: 'padding:12px;border-bottom:1px solid var(--ws-line);display:flex;gap:8px' });
            var searchInput = hub.el('input', {
                type: 'text',
                placeholder: 'Tìm theo email, SĐT hoặc tên…',
                'aria-label': 'Tìm khách hàng',
                style: 'flex:1;padding:8px 12px;border:1px solid var(--ws-line);border-radius:8px;background:#fff'
            });
            searchWrap.appendChild(searchInput);
            leftCol.appendChild(searchWrap);

            var customersList = hub.el('div', { class: 'ws-customer-list', style: 'max-height:calc(100vh - 280px);overflow:auto' });
            leftCol.appendChild(customersList);

            // --- Cột phải: ledger ---
            var rightCol = hub.el('div', { class: 'ws-loyalty-right' });
            var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
            var left = hub.el('div', { class: 'ws-list-toolbar-left' });
            var customerInput = hub.el('input', {
                type: 'hidden',
                id: 'cp-selected-customer-id'
            });
            var customerBadge = hub.el('span', { class: 'ws-badge muted', style: 'padding:6px 10px;background:#f4f5f7;border-radius:6px;color:#5b5b66' }, ['Chưa chọn khách']);
            left.appendChild(customerBadge);
            toolbar.appendChild(left);
            var right = hub.el('div', { class: 'ws-list-toolbar-right' });
            var adjustBtn = hub.el('button', { class: 'ws-btn primary', type: 'button' }, ['+ Điều chỉnh điểm']);
            adjustBtn.disabled = true;
            right.appendChild(adjustBtn);
            toolbar.appendChild(right);
            rightCol.appendChild(toolbar);
            var tableWrap = hub.el('div', { class: 'ws-card', style: 'padding:0;overflow:hidden' });
            rightCol.appendChild(tableWrap);

            layout.appendChild(leftCol);
            layout.appendChild(rightCol);
            page.appendChild(layout);
            viewRoot.replaceChildren(page);

            // ================================================================
            // QUICK LOOKUP logic
            // ================================================================
            function doQuickLookup() {
                var emailVal = quickEmail.value.trim();
                var phoneVal = quickPhone.value.trim();
                if (!emailVal && !phoneVal) {
                    hub.notify('Nhập email hoặc số điện thoại để tra cứu.', 'warn');
                    return;
                }
                var qs = emailVal ? '?email=' + encodeURIComponent(emailVal) : '?phone=' + encodeURIComponent(phoneVal);
                quickBtn.disabled = true;
                quickBtn.textContent = 'Đang tra cứu…';
                quickResult.style.display = 'none';
                hub.api('/loyalty/customer-points' + qs).then(function (data) {
                    quickBtn.disabled = false;
                    quickBtn.textContent = 'Tra cứu';
                    quickResult.style.display = 'block';
                    quickResult.innerHTML = '<div style="display:flex;gap:16px;align-items:center;padding:12px 14px;background:#f0fdf4;border:1px solid #bbf7d0;border-radius:8px">' +
                        '<div style="flex:1">' +
                        '<div style="font-weight:700;font-size:1rem;color:#1c1c24">' + (data.fullName || ('Khách #' + data.id)) + '</div>' +
                        '<div style="font-size:.82rem;color:#5b5b66;margin-top:2px">' + (data.email || '—') + ' · ' + (data.phone || '—') + '</div>' +
                        '</div>' +
                        '<div style="text-align:center;padding:8px 16px;background:#fff;border-radius:8px;border:1px solid #e5e7eb">' +
                        '<div style="font-size:.72rem;text-transform:uppercase;letter-spacing:.05em;color:#8b8b96">Điểm hiện tại</div>' +
                        '<div style="font-size:1.5rem;font-weight:800;color:#d92121;line-height:1.2;margin-top:2px">' + Number(data.points || 0).toLocaleString('vi') + '</div>' +
                        '</div>' +
                        '<div style="text-align:center;padding:8px 16px;background:#fff;border-radius:8px;border:1px solid #e5e7eb">' +
                        '<div style="font-size:.72rem;text-transform:uppercase;letter-spacing:.05em;color:#8b8b96">Hạng</div>' +
                        '<div style="font-size:1rem;font-weight:700;color:#1c1c24;line-height:1.2;margin-top:2px">' + (data.tier || '—') + '</div>' +
                        '</div>' +
                        '</div>';
                    // Highlight ledger row if this customer is in list
                    var rows = customersList.querySelectorAll('.ws-customer-row');
                    rows.forEach(function (r) {
                        if (String(r.getAttribute('data-customer-id')) === String(data.id)) {
                            selectCustomer(data, r);
                        }
                    });
                }).catch(function (err) {
                    quickBtn.disabled = false;
                    quickBtn.textContent = 'Tra cứu';
                    quickResult.style.display = 'block';
                    quickResult.innerHTML = '<div style="padding:12px 14px;background:#fef2f2;border:1px solid #fca5a5;border-radius:8px;color:#dc2626;font-size:.9rem">' +
                        'Không tìm thấy khách hàng với thông tin này.' +
                        '</div>';
                });
            }

            quickBtn.addEventListener('click', doQuickLookup);
            quickEmail.addEventListener('keydown', function (e) { if (e.key === 'Enter') doQuickLookup(); });
            quickPhone.addEventListener('keydown', function (e) { if (e.key === 'Enter') doQuickLookup(); });

            // ---------- Render list khách hàng ----------
            function renderCustomers(items) {
                customersList.replaceChildren();
                if (!items.length) {
                    customersList.appendChild(hub.el('div', { class: 'ws-empty', style: 'padding:24px;text-align:center' }, [
                        hub.el('p', {}, ['Không có khách hàng nào.'])
                    ]));
                    return;
                }
                items.forEach(function (c) {
                    var points = Number(c.points || 0);
                    var row = hub.el('button', {
                        type: 'button',
                        class: 'ws-customer-row',
                        'data-customer-id': c.id,
                        style: 'display:block;width:100%;text-align:left;padding:12px 14px;border:0;border-bottom:1px solid var(--ws-line);background:#fff;cursor:pointer'
                    }, [
                        hub.el('div', { style: 'display:flex;align-items:center;gap:8px;justify-content:space-between' }, [
                            hub.el('div', { style: 'font-weight:600;color:#1c1c24' }, [c.fullName || ('Khách #' + c.id)]),
                            hub.el('span', {
                                style: 'flex-shrink:0;padding:2px 8px;border-radius:999px;background:#fef2f2;color:#d92121;font-size:.78rem;font-weight:700'
                            }, [points.toLocaleString('vi') + ' điểm'])
                        ]),
                        hub.el('div', { style: 'font-size:.82rem;color:#5b5b66;margin-top:2px' }, [
                            c.email || '—'
                        ]),
                        hub.el('div', { style: 'font-size:.82rem;color:#8b8b96;margin-top:2px' }, [
                            (c.phone || '—') + ' · #' + c.id + (c.tier ? ' · ' + c.tier : '')
                        ])
                    ]);
                    row.addEventListener('mouseenter', function () { row.style.background = '#f7f8fb'; });
                    row.addEventListener('mouseleave', function () { row.style.background = '#fff'; });
                    row.addEventListener('click', function () { selectCustomer(c, row); });
                    customersList.appendChild(row);
                });
            }

            function setActiveRow(activeRow) {
                var rows = customersList.querySelectorAll('.ws-customer-row');
                rows.forEach(function (r) { r.style.background = '#fff'; r.style.boxShadow = 'none'; });
                if (activeRow) {
                    activeRow.style.background = '#eef2ff';
                    activeRow.style.boxShadow = 'inset 3px 0 0 var(--ws-primary, #d92121)';
                }
            }

            function loadCustomers(term) {
                customersList.replaceChildren(hub.el('div', { class: 'ws-list-loading', style: 'padding:24px;text-align:center' }, [
                    hub.el('div', { class: 'ws-spinner' })
                ]));
                var qs = term && term.trim() ? '?q=' + encodeURIComponent(term.trim()) : '';
                hub.api('/loyalty/customers' + qs).then(function (data) {
                    renderCustomers((data && data.items) || []);
                }).catch(function (err) {
                    customersList.replaceChildren(hub.el('div', { class: 'ws-flash err', style: 'padding:16px' }, [
                        (err && err.message) || 'Không tải được danh sách khách hàng.'
                    ]));
                });
            }

            function selectCustomer(c, rowEl) {
                customerInput.value = c.id;
                customerBadge.textContent = (c.fullName || ('Khách #' + c.id)) + ' · ' + (c.email || c.phone || '#' + c.id);
                customerBadge.style.background = '#eef2ff';
                customerBadge.style.color = '#1c1c24';
                setActiveRow(rowEl);
                loadLedger(c.id);
            }

            function loadLedger(customerId) {
                if (!customerId) {
                    tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty', style: 'padding:32px;text-align:center' }, [
                        hub.el('h3', {}, ['Chọn khách hàng để xem lịch sử']),
                        hub.el('p', {}, ['Chọn 1 khách từ danh sách bên trái, hoặc tìm bằng email/SĐT.'])
                    ]));
                    adjustBtn.disabled = true;
                    return;
                }
                tableWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                hub.api('/loyalty/ledger?customerId=' + customerId).then(function (data) {
                    var items = (data && data.items) || [];
                    if (!items.length) {
                        tableWrap.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Chưa có giao dịch điểm']),
                            hub.el('p', {}, ['Khách hàng chưa phát sinh điểm thưởng.'])
                        ]));
                        adjustBtn.disabled = false;
                        return;
                    }
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Ngày</th><th>Loại</th><th>Điểm</th><th>Số dư sau</th><th>Lý do</th><th>Nhân viên</th></tr></thead>';
                    var tbody = hub.el('tbody');
                    items.forEach(function (e) {
                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', {}, [e.createdAt ? hub.fmtDateTime(e.createdAt) : '—']),
                            hub.el('td', {}, [e.refType || '—']),
                            hub.el('td', {}, [(e.delta > 0 ? '+' : '') + e.delta]),
                            hub.el('td', {}, [String(e.balanceAfter || 0)]),
                            hub.el('td', {}, [e.reason || '—']),
                            hub.el('td', {}, [e.staffId ? '#' + e.staffId : '—'])
                        ]));
                    });
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);
                    adjustBtn.disabled = false;
                }).catch(notifyError);
            }

            // Debounce search để đỡ spam request khi gõ
            var debouncedSearch = (function () {
                var t;
                return function (val) {
                    clearTimeout(t);
                    t = setTimeout(function () { loadCustomers(val); }, 250);
                };
            })();
            searchInput.addEventListener('input', function () { debouncedSearch(searchInput.value); });
            searchInput.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); loadCustomers(searchInput.value); }
            });

            adjustBtn.addEventListener('click', function () {
                var customerId = customerInput.value;
                if (!customerId) {
                    hub.notify('Vui lòng chọn khách hàng trước.', 'warn');
                    return;
                }
                form.renderFormPage({
                    breadcrumb: ['Vận hành', { label: 'Điểm khách hàng', href: ctx + '/console?module=customer-points' }, 'Điều chỉnh'],
                    title: 'Điều chỉnh điểm khách hàng',
                    subtitle: 'Số điểm dương để cộng, số âm để trừ. Mọi điều chỉnh sẽ được ghi log audit.',
                    submitLabel: 'Điều chỉnh',
                    sections: [{
                        title: 'Thông tin điều chỉnh',
                        fields: [
                            { name: 'customerId', label: 'ID khách hàng', type: 'number', required: true, value: customerId, min: 1, readonly: true },
                            { name: 'delta', label: 'Điểm (+ hoặc -)', type: 'number', required: true, step: 1, min: -10000, max: 10000,
                              customValidate: function (value) {
                                  return Number.isInteger(Number(value)) ? '' : 'Điểm điều chỉnh phải là số nguyên';
                              } },
                            { name: 'reason', label: 'Lý do', type: 'textarea', required: true, rows: 3, minLength: 5, fullWidth: true,
                              hint: 'Tối thiểu 5 ký tự. Sẽ hiển thị trong lịch sử điểm của khách.' }
                        ]
                    }],
                    onSubmit: function (data) {
                        hub.api('/loyalty/adjust', { method: 'POST', body: data })
                            .then(function () {
                                hub.notify('Đã điều chỉnh điểm.', 'ok');
                                router.go('customer-points');
                            })
                            .catch(notifyError);
                    },
                    onCancel: function () { router.go('customer-points'); }
                });
            });

            // Vào trang: hiện tất cả khách + ledger trống
            loadCustomers('');
            loadLedger(null);
        }
    });

    // ================================================================
    //  REVIEW-BRANCH (Manager: average theo phim, sau đó xem review)
    // ================================================================
    router.register('review-branch', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });

            // Page header
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('span', { class: 'ws-eyebrow' }, ['ĐÁNH GIÁ PHIM CỦA KHÁCH']),
                hub.el('h1', { class: 'ws-page-title' }, ['Đánh giá phim theo rạp']),
                hub.el('p', { class: 'ws-page-subtitle' }, [
                    'Chỉ xem nhận xét và số sao về phim của khách hàng. Khiếu nại dịch vụ rạp được xử lý riêng trong mục Khiếu nại.'
                ])
            ]));

            var branchSelect = hub.el('select', {
                class: 'ws-input',
                'aria-label': 'Chọn chi nhánh',
            });
            branchSelect.appendChild(hub.el('option', { value: '' }, ['— Chọn chi nhánh —']));
            page.appendChild(hub.el('label', { class: 'ws-label' }, ['Chi nhánh được phân công', branchSelect]));
            var listWrap = hub.el('div', { class: 'ws-review-movie-grid' });
            page.appendChild(listWrap);
            viewRoot.replaceChildren(page);

            function loadBranches() {
                return hub.api('/api/reviews?branches=true').then(function (branches) {
                    if (!Array.isArray(branches)) throw new Error('Danh sách chi nhánh không hợp lệ.');
                    branchSelect.replaceChildren(hub.el('option', { value: '' }, ['— Chọn chi nhánh —']));
                    branches.forEach(function (b) {
                        branchSelect.appendChild(hub.el('option', { value: String(b.id) }, [b.name || ('Chi nhánh #' + b.id)]));
                    });
                    if (branches.length === 0) {
                        listWrap.replaceChildren(hub.el('p', { class: 'ws-empty' }, [
                            'Tài khoản chưa được phân công chi nhánh đang hoạt động.'
                        ]));
                    } else if (branches.length === 1) {
                        branchSelect.value = String(branches[0].id);
                        loadMovies();
                    }
                }).catch(function (error) {
                    listWrap.replaceChildren(hub.el('p', { class: 'ws-flash err' }, [
                        error.message || 'Không tải được chi nhánh được phân công.'
                    ]));
                });
            }

            function renderMovies(items) {
                listWrap.replaceChildren();
                if (!items.length) {
                    listWrap.appendChild(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Chưa có phim đang chiếu tại chi nhánh này']),
                        hub.el('p', { class: 'ws-muted' }, ['Danh sách chỉ gồm phim có suất chiếu không bị hủy.'])
                    ]));
                    return;
                }
                items.forEach(function (movie) {
                    var average = Number(movie.average) || 0;
                    var count = Number(movie.total) || 0;
                    var card = hub.el('button', {
                        class: 'ws-review-movie-card',
                        type: 'button',
                        'aria-label': 'Xem đánh giá phim ' + movie.movieTitle
                    }, [
                        hub.el('strong', { class: 'ws-review-movie-title' }, [movie.movieTitle || ('Phim #' + movie.movieId)]),
                        hub.el('span', { class: 'ws-review-movie-average' }, [
                            hub.el('span', { class: 'ws-stars' }, [starString(average)]),
                            hub.el('strong', {}, [average.toFixed(1) + '/5'])
                        ]),
                        hub.el('span', { class: 'ws-muted' }, [count + ' đánh giá · Xem chi tiết'])
                    ]);
                    card.addEventListener('click', function () {
                        loadMovieReviews(movie.movieId, movie.movieTitle || ('Phim #' + movie.movieId), 0);
                    });
                    listWrap.appendChild(card);
                });
            }

            function loadMovies() {
                var branchId = branchSelect.value;
                if (!branchId) {
                    listWrap.replaceChildren(hub.el('p', { class: 'ws-muted' }, ['Chọn chi nhánh được phân công.']));
                    return;
                }
                listWrap.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                hub.api('/api/reviews?branchId=' + encodeURIComponent(branchId)).then(function (data) {
                    renderMovies(data.items || []);
                }).catch(function (err) {
                    listWrap.replaceChildren(hub.el('p', { class: 'ws-flash err' }, [
                        err && err.message ? err.message : 'Không tải được danh sách phim.'
                    ]));
                });
            }

            function loadMovieReviews(movieId, movieTitle, offset) {
                var branchId = branchSelect.value;
                var pageSize = 50;
                listWrap.replaceChildren();
                var backButton = hub.el('button', { class: 'ws-btn ghost', type: 'button' }, ['← Quay lại danh sách phim']);
                backButton.addEventListener('click', loadMovies);
                var detail = hub.el('section', { class: 'ws-card ws-review-details' }, [
                    backButton,
                    hub.el('h2', {}, [movieTitle]),
                    hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })])
                ]);
                listWrap.appendChild(detail);
                hub.api('/api/reviews?branchId=' + encodeURIComponent(branchId)
                    + '&movieId=' + encodeURIComponent(movieId)
                    + '&offset=' + offset + '&limit=' + pageSize).then(function (data) {
                    var reviews = data.items || [];
                    detail.replaceChildren(
                        backButton,
                        hub.el('h2', {}, [movieTitle]),
                        hub.el('p', { class: 'ws-review-movie-average' }, [
                            hub.el('span', { class: 'ws-stars' }, [starString(Number(data.average) || 0)]),
                            hub.el('strong', {}, [(Number(data.average) || 0).toFixed(1) + '/5']),
                            hub.el('span', { class: 'ws-muted' }, [' · ' + (data.total || 0) + ' đánh giá'])
                        ])
                    );
                    if (!reviews.length) {
                        detail.appendChild(hub.el('p', { class: 'ws-muted' }, ['Phim này chưa có đánh giá.']));
                        return;
                    }
                    var reviewList = hub.el('div', { class: 'ws-review-list' });
                    reviews.forEach(function (review) {
                        reviewList.appendChild(hub.el('article', { class: 'ws-review-item' }, [
                            hub.el('div', { class: 'ws-review-item-header' }, [
                                hub.el('strong', {}, [review.userFullName || ('Khách #' + review.userId)]),
                                hub.el('span', { class: 'ws-star-display' }, [starString(review.rating)])
                            ]),
                            hub.el('p', { class: 'ws-review-comment' }, [
                                review.comment || 'Khách chỉ chấm sao, không để lại bình luận.'
                            ]),
                            hub.el('small', { class: 'ws-muted' }, [
                                review.createdAt ? hub.fmtDateTime(review.createdAt) : '—'
                            ])
                        ]));
                    });
                    detail.appendChild(reviewList);
                    if (data.total > pageSize) {
                        var pagination = hub.el('div', { class: 'ws-actions' });
                        var previous = hub.el('button', { class: 'ws-btn ghost', type: 'button' }, ['Trang trước']);
                        var next = hub.el('button', { class: 'ws-btn ghost', type: 'button' }, ['Trang tiếp']);
                        previous.disabled = offset === 0;
                        next.disabled = offset + pageSize >= data.total;
                        previous.addEventListener('click', function () {
                            loadMovieReviews(movieId, movieTitle, Math.max(0, offset - pageSize));
                        });
                        next.addEventListener('click', function () {
                            loadMovieReviews(movieId, movieTitle, offset + pageSize);
                        });
                        pagination.append(previous, next);
                        detail.appendChild(pagination);
                    }
                }).catch(function (error) {
                    detail.replaceChildren(backButton, hub.el('p', { class: 'ws-flash err' }, [
                        error.message || 'Không tải được danh sách đánh giá.'
                    ]));
                });
            }

            branchSelect.addEventListener('change', loadMovies);
            loadBranches();
        }
    });

    // ================================================================
    //  CONCESSION-PICKUP (QR scan)
    // ================================================================
    router.register('concession-pickup', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Quét mã F&B']),
                hub.el('p', {                 class: 'ws-page-subtitle' }, ['Nhập mã nhận hàng để tra cứu; kiểm tra đơn rồi xác nhận đã giao cho khách.'])
            ]));
            var card = hub.el('div', { class: 'ws-card', style: 'padding:24px;max-width:540px' });
            card.appendChild(hub.el('label', { style: 'display:block;margin-bottom:6px;font-weight:600' }, ['Mã nhận hàng']));
            var codeInput = hub.el('input', {
                type: 'text', placeholder: 'PCK-XXXXX',
                style: 'width:100%;padding:12px;border:1px solid var(--ws-line);border-radius:8px;font-size:1rem;font-family:monospace;text-transform:uppercase'
            });
            card.appendChild(codeInput);
            var scanBtn = hub.el('button', { class: 'ws-btn primary', type: 'button', style: 'margin-top:12px;width:100%' }, ['Tra cứu']);
            card.appendChild(scanBtn);
            var result = hub.el('div', { style: 'margin-top:20px' });
            card.appendChild(result);
            page.appendChild(card);
            viewRoot.replaceChildren(page);

            var lookupFn = function () {
                var code = codeInput.value.trim();
                if (!code) {
                    hub.notify('Vui lòng nhập mã nhận hàng hoặc mã đơn!', 'warn');
                    codeInput.focus();
                    return;
                }
                result.replaceChildren(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
                hub.api('/concession/scan', { method: 'POST', body: { code: code.toUpperCase() } }).then(function (data) {
                    if (!data.canRedeem) {
                        result.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                            hub.el('h3', {}, ['Không thể giao đơn này']),
                            hub.el('p', {}, [data.message || 'Đơn không ở trạng thái sẵn sàng nhận.'])
                        ]));
                        return;
                    }
                    var box = hub.el('div', { class: 'ws-card', style: 'padding:20px' });
                    box.appendChild(hub.el('h3', {}, ['✓ Sẵn sàng giao hàng']));
                    var grid = hub.el('div', { style: 'display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px' });
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Mã đơn:']), ' ', data.orderCode || '—']));
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Khách:']), ' ', data.customerName || '—']));
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Tổng tiền:']), ' ', hub.fmtMoney(data.totalAmount)]));
                    grid.appendChild(hub.el('div', {}, [hub.el('strong', {}, ['Chi nhánh:']), ' ', data.branchId || '—']));
                    box.appendChild(grid);
                    var lines = (data.lines || []).map(function (l) {
                        return hub.el('div', { style: 'padding:6px 0;border-bottom:1px solid var(--ws-line)' },
                            [l.productName + ' ×' + l.qty + '  ', hub.el('strong', {}, [hub.fmtMoney(l.subtotal)])]);
                    });
                    if (lines.length) {
                        box.appendChild(hub.el('h4', { style: 'margin-top:16px' }, ['Sản phẩm']));
                        lines.forEach(function (l) { box.appendChild(l); });
                    }
                    var confirmBtn = hub.el('button', { class: 'ws-btn primary', type: 'button', style: 'margin-top:16px;width:100%' }, ['✓ Xác nhận đã giao cho khách']);
                    confirmBtn.addEventListener('click', function () {
                        confirmBtn.disabled = true;
                        confirmBtn.textContent = 'Đang xử lý giao hàng...';
                        hub.api('/concession/redeem', { method: 'POST', body: { orderId: data.orderId } })
                            .then(function () {
                                hub.notify('Giao đơn hàng thành công!', 'ok');
                                result.replaceChildren();
                                codeInput.value = '';
                                codeInput.focus();
                            })
                            .catch(function (err) {
                                confirmBtn.disabled = false;
                                confirmBtn.textContent = '✓ Xác nhận đã giao cho khách';
                                notifyError(err);
                            });
                    });
                    box.appendChild(confirmBtn);
                    result.replaceChildren(box);
                }).catch(function (e) {
                    result.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                        hub.el('h3', {}, ['Không tìm thấy đơn']),
                        hub.el('p', {}, [e.message || 'Vui lòng kiểm tra lại mã.'])
                    ]));
                });
            };
            scanBtn.addEventListener('click', lookupFn);
            codeInput.addEventListener('keydown', function (e) { if (e.key === 'Enter') lookupFn(); });
            codeInput.focus();
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
                hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý thông tin tài khoản, hạng thành viên và yêu cầu hoàn tiền.'])
            ]);
            var content = hub.el('div', { class: 'ws-card' });
            content.appendChild(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
            page.appendChild(header);
            page.appendChild(content);
            viewRoot.replaceChildren(page);

            hub.api('/api/profile').then(function (data) {
                if (!data || !data.user || !data.profile
                        || typeof data.profile.tier !== 'string'
                        || !Number.isFinite(data.profile.points)) {
                    throw new Error('Dữ liệu hồ sơ không hợp lệ.');
                }
                var user = data.user;
                var membership = data.profile;
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
                        hub.el('strong', {}, [membership.tier])
                    ]),
                    hub.el('div', { class: 'kpi' }, [
                        hub.el('span', {}, ['Điểm tích lũy']),
                        hub.el('strong', {}, [String(membership.points)])
                    ])
                ]);
                var tierProgress = hub.el('section', { class: 'profile-tier-progress' });
                var progressStart = Number(membership.tierProgressStartPoints || 0);
                var nextTierThreshold = Number(membership.nextTierMinPoints);
                var hasNextTier = typeof membership.nextTier === 'string'
                    && Number.isFinite(nextTierThreshold)
                    && nextTierThreshold > progressStart;
                var progressTitle = hub.el('h2', {}, ['Tiến độ hạng thành viên']);
                tierProgress.appendChild(progressTitle);
                if (hasNextTier) {
                    var pointsToNext = Math.max(0, nextTierThreshold - membership.points);
                    var progressPercent = Math.max(0, Math.min(100,
                        ((membership.points - progressStart) / (nextTierThreshold - progressStart)) * 100));
                    tierProgress.appendChild(hub.el('div', { class: 'profile-tier-progress-labels' }, [
                        hub.el('strong', {}, [membership.tier]),
                        hub.el('span', {}, ['Hạng tiếp theo: ' + membership.nextTier])
                    ]));
                    tierProgress.appendChild(hub.el('div', {
                        class: 'profile-tier-progress-track',
                        role: 'progressbar',
                        'aria-label': 'Tiến độ lên hạng ' + membership.nextTier,
                        'aria-valuemin': '0',
                        'aria-valuemax': '100',
                        'aria-valuenow': String(Math.round(progressPercent))
                    }, [
                        hub.el('span', { style: 'width:' + progressPercent + '%' })
                    ]));
                    tierProgress.appendChild(hub.el('p', { class: 'profile-tier-progress-note' }, [
                        'Còn ' + pointsToNext.toLocaleString('vi-VN') + ' điểm để đạt hạng '
                            + membership.nextTier + ' (' + nextTierThreshold.toLocaleString('vi-VN')
                            + ' điểm).'
                    ]));
                    tierProgress.appendChild(hub.el('p', { class: 'profile-tier-progress-footnote' }, [
                        'Điểm được cộng sau khi giao dịch đủ điều kiện thanh toán thành công.'
                    ]));
                } else if (membership.tierProgressAvailable) {
                    tierProgress.appendChild(hub.el('p', { class: 'profile-tier-progress-note is-max-tier' }, [
                        'Bạn đã đạt hạng thành viên cao nhất hiện tại.'
                    ]));
                    tierProgress.appendChild(hub.el('p', { class: 'profile-tier-progress-footnote' }, [
                        'Điểm vẫn được cộng sau khi giao dịch đủ điều kiện thanh toán thành công.'
                    ]));
                } else {
                    tierProgress.appendChild(hub.el('p', { class: 'profile-tier-progress-note' }, [
                        'Chưa có cấu hình hạng thành viên để tính tiến độ.'
                    ]));
                }
                // Ví CinemaHub đã được gỡ — thay bằng điều hướng sang trang vé (chứa nút hoàn tiền).
                var refundKpi = hub.el('div', { class: 'kpi' }, [
                    hub.el('span', {}, ['Yêu cầu hoàn tiền']),
                    hub.el('strong', {}, [hub.el('a', {
                        href: ctx + '/console?module=my-tickets',
                        class: 'ws-link'
                    }, ['Vào vé của tôi →'])])
                ]);
                summary.appendChild(refundKpi);

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

                var savedBankAccount = data.bankAccount || {};
                var bankForm = hub.el('form', { class: 'ws-form profile-bank-form' });
                var bankFields = hub.el('div', { class: 'ws-form-grid' });
                var bankName = hub.el('input', {
                    type: 'text', name: 'bankName', maxlength: '120',
                    value: savedBankAccount.bankName || ''
                });
                var bankAccountName = hub.el('input', {
                    type: 'text', name: 'bankAccountName', maxlength: '120',
                    value: savedBankAccount.bankAccountName || ''
                });
                var bankAccountNumber = hub.el('input', {
                    type: 'text', name: 'bankAccountNumber', maxlength: '40',
                    inputmode: 'numeric', pattern: '[0-9]{6,40}',
                    value: savedBankAccount.bankAccountNumber || ''
                });
                bankFields.appendChild(hub.el('label', {}, ['Tên ngân hàng', bankName]));
                bankFields.appendChild(hub.el('label', {}, ['Chủ tài khoản', bankAccountName]));
                bankFields.appendChild(hub.el('label', {}, ['Số tài khoản', bankAccountNumber]));
                bankForm.appendChild(bankFields);
                bankForm.appendChild(hub.el('p', { class: 'ws-help' }, [
                    'Lưu một tài khoản nhận hoàn tiền. Để xoá, hãy xoá cả ba trường rồi lưu.'
                ]));
                bankForm.appendChild(hub.el('div', { class: 'ws-form-actions' }, [
                    hub.el('button', { class: 'ws-btn primary', type: 'submit' },
                        ['Lưu tài khoản nhận hoàn'])
                ]));
                bankForm.addEventListener('submit', function (event) {
                    event.preventDefault();
                    var submit = bankForm.querySelector('button[type="submit"]');
                    submit.disabled = true;
                    hub.api('/api/profile', {
                        method: 'POST',
                        body: {
                            bankName: bankName.value.trim(),
                            bankAccountName: bankAccountName.value.trim(),
                            bankAccountNumber: bankAccountNumber.value.trim()
                        }
                    }).then(function (updated) {
                        var saved = updated && updated.bankAccount ? updated.bankAccount : {};
                        bankName.value = saved.bankName || '';
                        bankAccountName.value = saved.bankAccountName || '';
                        bankAccountNumber.value = saved.bankAccountNumber || '';
                        hub.notify('Đã lưu tài khoản nhận hoàn.', 'ok');
                    }).catch(notifyError).then(function () {
                        submit.disabled = false;
                    });
                });
                var bankSection = hub.el('section', { class: 'profile-bank-section' }, [
                    hub.el('h2', {}, ['Tài khoản ngân hàng nhận hoàn']),
                    hub.el('p', { class: 'muted' }, [
                        'Bạn có thể chọn tài khoản này hoặc nhập tài khoản khác khi gửi yêu cầu hoàn tiền.'
                    ]),
                    bankForm
                ]);
                content.replaceChildren(form, bankSection, summary, tierProgress);
            }).catch(function (error) {
                content.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không tải được hồ sơ']),
                    hub.el('p', {}, [error.message || 'Vui lòng thử lại.'])
                ]));
            });
        }
    });


    // ============================================================
    // Historical allocation records remain readable; they no longer limit showtime creation.
    // ============================================================
    router.register('showtime-allocation', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            if (!viewRoot) return;
            if (typeof hub.setWorkspaceHeader === 'function') {
                hub.setWorkspaceHeader('Lịch phân bổ cũ');
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
                var moviesById = new Map(movies.map(function (movie) {
                    return [String(movie.id), movie];
                }));
                var roleMeta = document.querySelector('meta[name="user-role"]');
                var userRole = roleMeta ? roleMeta.content : '';
                var isAdmin = userRole === 'ADMIN';

                var header = hub.el('div', { class: 'ws-page-header' }, [
                    hub.el('div', {}, [
                        hub.el('h1', {}, ['Lịch phân bổ cũ']),
                        hub.el('p', { class: 'ws-tone-muted' }, [
                            'Hạn mức phân bổ đã ngừng áp dụng. Branch Manager tự tạo lịch chiếu, ',
                            'không bị giới hạn bởi số lượng phân bổ trước đây.'
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
                            hub.el('th', {}, ['Giới hạn cũ']),
                            hub.el('th', {}, ['Đã tạo']),
                            hub.el('th', {}, ['Còn thiếu (tham khảo)']),
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
                            if (!isAdmin) {
                                var movie = moviesById.get(String(a.movieId));
                                var createShowtimeUrl = ctx + '/console?module=showtime&action=create'
                                    + '&movieId=' + encodeURIComponent(a.movieId)
                                    + '&branchId=' + encodeURIComponent(a.branchId);
                                if (movie && movie.releaseDate) {
                                    createShowtimeUrl += '&releaseDate='
                                        + encodeURIComponent(dateValue(movie.releaseDate));
                                }
                                actions.appendChild(hub.el('a', {
                                    class: 'ws-btn primary small',
                                    href: createShowtimeUrl
                                }, ['+ Tạo suất chiếu']));
                            }
                            row.appendChild(hub.el('td', {}, [actions]));
                            tbody.appendChild(row);
                        });
                        table.appendChild(tbody);
                        listWrap.appendChild(table);
                    }).catch(notifyError);
                }

                [statusSelect, branchSelect, movieSelect].forEach(function (el) {
                    el.addEventListener('change', load);
                });

                load();
            }).catch(notifyError);
        }
    });

})();
