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
                    ? 'Chọn nhân viên chi nhánh (có thể chọn nhiều).'
                    : 'Thêm nhân viên chi nhánh của bạn.'
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
        if (!roleMeta) {
            var meta = document.querySelector('meta[name="user-role"]');
            roleMeta = meta ? meta.content : '';
        }
        return (roleMeta === 'ADMIN') ? '/branch' : '/branch/my-location';
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

    function renderMovieForm(movie, genres, branches) {
        var isEdit = !!movie;
        branches = branches || [];
        var posterState = { currentUrl: (movie && movie.posterUrl) || '' };
        var selectedTemplateId = new URLSearchParams(window.location.search).get('templateId');
        var pricePicker = null;
        var priceRetryButton = null;
        var pendingMoviePricingId = null;
        var sections = [{
            title: 'Thông tin cơ bản',
            description: 'Mô tả và chi tiết phim.',
            fields: [
                { name: 'title', label: 'Tên phim', required: true, value: movie && movie.title, fullWidth: true },
                { name: 'author', label: 'Tác giả / Đạo diễn', value: movie && movie.author,
                    placeholder: 'VD: Christopher Nolan, Trần Anh Hùng…' },
                { name: 'durationMin', label: 'Thời lượng (phút)', type: 'number',
                    required: true, min: 1, max: 600, value: movie && movie.durationMin },
                { name: 'genre', label: 'Thể loại phim', type: 'checkbox-group',
                    className: 'ws-genre-field', required: true,
                    value: movie && movie.genre ? movie.genre.split(/\s*[,/;]\s*/).filter(Boolean) : [],
                    options: (genres || []).map(function (genre) {
                        return { value: genre.name, label: genre.name };
                    }),
                    hint: 'Chọn một hoặc nhiều thể loại.' },
                { name: 'rating', label: 'Giới hạn tuổi', type: 'select', required: true,
                    value: (movie && movie.rating) || 'P', options: [
                        { value: 'P', label: 'P - Mọi lứa tuổi' },
                        { value: 'C13', label: 'C13 - Trên 13 tuổi' },
                        { value: 'C16', label: 'C16 - Trên 16 tuổi' },
                        { value: 'C18', label: 'C18 - Trên 18 tuổi' }
                    ] },
                { name: 'releaseDate', label: 'Ngày phát hành', type: 'date', required: true,
                    value: dateValue(movie && movie.releaseDate) },
                { name: 'endDate', label: 'Ngày kết thúc', type: 'date', required: true,
                    value: dateValue(movie && movie.endDate) },
                { name: 'status', label: 'Trạng thái', type: 'select', required: true,
                    value: (movie && movie.status) || 'DRAFT', options: [
                        { value: 'DRAFT', label: 'Bản nháp' },
                        { value: 'PUBLISHED', label: 'Đang phát hành' },
                        { value: 'ARCHIVED', label: 'Đã lưu trữ' }
                    ] },
                { name: 'description', label: 'Mô tả', type: 'textarea',
                    value: movie && movie.description, rows: 5, fullWidth: true }
            ]
        }];

        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phim', href: ctx + '/console?module=movie' },
                isEdit ? 'Chỉnh sửa' : 'Tạo mới'],
            title: isEdit ? 'Chỉnh sửa phim' : 'Thêm phim mới',
            subtitle: isEdit ? movie.title : 'Tạo bản ghi phim mới trong hệ thống.',
            submitLabel: isEdit ? 'Lưu thay đổi' : 'Tạo phim',
            sections: sections,
            renderFooter: function (formEl) {
                var pricing = hub.el('section', { class: 'ws-form-section ws-movie-pricing-section' });
                pricing.appendChild(hub.el('h2', { class: 'ws-form-section-title' }, ['Giá vé']));
                var pricingTemplates = window.CinemaHubPricingTemplates;
                if (!pricingTemplates || typeof pricingTemplates.createBranchApplyPicker !== 'function') {
                    throw new Error('Không tải được công cụ cấu hình mẫu giá vé.');
                }
                pricePicker = pricingTemplates.createBranchApplyPicker({
                    movieId: movie && movie.id,
                    branches: branches,
                    preselectedTemplateId: selectedTemplateId
                });
                pricing.appendChild(pricePicker.element);
                priceRetryButton = hub.el('button', {
                    type: 'button',
                    class: 'ws-btn ws-btn-secondary',
                    hidden: true
                }, ['Thử lại áp dụng giá']);
                priceRetryButton.addEventListener('click', function () {
                    if (priceRetryButton.disabled || pendingMoviePricingId == null) return;
                    priceRetryButton.disabled = true;
                    pricePicker.prepareSelection().then(function (assignments) {
                        if (!assignments.length) {
                            throw new Error('Chọn ít nhất một mẫu giá cho chi nhánh.');
                        }
                        return applyMoviePriceTemplates(pendingMoviePricingId, assignments);
                    }).then(function (result) {
                        if (result.cancelled) {
                            hub.notify('Đã hủy thay thế giá. Chưa có thay đổi nào được áp dụng.', 'warn');
                            return;
                        }
                        pendingMoviePricingId = null;
                        hub.notify('Đã áp dụng mẫu giá vé: '
                            + result.branchMessages.join(' '), 'ok');
                        router.go('movie');
                    }).catch(function (error) {
                        hub.notify(error.message || 'Không thể áp dụng mẫu giá.', 'err');
                    }).then(function () {
                        priceRetryButton.disabled = false;
                    });
                });
                pricing.appendChild(priceRetryButton);
                formEl.appendChild(pricing);

                var poster = hub.el('section', { class: 'ws-form-section ws-form-poster-section' });
                poster.appendChild(hub.el('h2', { class: 'ws-form-section-title' }, ['Poster phim']));
                poster.appendChild(hub.el('p', { class: 'ws-form-section-desc' }, [
                    'Chọn ảnh poster để tải lên. Nếu không chọn ảnh mới, poster hiện tại sẽ được giữ nguyên.'
                ]));
                var grid = hub.el('div', { class: 'ws-poster-uploader' });
                var preview = hub.el('div', { class: 'ws-poster-preview' });
                function renderPreview() {
                    preview.replaceChildren();
                    if (posterState.currentUrl) {
                        preview.appendChild(hub.el('img', {
                            src: posterState.currentUrl, alt: 'Poster phim'
                        }));
                    } else {
                        preview.appendChild(hub.el('div', { class: 'ws-poster-empty' }, ['Chưa có poster']));
                    }
                }
                renderPreview();
                grid.appendChild(preview);

                var controls = hub.el('div', { class: 'ws-poster-controls' });
                var fileInput = hub.el('input', {
                    type: 'file',
                    accept: 'image/jpeg,image/jpg,image/png,image/webp',
                    id: 'moviePosterFile'
                });
                controls.appendChild(hub.el('label', {
                    for: 'moviePosterFile', class: 'ws-btn secondary'
                }, ['Chọn ảnh mới']));
                controls.appendChild(fileInput);
                controls.appendChild(hub.el('p', { class: 'ws-field-hint' }, [
                    'Hỗ trợ JPEG/PNG/WEBP, tối đa 5MB.'
                ]));
                var status = hub.el('div', { class: 'ws-poster-status' });
                controls.appendChild(status);
                var hidden = hub.el('input', {
                    type: 'hidden', name: 'posterUrl', value: posterState.currentUrl
                });
                controls.appendChild(hidden);
                fileInput.addEventListener('change', function () {
                    var file = fileInput.files && fileInput.files[0];
                    if (!file) return;
                    if (file.size > 5 * 1024 * 1024) {
                        status.textContent = 'File vượt quá 5MB.';
                        status.className = 'ws-poster-status error';
                        fileInput.value = '';
                        return;
                    }
                    status.textContent = 'Đang tải poster lên…';
                    status.className = 'ws-poster-status uploading';
                    var previousUrl = posterState.currentUrl;
                    hub.uploadFile(file, 'file').then(function (result) {
                        posterState.currentUrl = result.url || '';
                        hidden.value = posterState.currentUrl;
                        renderPreview();
                        status.textContent = 'Đã tải poster lên.';
                        status.className = 'ws-poster-status success';
                    }).catch(function (error) {
                        posterState.currentUrl = previousUrl;
                        hidden.value = previousUrl;
                        fileInput.value = '';
                        renderPreview();
                        status.textContent = 'Tải poster thất bại: ' + (error.message || 'Lỗi không xác định.');
                        status.className = 'ws-poster-status error';
                    });
                });
                grid.appendChild(controls);
                poster.appendChild(grid);
                formEl.appendChild(poster);
            },
            onSubmit: function (data) {
                if (!isEdit && pendingMoviePricingId != null) {
                    throw new Error('Phim đã được lưu. Dùng nút "Thử lại áp dụng giá" để hoàn tất cấu hình.');
                }
                var assignmentsPromise = pricePicker
                    ? pricePicker.prepareSelection()
                    : Promise.resolve([]);
                return assignmentsPromise.then(function (assignments) {
                    if (selectedTemplateId && !assignments.length) {
                        throw new Error('Chọn ít nhất một chi nhánh để áp dụng mẫu giá.');
                    }
                    return hub.api(isEdit ? '/movie/' + movie.id : '/movie', {
                        method: isEdit ? 'PUT' : 'POST',
                        body: data
                    }).then(function (savedMovie) {
                        if (!assignments.length) {
                            router.go('movie');
                            return;
                        }
                        pendingMoviePricingId = savedMovie.id;
                        return applyMoviePriceTemplates(savedMovie.id, assignments).then(function (result) {
                            if (result.cancelled) {
                                pendingMoviePricingId = null;
                                hub.notify('Đã lưu phim; giá vé chưa được thay đổi.', 'warn');
                            } else {
                                pendingMoviePricingId = null;
                                hub.notify('Đã lưu phim và áp dụng mẫu giá: '
                                    + result.branchMessages.join(' '), 'ok');
                            }
                            router.go('movie');
                        }, function (error) {
                            if (priceRetryButton) priceRetryButton.hidden = false;
                            throw new Error('Đã lưu phim nhưng không áp dụng được mẫu giá. '
                                + 'Dùng nút "Thử lại áp dụng giá" để tiếp tục. '
                                + (error.message || 'Lỗi không xác định.'));
                        });
                    });
                });
            },
            onCancel: function () { router.go('movie'); }
        });
    }

    function pricingTemplateRequest(path, payload) {
        return hub.api(path, {
            method: 'POST',
            body: JSON.stringify(payload),
            contentType: 'application/json;charset=UTF-8'
        });
    }

    function applyMoviePriceTemplates(movieId, assignments) {
        // Phân tách: assignment có templateId → qua apply-template,
        // assignment có rules (nhập thủ công) → qua apply-rules.
        var templateAssignments = [];
        var manualAssignments = [];
        assignments.forEach(function (a) {
            if (a.rules && a.rules.length) {
                manualAssignments.push(a);
            } else {
                templateAssignments.push(a);
            }
        });

        // Gộp kết quả từ cả hai nhóm.
        function processOutcomes(outcomes, branchMessages) {
            var hasFailures = false;
            outcomes.forEach(function (outcome) {
                var assignment = outcome.assignment;
                if (outcome.error) {
                    hasFailures = true;
                    (assignment.branchIds || []).forEach(function (branchId) {
                        var target = (assignment.branches || []).find(function (b) {
                            return String(b.id) === String(branchId);
                        });
                        var branchName = target && target.name
                            ? target.name : ('#' + branchId);
                        branchMessages.push('Chi nhánh ' + branchName + ': '
                            + (outcome.error.message || 'Không áp dụng được giá.'));
                    });
                    return;
                }
                var reported = {};
                (outcome.branches || []).forEach(function (branch) {
                    reported[String(branch.branchId)] = true;
                    var target = (assignment.branches || []).find(function (b) {
                        return String(b.id) === String(branch.branchId);
                    });
                    var branchName = target && target.name ? target.name : ('#' + branch.branchId);
                    if (branch.status !== 'APPLIED') {
                        hasFailures = true;
                        branchMessages.push('Chi nhánh ' + branchName + ': '
                            + (branch.message || 'Không áp dụng được giá.'));
                    } else {
                        branchMessages.push('Chi nhánh ' + branchName + ': đã áp dụng '
                            + Number(branch.appliedRuleCount || 0) + ' khung giá.');
                    }
                });
                (assignment.branchIds || []).forEach(function (branchId) {
                    if (reported[String(branchId)]) return;
                    hasFailures = true;
                    var target = (assignment.branches || []).find(function (b) {
                        return String(b.id) === String(branchId);
                    });
                    var branchName = target && target.name ? target.name : ('#' + branchId);
                    branchMessages.push('Chi nhánh ' + branchName + ': không nhận được kết quả.');
                });
            });
            return hasFailures;
        }

        // 1. Xử lý mẫu giá (có preview trước).
        var templateOutcomes = [];
        var step1 = templateAssignments.length
            ? Promise.all(templateAssignments.map(function (assignment) {
                return pricingTemplateRequest('/pricing-templates/'
                    + encodeURIComponent(assignment.templateId) + '/preview', {
                        movieId: movieId,
                        branchIds: assignment.branchIds
                    }).then(function (preview) {
                        if (!preview || !Array.isArray(preview.branches) || !preview.branches.length) {
                            throw new Error('Không tạo được bản xem trước giá cho chi nhánh.');
                        }
                        return { assignment: assignment, preview: preview };
                    });
            })).then(function (previews) {
                var existingRuleCount = previews.reduce(function (count, item) {
                    return count + item.preview.branches.reduce(function (bc, branch) {
                        return bc + Number(branch.existingRuleCount || 0);
                    }, 0);
                }, 0);
                var replaceExisting = existingRuleCount > 0;
                if (replaceExisting && !window.confirm(
                    'Phim đã có ' + existingRuleCount + ' khung giá tại các chi nhánh đã chọn. Thay thế các khung giá này?'
                )) {
                    throw { cancelled: true };
                }
                return Promise.all(previews.map(function (item) {
                    var expectedState = {};
                    item.preview.branches.forEach(function (branch) {
                        expectedState[String(branch.branchId)] = branch.stateToken;
                    });
                    return pricingTemplateRequest('/pricing-templates/'
                        + encodeURIComponent(item.assignment.templateId) + '/apply', {
                            movieId: movieId,
                            branchIds: item.assignment.branchIds,
                            expectedState: expectedState,
                            replaceExisting: replaceExisting
                        }).then(function (result) {
                            return { assignment: item.assignment, branches: result && result.branches };
                        }, function (error) {
                            return { assignment: item.assignment, error: error };
                        });
                }));
            })
            : Promise.resolve([]);

        // 2. Xử lý nhập thủ công (không preview, apply trực tiếp).
        var step2 = manualAssignments.length
            ? Promise.resolve().then(function () {
                if (manualAssignments.length > 1
                        && !window.confirm('Sẽ áp dụng giá thủ công cho ' + manualAssignments.length
                        + ' nhóm chi nhánh. Tiếp tục?')) {
                    throw { cancelled: true };
                }
                // Mỗi assignment = một branch cùng rules (vì picker nhập chung 1 bộ rules).
                // Nhưng cấu trúc assignment hỗ trợ nhiều branch cùng template nên
                // ta gom chung rules cho mọi branch trong manualAssignments.
                return Promise.all(manualAssignments.map(function (assignment) {
                    var rules = assignment.rules;
                    var expectedState = {}; // manual không có token → luôn thay thế
                    var replaceExisting = true;
                    if (!window.confirm(
                        'Áp dụng ' + rules.length + ' khung giá thủ công cho '
                        + assignment.branchIds.length + ' chi nhánh? Giá hiện có sẽ bị thay thế.'
                    )) {
                        throw { cancelled: true };
                    }
                    return hub.api('/pricing-templates/apply-rules', {
                        method: 'POST',
                        body: JSON.stringify({
                            movieId: movieId,
                            branchIds: assignment.branchIds,
                            rules: rules,
                            expectedState: expectedState,
                            replaceExisting: replaceExisting
                        }),
                        contentType: 'application/json;charset=UTF-8'
                    }).then(function (result) {
                        return { assignment: assignment, branches: result && result.branches };
                    }, function (error) {
                        return { assignment: assignment, error: error };
                    });
                }));
            })
            : Promise.resolve([]);

        return Promise.all([step1, step2]).then(function (results) {
            var outcomes = results[0].concat(results[1]);
            var branchMessages = [];
            var hasFailures = processOutcomes(outcomes, branchMessages);
            if (hasFailures) {
                throw new Error('Kết quả áp dụng giá theo chi nhánh: ' + branchMessages.join(' '));
            }
            return { cancelled: false, branchMessages: branchMessages };
        });
    }

    function renderManagerMoviePricingForm(movie) {
        var picker;
        var selectedTemplateId = new URLSearchParams(window.location.search).get('templateId');
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Phim', href: ctx + '/console?module=movie' }, 'Giá vé'],
            title: 'Cấu hình giá vé phim',
            subtitle: movie.title || '',
            submitLabel: 'Áp dụng giá',
            sections: [{
                title: 'Giá theo chi nhánh',
                description: 'Chọn mẫu giá cho chi nhánh được phân công. Giá hiện có sẽ chỉ bị thay khi xác nhận.',
                // fields rỗng — giao diện chọn mẫu nằm trong renderFooter
                // để form-page không gọi section.fields.forEach trên undefined.
                fields: []
            }],
            renderFooter: function (formEl) {
                var templates = window.CinemaHubPricingTemplates;
                if (!templates || typeof templates.createBranchApplyPicker !== 'function') {
                    throw new Error('Không tải được công cụ cấu hình mẫu giá vé.');
                }
                picker = templates.createBranchApplyPicker({
                    movieId: movie.id,
                    preselectedTemplateId: selectedTemplateId || ''
                });
                formEl.appendChild(picker.element);
            },
            onSubmit: function () {
                if (!picker) throw new Error('Chưa tải được cấu hình giá vé.');
                return picker.prepareSelection().then(function (assignments) {
                    if (!assignments.length) {
                        if (!picker.hasUnselectedBranches()) {
                            hub.notify('Giá hiện tại được giữ nguyên, không có thay đổi nào.', 'ok');
                            router.go('movie');
                            return null;
                        }
                        throw new Error('Chọn ít nhất một mẫu giá cho chi nhánh.');
                    }
                    return applyMoviePriceTemplates(movie.id, assignments);
                }).then(function (result) {
                    if (!result) return;
                    if (result.cancelled) {
                        throw new Error('Đã hủy thay thế giá. Chưa có thay đổi nào được áp dụng.');
                    }
                    hub.notify('Đã cập nhật giá vé: '
                        + result.branchMessages.join(' '), 'ok');
                    router.go('movie');
                });
            },
            onCancel: function () { router.go('movie'); }
        });
    }

    router.register('movie', {
        list: function () {
            var roleMeta = document.querySelector('meta[name="user-role"]');
            var isAdmin = !!roleMeta && roleMeta.content === 'ADMIN';
            var isManager = !!roleMeta && roleMeta.content === 'BRANCH_MANAGER';
            var selectedTemplateId = new URLSearchParams(window.location.search).get('templateId');
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
                    if (!isAdmin && !isManager) return [];
                    if (isManager && row.status !== 'PUBLISHED') return [];
                    if (isAdmin) {
                    var arr = [{ label: 'Sửa', class: 'secondary', href: ctx + '/console?module=movie&action=edit&id=' + row.id }];
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
                    var priceEditUrl = ctx + '/console?module=movie&action=edit&id='
                        + encodeURIComponent(row.id);
                    if (selectedTemplateId) {
                        priceEditUrl += '&templateId=' + encodeURIComponent(selectedTemplateId);
                    }
                    return [{
                        label: 'Sửa giá vé',
                        class: 'secondary',
                        href: priceEditUrl
                    }, {
                        label: 'Tạo suất chiếu',
                        class: 'primary',
                        href: ctx + '/console?module=showtime&action=create&movieId='
                            + encodeURIComponent(row.id) + '&releaseDate='
                            + encodeURIComponent(dateValue(row.releaseDate))
                    }];
                }
            });
        },
        create: function () {
            var roleMeta = document.querySelector('meta[name="user-role"]');
            if (!roleMeta || roleMeta.content !== 'ADMIN') {
                hub.notify('Chỉ Admin mới có thể tạo phim.', 'err');
                router.go('movie');
                return;
            }
            Promise.all([hub.api('/movie/genres'), hub.api('/branch/')]).then(function (data) {
                renderMovieForm(null, data[0], data[1]);
            }).catch(notifyError);
        },
        edit: function (id) {
            var roleMeta = document.querySelector('meta[name="user-role"]');
            var isManager = !!roleMeta && roleMeta.content === 'BRANCH_MANAGER';
            if (isManager) {
                hub.api('/movie/' + id)
                    .then(renderManagerMoviePricingForm)
                    .catch(notifyError);
                return;
            }
            Promise.all([
                hub.api('/movie/' + id),
                hub.api('/movie/genres'),
                hub.api('/branch/')
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
            hiddenLayout.dispatchEvent(new Event('change'));
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
                        button.textContent = restored ? 'Đã chọn mở lại'
                            : (inBounds ? 'Mở lại ghế' : 'Ngoài kích thước phòng');
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
                        archivedSeatSection.appendChild(button);
                    });
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
    //  BOOKING
    // ================================================================
    router.register('booking', {
        list: function () {
            var roleMeta = (document.querySelector('meta[name="user-role"]') || {}).content;
            if (roleMeta === 'ADMIN' || roleMeta === 'BRANCH_MANAGER') {
                list.render({
                    title: 'Đặt vé & vé bán',
                    subtitle: 'Danh sách vé đã bán trong hệ thống (Admin/Manager).',
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
                    var table = hub.el('table', { class: 'ws-table' });
                    table.innerHTML = '<thead><tr><th>Nội dung</th><th>Thời điểm</th><th>Trạng thái</th><th></th></tr></thead>';
                    var tbody = hub.el('tbody');
                    list.forEach(function (n) {
                        var tr = hub.el('tr');
                            var action = hub.refundRequestAction(n);
                            var body = action ? action.body : (n.body || n.title || '—');
                            body = body.replace(/\s*·\s*\/console\?[^\s]*\s*$/, '');
                            var contentCell = hub.el('td');
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
                                }, [action.label]));
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

    router.register('review-branch', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });

            // Page header
            page.appendChild(hub.el('div', { class: 'ws-page-header' }, [
                hub.el('span', { class: 'ws-eyebrow' }, ['ĐÁNH GIÁ PHIM CỦA KHÁCH']),
                hub.el('h1', { class: 'ws-page-title' }, ['Đánh giá phim theo rạp']),
                hub.el('p', { class: 'ws-page-subtitle' }, [
                    'Xem nhận xét và số sao khách hàng đã gửi về phim tại chi nhánh.'
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
    //  CUSTOMER PROFILE
    // ================================================================
    router.register('profile', {
        list: function () {
            var viewRoot = document.getElementById('viewRoot');
            var page = hub.el('div', { class: 'ws-page' });
            var header = hub.el('div', { class: 'ws-page-header' }, [
                hub.el('h1', { class: 'ws-page-title' }, ['Hồ sơ cá nhân']),
                hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý thông tin tài khoản và yêu cầu hoàn tiền.'])
            ]);
            var content = hub.el('div', { class: 'ws-card' });
            content.appendChild(hub.el('div', { class: 'ws-list-loading' }, [hub.el('div', { class: 'ws-spinner' })]));
            page.appendChild(header);
            page.appendChild(content);
            viewRoot.replaceChildren(page);

            hub.api('/api/profile').then(function (data) {
                if (!data || !data.user) {
                    throw new Error('Dữ liệu hồ sơ không hợp lệ.');
                }
                var user = data.user;
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
                        hub.el('span', {}, ['Yêu cầu hoàn tiền']),
                        hub.el('strong', {}, [hub.el('a', {
                            href: ctx + '/console?module=my-tickets',
                            class: 'ws-link'
                        }, ['Vào vé của tôi →'])])
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
                content.replaceChildren(form, bankSection, summary);
            }).catch(function (error) {
                content.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không tải được hồ sơ']),
                    hub.el('p', {}, [error.message || 'Vui lòng thử lại.'])
                ]));
            });
        }
    });

})();
