/**
 * Reusable ticket-price template library and movie-form picker.
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var router = window.CinemaHubRouter;
    if (!hub || !router) return;

    var el = hub.el;
    var ctx = (document.querySelector('meta[name="ctx"]') || {}).content || '';
    var role = (document.querySelector('meta[name="user-role"]') || {}).content || '';
    var userId = (document.querySelector('meta[name="user-id"]') || {}).content || '';
    var canManageTemplates = role === 'ADMIN' || role === 'BRANCH_MANAGER';

    /** Admin sửa/xóa mọi mẫu; Manager chỉ sửa/xóa mẫu do chính mình tạo. */
    function canEditTemplate(template) {
        if (role === 'ADMIN') return true;
        if (role !== 'BRANCH_MANAGER') return false;
        if (!userId) return false;
        return template && template.createdBy != null
            && String(template.createdBy) === String(userId);
    }
    var SEATS = [
        { value: 'STANDARD', label: 'Ghế thường' },
        { value: 'VIP', label: 'Ghế VIP' },
        { value: 'COUPLE', label: 'Ghế đôi' }
    ];
    var DAYS = [
        { value: 'WEEKDAY', label: 'Ngày thường' },
        { value: 'WEEKEND', label: 'Cuối tuần' },
        { value: 'HOLIDAY', label: 'Ngày lễ' }
    ];
    var SLOTS = [
        { value: 'EARLY', label: 'Trước 10:00' },
        { value: 'DAY', label: '10:00-21:59' },
        { value: 'LATE', label: 'Từ 22:00' }
    ];
    var TICKETS = [
        { value: '', label: 'Mọi loại vé' },
        { value: 'ADULT', label: 'Người lớn' },
        { value: 'CHILD', label: 'Trẻ em' },
        { value: 'STUDENT', label: 'Học sinh / sinh viên' },
        { value: 'VIP', label: 'Vé VIP' }
    ];

    function requestJson(url, method, body) {
        return hub.api(url, {
            method: method,
            body: JSON.stringify(body),
            contentType: 'application/json;charset=UTF-8'
        });
    }

    function labelFor(options, value) {
        var found = options.find(function (option) { return option.value === value; });
        return found ? found.label : value;
    }

    function setError(node, message) {
        node.textContent = message || '';
        node.hidden = !message;
    }

    function pageHeader(title, subtitle, action) {
        var header = el('div', { class: 'ws-page-header ws-template-header' });
        var copy = el('div');
        copy.appendChild(el('h1', { class: 'ws-page-title' }, [title]));
        copy.appendChild(el('p', { class: 'ws-page-subtitle' }, [subtitle]));
        header.appendChild(copy);
        if (action) header.appendChild(action);
        return header;
    }

    function renderLibrary() {
        var root = document.getElementById('viewRoot');
        root.setAttribute('aria-busy', 'true');
        root.replaceChildren(el('div', { class: 'loading-state', role: 'status' }, [
            el('span', { class: 'spinner', 'aria-hidden': 'true' }),
            el('span', {}, ['Đang tải mẫu giá vé…'])
        ]));
        hub.api('/pricing-templates').then(function (templates) {
            if (!Array.isArray(templates)) {
                throw new Error('Phản hồi danh sách mẫu giá không hợp lệ. Vui lòng tải lại trang.');
            }
            root.removeAttribute('aria-busy');
            var createLink = canManageTemplates ? el('a', {
                class: 'ws-btn ws-btn-primary',
                href: ctx + '/console?module=pricing-templates&action=create'
            }, ['Tạo mẫu giá']) : null;
            var page = pageHeader('Mẫu giá vé',
                'Lưu và tái sử dụng bộ khung giá độc lập giữa các phim, chi nhánh.', createLink);
            var note = el('div', { class: 'ws-template-note' }, [
                canManageTemplates
                    ? 'Mẫu nhập tay cần đủ 27 khung giá cơ bản. Bạn cũng có thể sao chép bộ giá từ một phim cũ.'
                    : 'Bạn có thể xem mẫu và chọn áp dụng trong biểu mẫu tạo hoặc chỉnh sửa phim.'
            ]);
            var list = el('div', { class: 'ws-template-grid' });
            (templates || []).forEach(function (template) {
                var card = el('article', { class: 'ws-template-card' });
                var heading = el('div', { class: 'ws-template-card-heading' });
                heading.appendChild(el('div', {}, [
                    el('h2', {}, [template.name || 'Mẫu giá']),
                    el('span', { class: 'ws-template-source' }, [
                        template.sourceType === 'CLONED' ? 'Sao chép từ phim' : 'Nhập thủ công'
                    ])
                ]));
                heading.appendChild(el('span', { class: 'ws-template-count' }, [
                    String(template.ruleCount || 0) + ' khung giá'
                ]));
                card.appendChild(heading);
                card.appendChild(el('p', { class: 'ws-template-description' }, [
                    template.description || 'Chưa có mô tả.'
                ]));
                var actions = el('div', { class: 'ws-template-actions' });
                if (canEditTemplate(template)) {
                    actions.appendChild(el('a', {
                        class: 'ws-btn ws-btn-secondary ws-btn-sm',
                        href: ctx + '/console?module=pricing-templates&action=edit&id='
                            + encodeURIComponent(template.id)
                    }, ['Chỉnh sửa']));
                    var remove = el('button', {
                        type: 'button',
                        class: 'ws-btn ws-btn-danger ws-btn-sm'
                    }, ['Xóa']);
                    remove.addEventListener('click', function () {
                        if (!window.confirm('Xóa mẫu "' + template.name
                                + '"? Giá đã áp dụng cho phim sẽ được giữ nguyên.')) return;
                        hub.api('/pricing-templates/' + encodeURIComponent(template.id), {
                            method: 'DELETE'
                        }).then(renderLibrary).catch(function (error) {
                            hub.notify(error.message || 'Không xóa được mẫu giá.', 'err');
                        });
                    });
                    actions.appendChild(remove);
                    actions.appendChild(el('a', {
                        class: 'ws-btn ws-btn-primary ws-btn-sm',
                        href: ctx + '/console?module=movie&action=create&templateId='
                            + encodeURIComponent(template.id)
                    }, ['Áp dụng cho phim']));
                } else {
                    actions.appendChild(el('a', {
                        class: 'ws-btn ws-btn-secondary ws-btn-sm',
                        href: ctx + '/console?module=movie&action=create&templateId='
                            + encodeURIComponent(template.id)
                    }, ['Áp dụng cho phim']));
                }
                card.appendChild(actions);
                list.appendChild(card);
            });
            if (!templates || !templates.length) {
                list.appendChild(el('div', { class: 'ws-template-empty' }, [
                    el('h2', {}, ['Chưa có mẫu giá vé']),
                    el('p', {}, [canManageTemplates
                        ? 'Tạo mẫu thủ công hoặc sao chép từ một phim hiện có để bắt đầu.'
                        : 'Chưa có mẫu giá nào.'])
                ]));
            }
            root.replaceChildren(page, note, list);
        }).catch(function (error) {
            root.removeAttribute('aria-busy');
            var retry = el('button', { type: 'button', class: 'ws-btn ws-btn-secondary' }, ['Thử lại']);
            retry.addEventListener('click', renderLibrary);
            root.replaceChildren(pageHeader('Mẫu giá vé',
                'Không thể tải thư viện mẫu giá.', retry),
            el('div', { class: 'ws-template-error', role: 'alert' }, [
                error.message || 'Vui lòng thử lại sau.'
            ]));
        });
    }

    function makeSelect(options, value, ariaLabel) {
        var select = el('select', { class: 'ws-input', 'aria-label': ariaLabel || '' });
        options.forEach(function (option) {
            select.appendChild(el('option', {
                value: option.value,
                selected: option.value === value
            }, [option.label]));
        });
        return select;
    }

    function renderAdvancedRow(container, rule, removable) {
        rule = rule || {};
        var row = el('div', { class: 'ws-template-advanced-row' });
        var seat = makeSelect(SEATS, rule.seatType || 'STANDARD', 'Loại ghế');
        var day = makeSelect(DAYS, rule.dayType || 'WEEKDAY', 'Loại ngày');
        var slot = makeSelect(SLOTS, rule.timeSlot || 'EARLY', 'Khung giờ');
        var ticket = makeSelect(TICKETS, rule.ticketType || '', 'Loại vé');
        var price = el('input', {
            class: 'ws-input',
            type: 'number',
            min: '1000',
            step: '1000',
            value: rule.price == null ? '' : String(rule.price),
            placeholder: 'Giá VND',
            'aria-label': 'Giá VND'
        });
        var from = el('input', {
            class: 'ws-input',
            type: 'date',
            value: rule.activeFrom || '',
            'aria-label': 'Ngày bắt đầu'
        });
        var to = el('input', {
            class: 'ws-input',
            type: 'date',
            value: rule.activeTo || '',
            'aria-label': 'Ngày kết thúc'
        });
        row.appendChild(seat);
        row.appendChild(day);
        row.appendChild(slot);
        row.appendChild(ticket);
        row.appendChild(price);
        row.appendChild(from);
        row.appendChild(to);
        if (removable) {
            var remove = el('button', {
                type: 'button', class: 'ws-btn ws-btn-danger ws-btn-sm',
                'aria-label': 'Xóa khung giá'
            }, ['×']);
            remove.addEventListener('click', function () { row.remove(); });
            row.appendChild(remove);
        }
        row.readRule = function (index) {
            var value = Number(price.value);
            if (!Number.isSafeInteger(value) || value < 1000) {
                throw new Error('Khung giá bổ sung ' + index + ' phải có giá từ 1.000 VND.');
            }
            if ((from.value && !to.value) || (!from.value && to.value)
                    || (from.value && to.value && to.value < from.value)) {
                throw new Error('Khoảng ngày của khung giá bổ sung ' + index + ' không hợp lệ.');
            }
            return {
                seatType: seat.value,
                dayType: day.value,
                timeSlot: slot.value,
                ticketType: ticket.value || null,
                price: value,
                activeFrom: from.value || null,
                activeTo: to.value || null
            };
        };
        container.appendChild(row);
        return row;
    }

    function createEditor(template) {
        var editing = !!template;
        var sourceType = template && template.sourceType || 'MANUAL';
        var form = el('form', { class: 'ws-template-editor', novalidate: true });
        var name = el('input', {
            class: 'ws-input',
            type: 'text',
            maxlength: '120',
            required: true,
            value: template && template.name || ''
        });
        var description = el('textarea', {
            class: 'ws-input',
            rows: '3',
            maxlength: '500',
            placeholder: 'Mô tả ngắn về mục đích hoặc phạm vi áp dụng'
        });
        description.value = template && template.description || '';
        var error = el('div', { class: 'ws-template-error', role: 'alert', hidden: true });
        var formGrid = el('div', { class: 'ws-template-meta-grid' });
        var nameField = el('label', { class: 'ws-template-field' }, [
            el('span', {}, ['Tên mẫu *']), name
        ]);
        var descriptionField = el('label', { class: 'ws-template-field' }, [
            el('span', {}, ['Mô tả']), description
        ]);
        formGrid.appendChild(nameField);
        formGrid.appendChild(descriptionField);
        form.appendChild(formGrid);

        var manualPanel = el('section', { class: 'ws-template-editor-panel' });
        var sourcePanel = el('section', { class: 'ws-template-editor-panel', hidden: true });
        var matrixInputs = [];
        var advanced = el('div', { class: 'ws-template-advanced-list' });
        var sourceRules = [];
        var preview = el('div', { class: 'ws-template-preview', role: 'status' });
        var selectedSourceMovie = null;
        var selectedSourceBranch = null;

        if (!editing) {
            var mode = el('div', { class: 'ws-template-mode' });
            var manualRadio = el('input', { type: 'radio', name: 'templateMode', value: 'manual', checked: true });
            var cloneRadio = el('input', { type: 'radio', name: 'templateMode', value: 'clone' });
            mode.appendChild(el('label', {}, [manualRadio, ' Nhập bộ giá']));
            mode.appendChild(el('label', {}, [cloneRadio, ' Sao chép từ phim cũ']));
            manualRadio.addEventListener('change', function () {
                manualPanel.hidden = !manualRadio.checked;
                sourcePanel.hidden = manualRadio.checked;
            });
            cloneRadio.addEventListener('change', function () {
                manualPanel.hidden = cloneRadio.checked;
                sourcePanel.hidden = !cloneRadio.checked;
            });
            form.appendChild(mode);
        }

        if (sourceType === 'CLONED' && editing) {
            manualPanel.appendChild(el('p', { class: 'ws-template-note' }, [
                'Mẫu này được sao chép từ phim. Các dòng bên dưới giữ nguyên đúng bộ giá nguồn; có thể chỉnh hoặc thêm dòng.'
            ]));
            (template.rules || []).forEach(function (rule) {
                renderAdvancedRow(advanced, rule, true);
            });
        } else {
            manualPanel.appendChild(el('p', { class: 'ws-template-note' }, [
                'Điền đủ 27 tổ hợp cơ bản. Khung giá bổ sung có thể gắn với loại vé hoặc khoảng ngày hiệu lực.'
            ]));
            var existingBase = new Map();
            (template && template.rules || []).forEach(function (rule) {
                if (!rule.ticketType && !rule.activeFrom && !rule.activeTo) {
                    existingBase.set(rule.seatType + '|' + rule.dayType + '|' + rule.timeSlot, rule);
                } else {
                    renderAdvancedRow(advanced, rule, true);
                }
            });
            var matrix = el('div', { class: 'ws-template-matrix' });
            DAYS.forEach(function (day) {
                SLOTS.forEach(function (slot) {
                    var group = el('section', { class: 'ws-template-matrix-group' });
                    group.appendChild(el('h3', {}, [day.label + ' · ' + slot.label]));
                    var grid = el('div', { class: 'ws-template-matrix-grid' });
                    SEATS.forEach(function (seat) {
                        var key = seat.value + '|' + day.value + '|' + slot.value;
                        var oldRule = existingBase.get(key);
                        var input = el('input', {
                            class: 'ws-input',
                            type: 'number',
                            min: '1000',
                            step: '1000',
                            required: true,
                            value: oldRule ? String(oldRule.price) : '',
                            'aria-label': seat.label + ' ' + day.label + ' ' + slot.label
                        });
                        var field = el('label', { class: 'ws-template-price-field' }, [
                            el('span', {}, [seat.label]), input
                        ]);
                        grid.appendChild(field);
                        matrixInputs.push({
                            input: input, seatType: seat.value,
                            dayType: day.value, timeSlot: slot.value
                        });
                    });
                    group.appendChild(grid);
                    matrix.appendChild(group);
                });
            });
            manualPanel.appendChild(matrix);
        }
        var advancedHeader = el('div', { class: 'ws-template-advanced-header' }, [
            el('div', {}, [
                el('h3', {}, ['Khung giá bổ sung']),
                el('p', {}, ['Dùng cho loại vé riêng hoặc chương trình có ngày hiệu lực.'])
            ])
        ]);
        var addRule = el('button', {
            type: 'button', class: 'ws-btn ws-btn-secondary ws-btn-sm'
        }, ['Thêm khung giá']);
        addRule.addEventListener('click', function () { renderAdvancedRow(advanced, null, true); });
        advancedHeader.appendChild(addRule);
        manualPanel.appendChild(advancedHeader);
        manualPanel.appendChild(advanced);
        if (editing || canManageTemplates) form.appendChild(manualPanel);

        if (!editing) {
            var movieSelect = makeSelect([], '', 'Phim nguồn');
            var branchSelect = makeSelect([], '', 'Chi nhánh nguồn');
            var sourceOptions = el('div', { class: 'ws-template-source-options' });
            sourceOptions.appendChild(el('label', { class: 'ws-template-field' }, [
                el('span', {}, ['Phim nguồn']), movieSelect
            ]));
            sourceOptions.appendChild(el('label', { class: 'ws-template-field' }, [
                el('span', {}, ['Chi nhánh nguồn']), branchSelect
            ]));
            sourcePanel.appendChild(el('p', { class: 'ws-template-note' }, [
                'Chọn một phim và một chi nhánh. Giá nguồn không bị thay đổi; mẫu sẽ lưu một bản sao độc lập.'
            ]));
            sourcePanel.appendChild(sourceOptions);
            sourcePanel.appendChild(preview);
            form.appendChild(sourcePanel);

            Promise.all([
                hub.api('/movie/'),
                role === 'BRANCH_MANAGER' ? hub.api('/branch/my-location') : hub.api('/branch/')
            ]).then(function (data) {
                var movies = Array.isArray(data[0]) ? data[0] : [];
                var branches = Array.isArray(data[1]) ? data[1] : [];
                movieSelect.replaceChildren(el('option', { value: '' }, ['— Chọn phim —']));
                branches = branches.filter(function (branch) {
                    return branch.status == null || branch.status === 'ACTIVE';
                });
                branchSelect.replaceChildren(el('option', { value: '' }, ['— Chọn chi nhánh —']));
                movies.forEach(function (movie) {
                    movieSelect.appendChild(el('option', { value: String(movie.id) }, [
                        movie.title || ('Phim #' + movie.id)
                    ]));
                });
                branches.forEach(function (branch) {
                    branchSelect.appendChild(el('option', { value: String(branch.id) }, [
                        branch.name || ('Chi nhánh #' + branch.id)
                    ]));
                });
            }).catch(function (requestError) {
                setError(error, requestError.message || 'Không tải được danh sách phim, chi nhánh.');
            });

            function loadSourcePreview() {
                var movieId = movieSelect.value;
                var branchId = branchSelect.value;
                selectedSourceMovie = movieId ? Number(movieId) : null;
                selectedSourceBranch = branchId ? Number(branchId) : null;
                sourceRules = [];
                preview.replaceChildren();
                if (!movieId || !branchId) return;
                preview.appendChild(el('span', {}, ['Đang tải khung giá nguồn…']));
                hub.api('/pricing-templates/source-rules?movieId='
                    + encodeURIComponent(movieId) + '&branchId=' + encodeURIComponent(branchId))
                    .then(function (rules) {
                        sourceRules = rules || [];
                        preview.replaceChildren();
                        if (!sourceRules.length) {
                            preview.appendChild(el('span', {}, ['Nguồn chưa có khung giá để sao chép.']));
                            return;
                        }
                        preview.appendChild(el('strong', {}, [
                            'Sẽ sao chép ' + sourceRules.length + ' khung giá'
                        ]));
                        var previewList = el('ul');
                        sourceRules.slice(0, 6).forEach(function (rule) {
                            previewList.appendChild(el('li', {}, [
                                labelFor(SEATS, rule.seatType) + ' · '
                                + labelFor(DAYS, rule.dayType) + ' · '
                                + labelFor(SLOTS, rule.timeSlot) + ' · '
                                + (rule.ticketType || 'mọi loại vé') + ' · '
                                + hub.fmtMoney(rule.price)
                            ]));
                        });
                        if (sourceRules.length > 6) {
                            previewList.appendChild(el('li', {}, [
                                '… và ' + (sourceRules.length - 6) + ' khung giá khác'
                            ]));
                        }
                        preview.appendChild(previewList);
                    }).catch(function (requestError) {
                        sourceRules = [];
                        preview.replaceChildren(el('span', { class: 'ws-template-error' }, [
                            requestError.message || 'Không thể đọc khung giá nguồn.'
                        ]));
                    });
            }
            movieSelect.addEventListener('change', loadSourcePreview);
            branchSelect.addEventListener('change', loadSourcePreview);
        }

        form.appendChild(error);
        var footer = el('div', { class: 'ws-template-form-actions' });
        footer.appendChild(el('a', {
            class: 'ws-btn ws-btn-secondary',
            href: ctx + '/console?module=pricing-templates'
        }, ['Hủy']));
        var save = el('button', {
            type: 'submit', class: 'ws-btn ws-btn-primary'
        }, [editing ? 'Lưu thay đổi' : 'Lưu mẫu giá']);
        footer.appendChild(save);
        form.appendChild(footer);

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            setError(error, '');
            var normalizedName = name.value.trim();
            if (!normalizedName) {
                setError(error, 'Nhập tên mẫu giá.');
                name.focus();
                return;
            }
            if (!editing && sourcePanel && !sourcePanel.hidden) {
                if (!selectedSourceMovie || !selectedSourceBranch || !sourceRules.length) {
                    setError(error, 'Chọn phim và chi nhánh có khung giá để sao chép.');
                    return;
                }
                save.disabled = true;
                requestJson('/pricing-templates/from-movie', 'POST', {
                    name: normalizedName,
                    description: description.value.trim() || null,
                    movieId: selectedSourceMovie,
                    sourceBranchId: selectedSourceBranch
                }).then(function () {
                    hub.notify('Đã lưu mẫu giá từ phim nguồn.', 'ok');
                    router.go('pricing-templates');
                }).catch(function (requestError) {
                    save.disabled = false;
                    setError(error, requestError.message || 'Không lưu được mẫu giá.');
                });
                return;
            }

            var rules = [];
            try {
                matrixInputs.forEach(function (entry, index) {
                    var price = Number(entry.input.value);
                    if (!Number.isSafeInteger(price) || price < 1000) {
                        throw new Error('Nhập giá từ 1.000 VND cho tổ hợp cơ bản thứ '
                            + (index + 1) + '.');
                    }
                    rules.push({
                        seatType: entry.seatType,
                        dayType: entry.dayType,
                        timeSlot: entry.timeSlot,
                        price: price,
                        ticketType: null,
                        activeFrom: null,
                        activeTo: null
                    });
                });
                Array.from(advanced.children).forEach(function (row, index) {
                    if (typeof row.readRule === 'function') rules.push(row.readRule(index + 1));
                });
                var keys = new Set();
                rules.forEach(function (rule) {
                    var key = [rule.seatType, rule.dayType, rule.timeSlot,
                        rule.ticketType || '', rule.activeFrom || '', rule.activeTo || ''].join('|');
                    if (keys.has(key)) throw new Error('Có khung giá trùng tổ hợp.');
                    keys.add(key);
                });
            } catch (validationError) {
                setError(error, validationError.message);
                return;
            }
            save.disabled = true;
            var payload = {
                name: normalizedName,
                description: description.value.trim() || null,
                rules: rules
            };
            requestJson(editing ? '/pricing-templates/' + encodeURIComponent(template.id)
                : '/pricing-templates', editing ? 'PUT' : 'POST', payload)
                .then(function () {
                    hub.notify(editing ? 'Đã cập nhật mẫu giá.' : 'Đã tạo mẫu giá.', 'ok');
                    router.go('pricing-templates');
                }).catch(function (requestError) {
                    save.disabled = false;
                    setError(error, requestError.message || 'Không lưu được mẫu giá.');
                });
        });
        return form;
    }

    function renderForm(id) {
        var root = document.getElementById('viewRoot');
        var edit = !!id;
        if (!canManageTemplates) {
            root.replaceChildren(pageHeader('Không có quyền quản lý mẫu',
                'Chỉ Admin hoặc Quản lý chi nhánh mới được tạo hoặc chỉnh sửa mẫu giá.'));
            return;
        }
        root.setAttribute('aria-busy', 'true');
        var dataPromise = edit ? hub.api('/pricing-templates/' + encodeURIComponent(id))
            : Promise.resolve(null);
        dataPromise.then(function (template) {
            root.removeAttribute('aria-busy');
            if (edit && !canEditTemplate(template)) {
                root.replaceChildren(pageHeader('Không có quyền chỉnh sửa mẫu này',
                    'Bạn chỉ có thể chỉnh sửa mẫu giá do chính bạn tạo. '
                    + 'Liên hệ Admin nếu cần thay đổi.'));
                return;
            }
            var form = createEditor(template);
            root.replaceChildren(
                pageHeader(edit ? 'Chỉnh sửa mẫu giá' : 'Tạo mẫu giá vé',
                    edit ? 'Cập nhật bộ giá để tái sử dụng trong các phim tiếp theo.'
                        : 'Tạo bộ giá chuẩn hoặc sao chép từ một phim đã cấu hình.'),
                form
            );
        }).catch(function (error) {
            root.removeAttribute('aria-busy');
            root.replaceChildren(pageHeader('Không tải được mẫu giá',
                error.message || 'Mẫu giá không tồn tại hoặc dữ liệu không tải được.'));
        });
    }

    function createApplyPicker(options) {
        options = options || {};
        var section = el('section', { class: 'ws-form-section ws-template-apply-picker' });
        section.appendChild(el('h2', { class: 'ws-form-section-title' }, ['Dùng mẫu giá vé']));
        section.appendChild(el('p', { class: 'ws-form-section-desc' }, [
            'Chọn mẫu để điền khung giá bên trên, rồi chọn chi nhánh. Sau khi lưu phim, các quy tắc mẫu sẽ được áp dụng.'
        ]));
        var templateSelect = makeSelect([{ value: '', label: 'Không áp dụng mẫu' }], '', 'Mẫu giá vé');
        var templateField = el('label', { class: 'ws-template-field' }, [
            el('span', {}, ['Mẫu giá']), templateSelect
        ]);
        section.appendChild(templateField);
        var branchWrap = el('div', { class: 'ws-template-branch-picker' });
        var status = el('div', { class: 'ws-template-picker-status', role: 'status' });
        section.appendChild(branchWrap);
        section.appendChild(status);
        if (canManageTemplates) {
            section.appendChild(el('a', {
                class: 'ws-btn ws-btn-sm ws-btn-secondary',
                href: ctx + '/console?module=pricing-templates'
            }, []));
        }
        var allowedBranches = options.branches || [];
        var roleMeta = document.querySelector('meta[name="user-role"]');
        var currentRole = roleMeta ? roleMeta.content : '';
        var loadError = null;
        var preselectedTemplateId = String(options.preselectedTemplateId || '');
        var missingTemplateValue = '__missing_template__';
        var missingPreselectedTemplate = false;
        var preselectedBranches = new Set((options.preselectedBranchIds || []).map(String));
        var selectedRules = null;
        var selectedTemplatePromise = Promise.resolve(null);
        var selectionVersion = 0;
        var dataPromises = [hub.api('/pricing-templates')];
        if (currentRole === 'BRANCH_MANAGER') {
            dataPromises.push(hub.api('/branch/my-location'));
        } else {
            dataPromises.push(Promise.resolve(allowedBranches));
        }
        var ready = Promise.all(dataPromises).then(function (data) {
            if (!Array.isArray(data[0]) || !Array.isArray(data[1])) {
                throw new Error('Dữ liệu mẫu giá hoặc chi nhánh không đúng định dạng.');
            }
            var templates = data[0];
            var branches = data[1];
            templateSelect.replaceChildren(el('option', { value: '' }, ['Không áp dụng mẫu']));
            var preselectedTemplateFound = !preselectedTemplateId;
            templates.forEach(function (template) {
                var selected = String(template.id) === preselectedTemplateId;
                preselectedTemplateFound = preselectedTemplateFound || selected;
                templateSelect.appendChild(el('option', {
                    value: String(template.id),
                    selected: selected
                }, [template.name + ' · ' + template.ruleCount + ' khung']));
            });
            if (!preselectedTemplateFound) {
                missingPreselectedTemplate = true;
                templateSelect.insertBefore(el('option', {
                    value: missingTemplateValue,
                    selected: true
                }, ['Mẫu đã chọn không còn tồn tại']), templateSelect.firstChild.nextSibling);
            }
            branchWrap.replaceChildren(el('strong', {}, ['Chi nhánh áp dụng']));
            if (!branches.length) {
                branchWrap.appendChild(el('p', { class: 'ws-template-error' }, [
                    'Không có chi nhánh được phép áp dụng mẫu.'
                ]));
            }
            branches.forEach(function (branch, index) {
                var checkbox = el('input', {
                    type: 'checkbox',
                    value: String(branch.id),
                    checked: preselectedBranches.size
                        ? preselectedBranches.has(String(branch.id)) : index === 0,
                    'aria-label': branch.name || ('Chi nhánh #' + branch.id)
                });
                var label = el('label', { class: 'ws-template-branch-option' }, [
                    checkbox,
                    el('span', {}, [branch.name || ('Chi nhánh #' + branch.id)])
                ]);
                branchWrap.appendChild(label);
            });
            if (missingPreselectedTemplate) {
                status.className = 'ws-template-picker-status ws-template-error';
                status.textContent = 'Mẫu giá đã chọn không còn tồn tại. Chọn mẫu khác hoặc chọn "Không áp dụng mẫu" trước khi lưu.';
            } else {
                status.textContent = templates.length
                    ? 'Chọn mẫu để tải giá xem trước.'
                    : 'Chưa có mẫu giá. Admin có thể tạo mẫu tại thư viện Mẫu giá vé.';
            }
            branchWrap.querySelectorAll('input[type="checkbox"]').forEach(function (checkbox) {
                checkbox.addEventListener('change', function () {
                    if (selectedRules && typeof options.onTemplateRules === 'function') {
                        options.onTemplateRules(selectedRules, selectedBranchIds());
                    }
                });
            });
            branchWrap.hidden = !templateSelect.value;
            if (typeof options.onChange === 'function') {
                options.onChange(!!templateSelect.value);
            }
            loadSelectedTemplate();
        }).catch(function (error) {
            loadError = error;
            status.className = 'ws-template-picker-status ws-template-error';
            status.textContent = error.message || 'Không tải được danh sách mẫu hoặc chi nhánh.';
        });
        function selectedBranchIds() {
            return Array.from(branchWrap.querySelectorAll('input[type="checkbox"]:checked'))
                .map(function (checkbox) { return Number(checkbox.value); });
        }
        function loadSelectedTemplate() {
            var templateId = templateSelect.value;
            var version = ++selectionVersion;
            selectedRules = null;
            if (!templateId || templateId === missingTemplateValue) {
                selectedTemplatePromise = Promise.resolve(null);
                if (typeof options.onTemplateRules === 'function') {
                    options.onTemplateRules(null, []);
                }
                return selectedTemplatePromise;
            }
            status.className = 'ws-template-picker-status';
            status.textContent = 'Đang tải giá của mẫu…';
            selectedTemplatePromise = hub.api('/pricing-templates/'
                + encodeURIComponent(templateId)).then(function (template) {
                if (version !== selectionVersion) return null;
                selectedRules = template && Array.isArray(template.rules) ? template.rules : [];
                if (!selectedRules.length) {
                    throw new Error('Mẫu giá không có quy tắc để áp dụng.');
                }
                status.textContent = 'Đã tải ' + selectedRules.length
                    + ' quy tắc. Giá trong khung là để xem trước; điều kiện loại vé và ngày hiệu lực của mẫu vẫn được áp dụng.';
                if (typeof options.onTemplateRules === 'function') {
                    options.onTemplateRules(selectedRules, selectedBranchIds());
                }
                return selectedRules;
            }).catch(function (error) {
                if (version === selectionVersion) {
                    selectedRules = null;
                    status.className = 'ws-template-picker-status ws-template-error';
                    status.textContent = error.message || 'Không tải được quy tắc của mẫu giá.';
                }
                throw error;
            });
            selectedTemplatePromise.catch(function () {});
            return selectedTemplatePromise;
        }
        templateSelect.addEventListener('change', function () {
            if (templateSelect.value !== missingTemplateValue) {
                missingPreselectedTemplate = false;
                status.className = 'ws-template-picker-status';
                status.textContent = templateSelect.value
                    ? 'Đang tải giá của mẫu…'
                    : 'Không áp dụng mẫu giá.';
            }
            branchWrap.hidden = !templateSelect.value;
            if (typeof options.onChange === 'function') {
                options.onChange(!!templateSelect.value);
            }
            loadSelectedTemplate();
        });
        return {
            element: section,
            ready: ready,
            prepareSelection: function () {
                return ready.then(function () {
                    if (templateSelect.value && templateSelect.value !== missingTemplateValue) {
                        return selectedTemplatePromise.then(function () {
                            return this.getSelection();
                        }.bind(this));
                    }
                    return this.getSelection();
                }.bind(this));
            },
            getSelection: function () {
                if (loadError) {
                    throw new Error('Không tải được danh sách mẫu hoặc chi nhánh. Vui lòng thử lại trước khi lưu.');
                }
                if (missingPreselectedTemplate && templateSelect.value === missingTemplateValue) {
                    throw new Error('Mẫu giá đã chọn không còn tồn tại. Hãy chọn mẫu khác hoặc chọn "Không áp dụng mẫu".');
                }
                if (!templateSelect.value) return null;
                if (!selectedRules) {
                    throw new Error('Chưa tải được giá của mẫu đã chọn. Hãy chọn lại mẫu rồi thử tiếp.');
                }
                var checks = Array.from(branchWrap.querySelectorAll('input[type="checkbox"]:checked'));
                if (!checks.length) throw new Error('Chọn ít nhất một chi nhánh để áp dụng mẫu.');
                var names = checks.map(function (checkbox) {
                    var label = checkbox.closest('label');
                    return {
                        id: Number(checkbox.value),
                        name: label ? label.textContent.trim() : ('Chi nhánh #' + checkbox.value)
                    };
                });
                return {
                    templateId: Number(templateSelect.value),
                    branches: names,
                    branchIds: names.map(function (branch) { return branch.id; })
                };
            }
        };
    }

    function createBranchApplyPicker(options) {
        options = options || {};
        var section = el('section', { class: 'ws-form-section ws-template-apply-picker' });
        section.appendChild(el('h2', { class: 'ws-form-section-title' }, ['Mẫu giá theo chi nhánh']));
        section.appendChild(el('p', { class: 'ws-form-section-desc' }, [
            'Chọn mẫu riêng cho từng chi nhánh. Để trống nếu muốn nhập hoặc giữ giá thủ công; nếu đã có giá, hệ thống sẽ hỏi trước khi thay thế.'
        ]));
        var branchWrap = el('div', { class: 'ws-template-branch-assignments' });
        var status = el('div', { class: 'ws-template-picker-status', role: 'status' });
        section.appendChild(branchWrap);
        section.appendChild(status);
        if (canManageTemplates) {
            section.appendChild(el('a', {
                class: 'ws-btn ws-btn-sm ws-btn-secondary',
                href: ctx + '/console?module=pricing-templates'
            }, ['']));
        }

        var roleMeta = document.querySelector('meta[name="user-role"]');
        var currentRole = roleMeta ? roleMeta.content : '';
        var preselectedAssignments = new Map();
        var legacyTemplateId = String(options.preselectedTemplateId || '');
        (options.preselectedBranchIds || []).forEach(function (branchId) {
            if (legacyTemplateId) preselectedAssignments.set(String(branchId), legacyTemplateId);
        });
        String(options.preselectedAssignments || '').split(',').forEach(function (pair) {
            var parts = pair.split(':');
            if (parts.length === 2 && /^\d+$/.test(parts[0]) && /^\d+$/.test(parts[1])) {
                preselectedAssignments.set(parts[0], parts[1]);
            }
        });
        var loadError = null;
        var records = [];
        var ready = Promise.all([
            hub.api('/pricing-templates'),
            currentRole === 'BRANCH_MANAGER'
                ? hub.api('/branch/my-location')
                : Promise.resolve(options.branches || [])
        ]).then(function (data) {
            if (!Array.isArray(data[0]) || !Array.isArray(data[1])) {
                throw new Error('Dữ liệu mẫu giá hoặc chi nhánh không đúng định dạng.');
            }
            var templates = data[0];
            var branches = data[1];
            if (legacyTemplateId && !preselectedAssignments.size && branches.length) {
                preselectedAssignments.set(String(branches[0].id), legacyTemplateId);
            }
            branchWrap.replaceChildren();
            if (!branches.length) {
                branchWrap.appendChild(el('p', { class: 'ws-template-error' }, [
                    'Không có chi nhánh được phép áp dụng mẫu.'
                ]));
            }
            branches.forEach(function (branch) {
                var branchId = String(branch.id);
                var select = makeSelect([{
                    value: '',
                    label: 'Nhập giá thủ công / không áp dụng mẫu'
                }].concat(templates.map(function (template) {
                    return {
                        value: String(template.id),
                        label: template.name + ' · ' + template.ruleCount + ' khung'
                    };
                })), preselectedAssignments.get(branchId) || '', 'Mẫu giá cho ' + branch.name);
                var rowStatus = el('span', { class: 'ws-template-assignment-status' }, [
                    'Nhập hoặc giữ giá riêng của chi nhánh này.'
                ]);
                var row = el('div', { class: 'ws-template-branch-assignment' }, [
                    el('strong', {}, [branch.name || ('Chi nhánh #' + branchId)]),
                    select,
                    rowStatus
                ]);
                branchWrap.appendChild(row);
                var record = {
                    branch: { id: Number(branch.id), name: branch.name || ('Chi nhánh #' + branchId) },
                    select: select,
                    status: rowStatus,
                    rules: null,
                    promise: Promise.resolve(null),
                    version: 0
                };
                records.push(record);
                if (preselectedAssignments.has(branchId)
                        && !templates.some(function (template) {
                            return String(template.id) === select.value;
                        })) {
                    select.insertBefore(el('option', {
                        value: '__missing_template__',
                        selected: true
                    }, ['Mẫu đã chọn không còn tồn tại']), select.firstChild.nextSibling);
                    record.status.className = 'ws-template-assignment-status ws-template-error';
                    record.status.textContent = 'Chọn lại mẫu để có thể lưu.';
                }
                select.addEventListener('change', function () {
                    loadRecord(record);
                });
                if (select.value && select.value !== '__missing_template__') {
                    loadRecord(record);
                }
            });
           
        }).catch(function (error) {
            loadError = error;
            status.className = 'ws-template-picker-status ws-template-error';
            status.textContent = error.message || 'Không tải được danh sách mẫu hoặc chi nhánh.';
            return null;
        });

        function loadRecord(record) {
            var templateId = record.select.value;
            var version = ++record.version;
            record.rules = null;
            if (!templateId || templateId === '__missing_template__') {
                record.promise = Promise.resolve(null);
                record.status.className = templateId
                    ? 'ws-template-assignment-status ws-template-error'
                    : 'ws-template-assignment-status';
                record.status.textContent = templateId
                    ? 'Chọn lại mẫu để có thể lưu.'
                    : 'Nhập hoặc giữ giá riêng của chi nhánh này.';
                if (typeof options.onTemplateRules === 'function') {
                    options.onTemplateRules(record.branch.id, null, null);
                }
                return;
            }
            record.status.className = 'ws-template-assignment-status';
            record.status.textContent = 'Đang tải giá mẫu…';
            record.promise = hub.api('/pricing-templates/' + encodeURIComponent(templateId))
                .then(function (template) {
                    if (version !== record.version) return null;
                    record.rules = template && Array.isArray(template.rules) ? template.rules : [];
                    if (!record.rules.length) throw new Error('Mẫu giá không có quy tắc để áp dụng.');
                    record.status.textContent = 'Xem trước ' + record.rules.length
                        + ' quy tắc; điều kiện loại vé và ngày hiệu lực vẫn được giữ.';
                    if (typeof options.onTemplateRules === 'function') {
                        options.onTemplateRules(record.branch.id, record.rules, template.name);
                    }
                    return record.rules;
                }).catch(function (error) {
                    if (version === record.version) {
                        record.rules = null;
                        record.status.className = 'ws-template-assignment-status ws-template-error';
                        record.status.textContent = error.message || 'Không tải được giá mẫu.';
                    }
                    throw error;
                });
            record.promise.catch(function () {});
        }

        return {
            element: section,
            ready: ready,
            clearBranchTemplate: function (branchId) {
                var record = records.find(function (item) {
                    return String(item.branch.id) === String(branchId);
                });
                if (!record || !record.select.value) return;
                record.select.value = '';
                loadRecord(record);
            },
            prepareSelection: function () {
                return ready.then(function () {
                    return Promise.all(records.map(function (record) {
                        if (record.select.value === '__missing_template__') {
                            throw new Error('Một mẫu đã chọn không còn tồn tại. Hãy chọn lại mẫu.');
                        }
                        return record.select.value ? record.promise : null;
                    }));
                }).then(function () {
                    if (loadError) {
                        throw new Error('Không tải được danh sách mẫu hoặc chi nhánh. Vui lòng thử lại trước khi lưu.');
                    }
                    return records.filter(function (record) {
                        return record.select.value && record.rules;
                    }).map(function (record) {
                        return {
                            templateId: Number(record.select.value),
                            templateName: record.select.selectedOptions[0].textContent,
                            branches: [record.branch],
                            branchIds: [record.branch.id]
                        };
                    });
                });
            }
        };
    }

    router.register('pricing-templates', {
        list: renderLibrary,
        create: function () { renderForm(null); },
        edit: renderForm
    });

    window.CinemaHubPricingTemplates = {
        createApplyPicker: createApplyPicker,
        createBranchApplyPicker: createBranchApplyPicker
    };
})();
