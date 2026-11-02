/* eslint-disable no-undef */
/**
 * Module: movie-price — Admin chỉnh sửa khung giá vé theo (movie, branch).
 *
 * <p>Pattern mirror {@code pricing-templates.js}: dùng {@code CinemaHubFormPages}
 * đăng ký qua {@code router.register('movie-price', { list, edit })}.</p>
 *
 * <p>Chỉ Admin được truy cập màn hình chỉnh sửa khung giá.</p>
 *
 * <p>BR-PR-03 — có thể áp dụng {@link PriceTemplate} cho (movie, branch) bằng
 * panel "Áp dụng mẫu giá" trong edit form.</p>
 */
(function () {
    var ctx = (typeof window !== 'undefined' && window.CinemaHub && window.CinemaHub.ctx)
        ? window.CinemaHub.ctx : '';
    var hub = window.CinemaHub;
    var router = window.CinemaHubRouter;
    var form = window.CinemaHubForm;
    if (!hub || !router || !form || typeof form.renderFormPage !== 'function') {
        console.warn('[movie-price] Thiếu CinemaHub/router/form chưa load — bỏ qua đăng ký.');
        return;
    }

    /**
     * Escape HTML — fallback nếu {@code hub.escape} không có (một số trang SPA
     * chỉ import {@code app.js} mà chưa inject helper này).
     */
    function escapeHtml(value) {
        if (hub && typeof hub.escape === 'function') return hub.escape(value);
        if (value === null || value === undefined) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
    var escape = escapeHtml;

    function roleName() {
        var meta = document.querySelector('meta[name="user-role"]');
        return meta ? meta.content : '';
    }

    function isAdmin() { return roleName() === 'ADMIN'; }
    function canManage() { return isAdmin(); }

    function notify(text, kind) {
        if (typeof hub.notify === 'function') hub.notify(text, kind || 'ok');
    }

    function notifyError(err) {
        var msg = (err && err.message) ? err.message : 'Thao tác thất bại.';
        notify(msg, 'err');
    }

    /**
     * Điều hướng tới module movie-price với query params — vì page-router.go
     * chỉ nhận (module, action, id). Tái sử dụng helper này cho mọi lệnh goto.
     */
    function gotoMoviePrice(params) {
        var search = new URLSearchParams();
        search.set('module', 'movie-price');
        if (params && params.action) search.set('action', params.action);
        if (params && params.movieId) search.set('movieId', String(params.movieId));
        if (params && params.branchId) search.set('branchId', String(params.branchId));
        if (params && params.templateId) search.set('templateId', String(params.templateId));
        window.location.href = ctx + '/console?' + search.toString();
    }

    function priceLabel(value) {
        var num = Number(value);
        if (!isFinite(num) || num <= 0) return '—';
        return new Intl.NumberFormat('vi-VN').format(num) + ' ₫';
    }

    function getQueryParams() {
        return new URLSearchParams(window.location.search);
    }

    function parseQueryId(name) {
        var raw = getQueryParams().get(name);
        if (!raw) return null;
        var id = Number(raw);
        return isFinite(id) && id > 0 ? id : null;
    }

    function branchLabel(branch) {
        return branch && branch.name ? branch.name : ('Chi nhánh #' + (branch && branch.id));
    }

    function branchAddress(branch) {
        if (!branch) return '';
        return branch.address || branch.location || '';
    }

    function movieLabel(movie) {
        if (!movie) return '—';
        var title = movie.title || ('Phim #' + movie.id);
        if (movie.rating) title += ' [' + movie.rating + ']';
        return title;
    }

    // -----------------------------------------------------------------------
    // Data helpers
    // -----------------------------------------------------------------------

    function fetchMyBranches() {
        return hub.api('/branch/');
    }

    function fetchRules(movieId, branchId) {
        return hub.api('/movie-price-rules?movieId=' + encodeURIComponent(movieId)
            + '&branchId=' + encodeURIComponent(branchId));
    }

    function fetchTemplates() {
        return hub.api('/pricing-templates');
    }

    function fetchMovies() {
        return hub.api('/movie/').then(function (response) {
            if (Array.isArray(response)) return response;
            if (response && Array.isArray(response.items)) return response.items;
            return [];
        });
    }

    function confirmAction(message, onConfirm) {
        if (typeof window.confirm === 'function') {
            if (window.confirm(message)) onConfirm();
        } else {
            onConfirm();
        }
    }

    // -----------------------------------------------------------------------
    // List page — chọn phim, xem nhanh khung giá theo từng chi nhánh
    // -----------------------------------------------------------------------

    function renderList() {
        if (!canManage()) {
            renderForbidden();
            return;
        }

        var queryMovieId = parseQueryId('movieId');

        Promise.all([fetchMovies(), fetchMyBranches()])
            .then(function (results) {
                var movies = results[0] || [];
                var branches = results[1] || [];

                var sections = [{
                    title: 'Chọn phim để xem khung giá',
                    fields: [{
                        name: 'movieId', type: 'select',
                        label: 'Phim', required: true,
                        value: queryMovieId ? String(queryMovieId) : '',
                        options: movies.map(function (m) {
                            return { value: String(m.id), label: movieLabel(m) };
                        }),
                        hint: 'Chọn phim rồi nhấn "Mở danh sách chi nhánh" để hiển thị khung giá hiện tại.'
                    }]
                }];

                var root = form.renderFormPage({
                    breadcrumb: ['Quản lý', { label: 'Phim & Giá vé', href: ctx + '/console?module=movie-price' }],
                    title: 'Chỉnh sửa khung giá phim theo chi nhánh',
                    subtitle: 'Sửa trực tiếp khung giá hoặc áp dụng mẫu giá có sẵn cho từng chi nhánh.',
                    sections: sections,
                    submitLabel: 'Mở danh sách chi nhánh',
                    onSubmit: function (data) {
                        if (!data || !data.movieId) {
                            notify('Vui lòng chọn phim.', 'err');
                            return;
                        }
                        gotoMoviePrice({ action: 'list', movieId: data.movieId });
                    },
                    onCancel: function () { history.back(); }
                });

                var tableSection = document.createElement('section');
                tableSection.className = 'ws-form-section';
                root.appendChild(tableSection);

                if (queryMovieId) {
                    buildBranchPricingTable(tableSection, queryMovieId, branches);
                } else {
                    tableSection.appendChild(hub.el('p', { class: 'ws-field-hint' },
                        ['Chọn phim ở trên rồi nhấn "Mở danh sách chi nhánh" để xem chi tiết.']));
                }
            })
            .catch(notifyError);
    }

    function buildBranchPricingTable(host, movieId, branches) {
        host.replaceChildren();
        var heading = document.createElement('h2');
        heading.className = 'ws-form-section-title';
        heading.textContent = 'Khung giá hiện tại theo chi nhánh';
        host.appendChild(heading);

        if (!branches.length) {
            host.appendChild(hub.el('p', { class: 'ws-field-hint' },
                ['Tài khoản của bạn chưa được gán chi nhánh nào.']));
            return;
        }

        var panelWrap = document.createElement('div');
        panelWrap.className = 'ws-table-wrap';
        panelWrap.appendChild(hub.el('div', { class: 'ws-loading' }, ['Đang tải khung giá…']));
        host.appendChild(panelWrap);

        var requests = branches.map(function (branch) {
            return fetchRules(movieId, branch.id)
                .then(function (response) {
                    var rules = (response && Array.isArray(response.items)) ? response.items : [];
                    var sample = rules.slice(0, 3).map(function (r) {
                        return r.seatType + '/' + r.dayType + '/' + r.timeSlot
                            + ' · ' + priceLabel(r.price);
                    }).join('; ');
                    return { branch: branch, count: rules.length, sample: sample };
                })
                .catch(function () {
                    return { branch: branch, count: 0, sample: 'Không thể tải khung giá' };
                });
        });

        Promise.all(requests).then(function (rows) {
            var html = '<table class="ws-table"><thead><tr>'
                + '<th>Chi nhánh</th><th>Số khung giá</th><th>Mẫu khung đầu tiên</th>'
                + '<th class="ws-table-actions">Thao tác</th></tr></thead><tbody>';
            rows.forEach(function (item) {
                var editUrl = ctx + '/console?module=movie-price&action=edit&movieId='
                    + encodeURIComponent(movieId) + '&branchId=' + encodeURIComponent(item.branch.id);
                html += '<tr>'
                    + '<td><strong>' + escape(branchLabel(item.branch)) + '</strong>'
                    + '<div class="ws-muted">' + escape(branchAddress(item.branch)) + '</div></td>'
                    + '<td>' + item.count + '</td>'
                    + '<td>' + escape(item.sample || '—') + '</td>'
                    + '<td class="ws-table-actions"><a class="ws-btn primary" href="' + editUrl + '">Sửa giá</a></td>'
                    + '</tr>';
            });
            html += '</tbody></table>';
            panelWrap.innerHTML = html;
        });
    }

    // -----------------------------------------------------------------------
    // Edit form — sửa trực tiếp + apply template
    // -----------------------------------------------------------------------

    function renderEditForm() {
        if (!canManage()) {
            renderForbidden();
            return;
        }

        var movieId = parseQueryId('movieId');
        var branchId = parseQueryId('branchId');
        var templateId = parseQueryId('templateId');

        if (!movieId || !branchId) {
            notify('Thiếu movieId hoặc branchId trong URL.', 'err');
            gotoMoviePrice();
            return;
        }

        Promise.all([
            hub.api('/movie/' + movieId),
            fetchMyBranches(),
            fetchTemplates(),
            fetchRules(movieId, branchId)
        ]).then(function (results) {
            var movie = results[0];
            var branches = results[1] || [];
            var templates = results[2] || [];
            var rulesResponse = results[3];
            var existing = (rulesResponse && Array.isArray(rulesResponse.items)) ? rulesResponse.items : [];

            var branch = branches.find(function (b) { return b.id === branchId; });
            if (!branch) {
                notify('Chi nhánh không tồn tại hoặc bạn không có quyền.', 'err');
                gotoMoviePrice({ action: 'list', movieId: movieId });
                return;
            }
            renderForm(movie, branches, branch, templates, existing, templateId);
        }).catch(notifyError);
    }

    function renderForm(movie, branches, branch, templates, existing, preselectedTemplateId) {
        var sections = [{
            title: 'Thông tin chung',
            fields: [{
                name: 'branchId', type: 'select',
                label: 'Chi nhánh', required: true,
                value: branch ? String(branch.id) : '',
                options: branches.map(function (b) {
                    return { value: String(b.id), label: branchLabel(b) };
                }),
                hint: 'Đổi chi nhánh sẽ tải khung giá tương ứng.'
            }]
        }];

        var root = form.renderFormPage({
            breadcrumb: [
                'Quản lý',
                { label: 'Phim & Giá vé', href: ctx + '/console?module=movie-price' },
                { label: movieLabel(movie), href: ctx + '/console?module=movie-price&action=list&movieId=' + (movie ? movie.id : '') }
            ],
            title: 'Sửa khung giá vé · ' + movieLabel(movie),
            subtitle: 'Tại ' + branchLabel(branch) + ' · '
                + 'Lưu sẽ thay thế toàn bộ khung giá hiện tại của (phim, chi nhánh).',
            sections: sections,
            submitLabel: 'Lưu khung giá',
            onSubmit: function (data) {
                if (!data || !data.branchId) {
                    notify('Vui lòng chọn chi nhánh.', 'err');
                    return;
                }
                var newBranchId = Number(data.branchId);
                if (newBranchId !== branch.id) {
                    gotoMoviePrice({
                        action: 'edit',
                        movieId: movie.id,
                        branchId: newBranchId
                    });
                    return;
                }
                var rows = readRulesTable();
                saveRules(movie.id, branch.id, rows);
            },
            onCancel: function () {
                gotoMoviePrice({ action: 'list', movieId: movie.id });
            }
        });

        var rulesSection = document.createElement('section');
        rulesSection.className = 'ws-form-section';
        root.appendChild(rulesSection);
        var tableHolder = document.createElement('div');
        tableHolder.className = 'ws-table-wrap';
        rulesSection.appendChild(tableHolder);
        drawRulesTable(tableHolder, existing.map(toRowFromRule));

        var templateSection = document.createElement('section');
        templateSection.className = 'ws-form-section';
        root.appendChild(templateSection);
        renderTemplateApplyPanel(templateSection, movie.id, branch.id, templates, preselectedTemplateId);

        var back = hub.el('p', { class: 'ws-field-hint' }, [
            hub.el('a', {
                href: ctx + '/console?module=movie-price&action=list&movieId=' + movie.id
            }, ['← Quay lại danh sách khung giá'])
        ]);
        root.appendChild(back);
    }

    function toRowFromRule(rule) {
        return {
            seatType: rule.seatType,
            dayType: rule.dayType,
            timeSlot: rule.timeSlot,
            price: rule.price,
            ticketType: rule.ticketType || '',
            activeFrom: rule.activeFrom || '',
            activeTo: rule.activeTo || ''
        };
    }

    function drawRulesTable(host, rows) {
        function selectCell(index, name, value, options) {
            var opts = options.map(function (option) {
                var selected = String(option.value) === String(value || '') ? ' selected' : '';
                return '<option value="' + escape(option.value) + '"' + selected + '>' + escape(option.label) + '</option>';
            }).join('');
            return '<select class="ws-input" data-row="' + index + '" data-key="' + name + '">' + opts + '</select>';
        }

        function rowHtml(row, index) {
            return '<tr data-row-index="' + index + '">'
                + '<td>' + selectCell(index, 'seatType', row.seatType, [
                    { value: 'STANDARD', label: 'Thường' },
                    { value: 'VIP', label: 'VIP' },
                    { value: 'COUPLE', label: 'Đôi' }
                ]) + '</td>'
                + '<td>' + selectCell(index, 'dayType', row.dayType, [
                    { value: 'WEEKDAY', label: 'Ngày thường' },
                    { value: 'WEEKEND', label: 'Cuối tuần' },
                    { value: 'HOLIDAY', label: 'Lễ/Tết' }
                ]) + '</td>'
                + '<td>' + selectCell(index, 'timeSlot', row.timeSlot, [
                    { value: 'EARLY', label: 'Sớm' },
                    { value: 'DAY', label: 'Trưa' },
                    { value: 'LATE', label: 'Tối' }
                ]) + '</td>'
                + '<td>' + selectCell(index, 'ticketType', row.ticketType || '', [
                    { value: '', label: '— Mặc định —' },
                    { value: 'ADULT', label: 'Người lớn' },
                    { value: 'CHILD', label: 'Trẻ em' },
                    { value: 'STUDENT', label: 'HSSV' },
                    { value: 'VIP', label: 'VIP' }
                ]) + '</td>'
                + '<td><input class="ws-input" type="number" min="1000" step="1000" data-row="' + index + '" data-key="price" value="' + (row.price || '') + '"/></td>'
                + '<td><input class="ws-input" type="date" data-row="' + index + '" data-key="activeFrom" value="' + (row.activeFrom || '') + '"/></td>'
                + '<td><input class="ws-input" type="date" data-row="' + index + '" data-key="activeTo" value="' + (row.activeTo || '') + '"/></td>'
                + '<td class="ws-table-actions"><button type="button" class="ws-btn danger" data-action="remove" data-index="' + index + '">Xóa</button></td>'
                + '</tr>';
        }

        function renderTable() {
            var html = '<h2 class="ws-form-section-title">Khung giá hiện tại (' + rows.length + ')</h2>'
                + '<p class="ws-form-section-desc">Mỗi hàng là một tổ hợp (loại ghế, ngày, khung giờ, loại vé). '
                + 'Bấm "Lưu khung giá" ở cuối form để thay thế toàn bộ khung giá hiện tại.</p>'
                + '<table class="ws-table ws-table-pricing"><thead><tr>'
                + '<th>Loại ghế</th><th>Ngày</th><th>Khung giờ</th><th>Loại vé</th>'
                + '<th>Giá (VND)</th><th>Hiệu lực từ</th><th>Hiệu lực đến</th>'
                + '<th class="ws-table-actions">Thao tác</th></tr></thead><tbody>';
            rows.forEach(function (row, index) { html += rowHtml(row, index); });
            html += '</tbody></table>'
                + '<div class="ws-form-actions"><button type="button" class="ws-btn secondary" data-action="add">+ Thêm khung giá</button></div>';
            host.innerHTML = html;
        }

        renderTable();

        host.addEventListener('click', function (event) {
            var target = event.target.closest('[data-action]');
            if (!target) return;
            var action = target.getAttribute('data-action');
            if (action === 'add') {
                rows.push({
                    seatType: 'STANDARD', dayType: 'WEEKDAY', timeSlot: 'DAY',
                    ticketType: '', price: 0, activeFrom: '', activeTo: ''
                });
                renderTable();
            } else if (action === 'remove') {
                var index = Number(target.getAttribute('data-index'));
                if (index >= 0 && index < rows.length) {
                    rows.splice(index, 1);
                    renderTable();
                }
            }
        });
    }

    function readRulesTable() {
        var grouped = {};
        var scope = document.querySelector('.ws-table-pricing');
        if (!scope) return [];
        scope.querySelectorAll('[data-row]').forEach(function (el) {
            var index = Number(el.getAttribute('data-row'));
            var key = el.getAttribute('data-key');
            if (!grouped[index]) grouped[index] = {};
            grouped[index][key] = el.value;
        });
        return Object.keys(grouped).sort(function (a, b) {
            return Number(a) - Number(b);
        }).map(function (key) {
            return grouped[key];
        });
    }

    function buildSavePayload(rows, currentBranchId, currentMovieId) {
        return {
            movieId: currentMovieId,
            branchId: currentBranchId,
            rules: rows.map(function (row) {
                return {
                    seatType: row.seatType,
                    dayType: row.dayType,
                    timeSlot: row.timeSlot,
                    ticketType: row.ticketType || null,
                    price: Number(row.price) || 0,
                    activeFrom: row.activeFrom || null,
                    activeTo: row.activeTo || null
                };
            })
        };
    }

    function saveRules(currentMovieId, currentBranchId, rows) {
        var payload = buildSavePayload(rows, currentBranchId, currentMovieId);
        return hub.api('/movie-price-rules', { method: 'PUT', body: payload })
            .then(function (response) {
                notify('Đã lưu ' + (response && response.ruleCount || 0) + ' khung giá.', 'ok');
                gotoMoviePrice({ action: 'list', movieId: currentMovieId });
                return response;
            })
            .catch(notifyError);
    }

    function renderTemplateApplyPanel(host, currentMovieId, currentBranchId, templates, preselectedTemplateId) {
        host.appendChild(hub.el('h2', { class: 'ws-form-section-title' }, ['Áp dụng mẫu giá']));
        host.appendChild(hub.el('p', { class: 'ws-form-section-desc' },
            ['Chọn một mẫu giá có sẵn để copy toàn bộ khung giá sang (phim, chi nhánh) hiện tại. Khung giá cũ sẽ bị thay thế.']));

        if (!templates || !templates.length) {
            host.appendChild(hub.el('p', { class: 'ws-field-hint' }, ['Chưa có mẫu giá nào.']));
            return;
        }

        var select = hub.el('select', { class: 'ws-input', id: 'priceTemplateSelect' });
        templates.forEach(function (template) {
            var opt = document.createElement('option');
            opt.value = String(template.id);
            opt.textContent = template.name + ' (' + (template.ruleCount || 0) + ' khung)';
            if (preselectedTemplateId && Number(preselectedTemplateId) === Number(template.id)) {
                opt.selected = true;
            }
            select.appendChild(opt);
        });
        host.appendChild(select);

        var apply = hub.el('button', {
            type: 'button',
            class: 'ws-btn primary',
            onclick: function () {
                var templateId = Number(select.value);
                if (!templateId) {
                    notify('Vui lòng chọn một mẫu giá.', 'err');
                    return;
                }
                confirmAction('Áp dụng mẫu giá sẽ thay thế toàn bộ khung giá hiện tại. Tiếp tục?', function () {
                    hub.api('/movie-price-rules/apply-template', {
                        method: 'POST',
                        body: {
                            movieId: currentMovieId,
                            branchId: currentBranchId,
                            templateId: templateId
                        }
                    }).then(function (response) {
                        notify('Đã áp mẫu (' + (response && response.ruleCount || 0) + ' khung).', 'ok');
                        gotoMoviePrice({ action: 'list', movieId: currentMovieId });
                    }).catch(notifyError);
                });
            }
        }, ['Áp dụng mẫu']);
        host.appendChild(hub.el('div', { class: 'ws-form-actions' }, [apply]));
    }

    function renderForbidden() {
        var main = document.getElementById('viewRoot');
        if (!main) return;
        main.innerHTML = '<div class="ws-empty-state"><h2>Không có quyền truy cập</h2>'
            + '<p>Chỉ Admin mới có thể sửa giá vé.</p></div>';
    }

    router.register('movie-price', {
        list: renderList,
        edit: renderEditForm
    });

    window.CinemaHubMoviePrice = { renderList: renderList, renderEdit: renderEditForm };
})();
