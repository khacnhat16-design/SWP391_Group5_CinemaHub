/**
 * inventory-material.js — Quản lý Kho Nguyên Liệu Thô & Bao Bì.
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var inv = window.CinemaHubInventory;
    if (!hub || !inv) return;

    var notifyError = inv.notifyError;
    var createInventoryModal = inv.createInventoryModal;
    var makeInvField = inv.makeInvField;
    var makeInvFooter = inv.makeInvFooter;
    var makeInvRadio = inv.makeInvRadio;
    var makeStatCard = inv.makeStatCard;
    var formatMaterialUnit = inv.formatMaterialUnit;
    var formatMaterialCategory = inv.formatMaterialCategory;
    var formatInventoryReason = inv.formatInventoryReason;
    var formatMoneyVnd = inv.formatMoneyVnd;
    var setFieldError = inv.setFieldError;
    var clearFieldError = inv.clearFieldError;

    function showMaterialMinStockModal(material, onReload, branchId) {
        var mId = material.materialId || material.id;
        var unit = material.unit || '';
        var unitFormatted = formatMaterialUnit(unit);
        var currentMin = material.minStock != null ? material.minStock : 0;

        var infoBox = hub.el('div', { class: 'ws-inv-info-card' }, [
            hub.el('div', { class: 'info-title', style: 'margin-bottom: 4px;' }, [material.name || ('Nguyên liệu #' + mId)]),
            hub.el('div', { class: 'info-sub' }, [
                'Mã: ' + (material.code || '—') + ' | Phân loại: ' + formatMaterialCategory(material.category) + ' | ĐVT: ' + unitFormatted
            ]),
            hub.el('div', { class: 'info-meta' }, [
                hub.el('span', {}, ['Định mức hiện tại: ', hub.el('strong', { style: 'color: #ea580c;' }, [currentMin + ' ' + unitFormatted])])
            ])
        ]);

        var minStockInput = hub.el('input', {
            type: 'number',
            step: 'any',
            min: '0.01',
            placeholder: 'Nhập định mức (> 0)…',
            value: (currentMin != null && currentMin > 0) ? currentMin : '',
            class: 'ws-input'
        });

        var cancelBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-secondary', style: 'flex: 1;' }, ['Hủy']);
        var saveBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-primary cyan', style: 'flex: 1;' }, ['Lưu định mức']);
        var footer = makeInvFooter(cancelBtn, saveBtn);

        var modal = createInventoryModal({
            title: 'Cấu hình định mức tồn kho',
            subtitle: 'Thiết lập ngưỡng cảnh báo sắp hết hàng',
            theme: 'cyan',
            maxWidth: '480px',
            body: [
                infoBox,
                makeInvField('Định mức tồn tối thiểu mới (' + unitFormatted + ')', minStockInput, true),
                hub.el('small', { class: 'muted', style: 'display: block; margin-top: -8px; margin-bottom: 14px; font-size: 0.8rem;' }, [
                    'Hệ thống sẽ chuyển trạng thái sang "Sắp hết hàng" khi lượng tồn kho thực tế nhỏ hơn hoặc bằng định mức này.'
                ])
            ],
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        function validateMinStock() {
            clearFieldError(minStockInput);
            var raw = (minStockInput.value || '').trim();
            if (!raw) {
                setFieldError(minStockInput, 'Vui lòng nhập định mức tồn kho tối thiểu.');
                return false;
            }
            var val = parseFloat(raw);
            if (isNaN(val) || val <= 0) {
                setFieldError(minStockInput, 'Định mức tồn kho tối thiểu phải lớn hơn 0.');
                return false;
            }
            return true;
        }

        minStockInput.addEventListener('input', function () {
            var raw = (minStockInput.value || '').trim();
            if (raw) {
                var val = parseFloat(raw);
                if (isNaN(val) || val <= 0) {
                    setFieldError(minStockInput, 'Định mức tồn kho tối thiểu phải lớn hơn 0.');
                } else {
                    clearFieldError(minStockInput);
                }
            } else {
                clearFieldError(minStockInput);
            }
        });

        saveBtn.addEventListener('click', function () {
            if (!validateMinStock()) {
                minStockInput.focus();
                return;
            }
            var raw = (minStockInput.value || '').trim();
            var val = parseFloat(raw);

            saveBtn.disabled = true;
            saveBtn.textContent = 'Đang lưu…';

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    type: 'material',
                    action: 'update-min-stock',
                    materialId: mId,
                    minStock: val,
                    branchId: branchId || 0
                }
            }).then(function () {
                modal.close();
                hub.notify('Cập nhật định mức tồn kho tối thiểu thành công!', 'ok');
                material.minStock = val;
                if (typeof onReload === 'function') onReload();
            }).catch(function (err) {
                saveBtn.disabled = false;
                saveBtn.textContent = 'Lưu định mức';
                hub.notify(err.message || 'Lỗi khi cập nhật định mức', 'error');
            });
        });
    }

    // 1. Modal xem chi tiết nguyên liệu và lịch sử biến động
    function showMaterialDetailModal(branchId, material, rawMaterials, onReload, isStaff) {
        var mId = material.materialId || material.id;
        var qty = material.qty != null ? material.qty : 0;
        var minStock = material.minStock != null ? material.minStock : 0;
        var unit = material.unit || '';
        var statusClass = qty <= 0 ? 'ws-badge-red' : (qty <= minStock ? 'ws-badge-orange' : 'ws-badge-green');
        var statusLabel = qty <= 0 ? 'Hết hàng' : (qty <= minStock ? ('Sắp hết (' + qty + ')') : 'Còn hàng');

        var infoCard = hub.el('div', { class: 'ws-inv-info-card' }, [
            hub.el('div', { class: 'info-head' }, [
                hub.el('div', { class: 'info-row' }, [
                    hub.el('div', { class: 'ws-inv-avatar cyan' }, ['NL']),
                    hub.el('div', {}, [
                        hub.el('h4', { class: 'info-title' }, [material.name || ('Nguyên liệu #' + mId)]),
                        hub.el('div', { class: 'info-sub' }, ['Phân loại: ' + formatMaterialCategory(material.category)])
                    ])
                ]),
                hub.el('span', { class: 'ws-badge ws-status-pill ' + statusClass }, [statusLabel])
            ]),
            hub.el('div', { class: 'info-meta', style: 'border-top: 1px dashed #cbd5e1; padding-top: 10px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px;' }, [
                hub.el('div', {}, ['ĐVT: ', hub.el('strong', {}, [formatMaterialUnit(unit)])]),
                hub.el('div', {}, ['Tồn hiện tại: ', hub.el('strong', { style: 'color: #0284c7;' }, [qty + ' ' + formatMaterialUnit(unit)])]),
                hub.el('div', {}, [
                    'Tối thiểu: ', hub.el('strong', { style: 'color: #ea580c;' }, [minStock + ' ' + formatMaterialUnit(unit)]),
                    (!isStaff ? hub.el('button', {
                        type: 'button',
                        class: 'ws-btn secondary',
                        style: 'padding: 1px 6px; font-size: 0.72rem; margin-left: 6px;',
                        onclick: function () {
                            modal.close();
                            showMaterialMinStockModal(material, onReload, branchId);
                        }
                    }, ['Sửa']) : '')
                ]),
                hub.el('div', {}, ['NCC: ', hub.el('strong', {}, [material.supplier || '—'])]),
                hub.el('div', {}, ['Giá nhập: ', hub.el('strong', {}, [formatMoneyVnd(material.costPrice)])]),
                hub.el('div', {}, ['Trạng thái: ', hub.el('span', { class: 'ws-badge ws-badge-green' }, [(material.status === 'ACTIVE' || !material.status) ? 'Đang hoạt động' : material.status])])
            ])
        ]);

        var auditFilterSelect = hub.el('select', { class: 'ws-select', style: 'width: auto; height: 32px; font-size: 0.82rem;' }, [
            hub.el('option', { value: 'ALL' }, ['Tất cả thời gian']),
            hub.el('option', { value: 'TODAY' }, ['Hôm nay']),
            hub.el('option', { value: '7DAYS' }, ['7 ngày gần nhất']),
            hub.el('option', { value: '30DAYS' }, ['30 ngày gần nhất'])
        ]);

        var histHead = hub.el('div', { style: 'display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;' }, [
            hub.el('h5', { class: 'ws-inv-label', style: 'margin: 0;' }, ['Lịch sử biến động kho']),
            auditFilterSelect
        ]);

        var tableWrap = hub.el('div', { class: 'ws-inv-history-wrap' });
        tableWrap.appendChild(hub.el('p', { class: 'muted', style: 'padding: 24px; text-align: center; margin: 0;' }, ['Đang tải lịch sử…']));

        var paginationWrap = hub.el('div', { style: 'display: flex; justify-content: flex-end; align-items: center; gap: 6px; margin-top: 12px;' });

        var footBtns = [];
        if (!isStaff && rawMaterials) {
            var btnRestock = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-primary green', type: 'button' }, ['Nhập thêm']);
            btnRestock.addEventListener('click', function () {
                modal.close();
                showMaterialRestockModal(branchId, rawMaterials, onReload, material);
            });

            var btnAdjust = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-secondary', type: 'button' }, ['Điều chỉnh']);
            btnAdjust.addEventListener('click', function () {
                modal.close();
                showMaterialAdjustModal(branchId, material, rawMaterials, onReload);
            });

            var btnMinStock = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-secondary', type: 'button' }, ['Sửa định mức']);
            btnMinStock.addEventListener('click', function () {
                modal.close();
                showMaterialMinStockModal(material, onReload, branchId);
            });

            footBtns.push(btnRestock, btnAdjust, btnMinStock);
        }

        var closeFoot = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-secondary', type: 'button' }, ['Đóng']);
        footBtns.push(closeFoot);
        var footWrap = hub.el('div', { class: 'ws-inv-modal-footer' }, footBtns);

        var modal = createInventoryModal({
            title: 'Chi tiết nguyên liệu & Lịch sử biến động',
            subtitle: 'Lịch sử xuất nhập & điều chỉnh nguyên liệu',
            theme: 'cyan',
            maxWidth: '760px',
            body: [infoCard, histHead, tableWrap, paginationWrap],
            footer: footWrap
        });

        closeFoot.addEventListener('click', modal.close);

        hub.api('/inventory?type=material&action=history&branchId=' + branchId + '&materialId=' + mId)
            .then(function (res) {
                var rawList = (res && res.data) || res || [];
                if (!Array.isArray(rawList) || rawList.length === 0) {
                    tableWrap.replaceChildren(hub.el('p', { class: 'muted', style: 'padding: 24px; text-align: center; margin: 0;' }, ['Chưa có lịch sử biến động nào cho nguyên liệu này.']));
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
                            return h.createdAt && new Date(h.createdAt) >= sevenDaysAgo;
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
                        tableWrap.replaceChildren(hub.el('p', { class: 'muted', style: 'padding: 24px; text-align: center; margin: 0;' }, ['Không tìm thấy lịch sử phù hợp với khoảng thời gian đã chọn.']));
                        paginationWrap.replaceChildren();
                        return;
                    }

                    var table = hub.el('table', { class: 'ws-inv-history-table' });
                    var thead = hub.el('thead', {}, [
                        hub.el('tr', {}, [
                            hub.el('th', {}, ['Ngày giờ']),
                            hub.el('th', { style: 'text-align: center;' }, ['Loại']),
                            hub.el('th', {}, ['Số lượng']),
                            hub.el('th', {}, ['Lý do']),
                            hub.el('th', {}, ['Người thực hiện']),
                            hub.el('th', {}, ['Ghi chú'])
                        ])
                    ]);
                    var tbody = hub.el('tbody');
                    var start = curPage * pageSize;
                    var pageItems = filteredList.slice(start, start + pageSize);

                    pageItems.forEach(function (h) {
                        var isPlus = h.deltaQty > 0;
                        var absQty = Math.abs(h.deltaQty);
                        var typeLabel = isPlus ? '+ Nhập' : '- Xuất';

                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', { class: 'muted', style: 'white-space: nowrap;' }, [h.createdAt || '—']),
                            hub.el('td', { style: 'text-align: center;' }, [
                                hub.el('span', { class: 'ws-inv-badge-sign ' + (isPlus ? 'plus' : 'minus') }, [typeLabel])
                            ]),
                            hub.el('td', { style: 'font-weight: 600;' }, [absQty + ' ' + formatMaterialUnit(unit)]),
                            hub.el('td', {}, [formatInventoryReason(h.reason)]),
                            hub.el('td', { style: 'font-weight: 600;' }, [h.actorName || ('ID: ' + h.actorId)]),
                            hub.el('td', { class: 'muted' }, [h.note || '—'])
                        ]));
                    });

                    table.appendChild(thead);
                    table.appendChild(tbody);
                    tableWrap.replaceChildren(table);

                    paginationWrap.replaceChildren();
                    if (totalPages > 1) {
                        var prevBtn = hub.el('button', { class: 'ws-pagination-btn', type: 'button', disabled: curPage === 0 }, ['<']);
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

                        var nextBtn = hub.el('button', { class: 'ws-pagination-btn', type: 'button', disabled: curPage >= totalPages - 1 }, ['>']);
                        nextBtn.addEventListener('click', function () {
                            if (curPage < totalPages - 1) { curPage++; renderHistoryTable(); }
                        });
                        paginationWrap.appendChild(nextBtn);
                    }
                }
                renderHistoryTable();
            })
            .catch(function (err) {
                tableWrap.replaceChildren(hub.el('p', { class: 'text-danger', style: 'padding: 24px; text-align: center; margin: 0;' }, ['Không thể tải lịch sử: ' + (err.message || err)]));
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

        var branchHint = hub.el('div', { class: 'ws-inv-hint' }, [
            'Chi nhánh: ', hub.el('strong', {}, [branchName || ('Chi nhánh #' + branchId)])
        ]);

        var matSelect = hub.el('select', { class: 'ws-select' });
        matSelect.appendChild(hub.el('option', { value: '' }, ['Chọn nguyên liệu…']));
        (materials || []).filter(function (it) {
            return !it.status || it.status.toUpperCase() === 'ACTIVE';
        }).forEach(function (it) {
            var mId = it.materialId || it.id;
            matSelect.appendChild(hub.el('option', { value: String(mId) }, [it.name + ' (' + (it.qty != null ? it.qty : 0) + ' ' + (it.unit || '') + ')']));
        });

        var qtyInput = hub.el('input', { type: 'number', step: 'any', min: '0.01', placeholder: 'Nhập số lượng (vd: 50.5)', class: 'ws-input' });

        var costInput = hub.el('input', { type: 'number', placeholder: 'Đơn giá (đ/ĐVT)…', class: 'ws-input' });
        var supplierInput = hub.el('input', { type: 'text', placeholder: 'Nhà cung cấp…', class: 'ws-input' });
        var rowCostSupplier = hub.el('div', { class: 'ws-inv-grid-2' }, [
            makeInvField('Đơn giá nhập', costInput, false),
            makeInvField('Nhà cung cấp', supplierInput, false)
        ]);

        var reasonSelect = hub.el('select', { class: 'ws-select' });
        [
            { value: 'NHAP_HANG_TU_NCC', label: 'Nhập hàng từ nhà cung cấp' },
            { value: 'NHAP_LAI_TU_NCC', label: 'Nhập đổi trả từ nhà cung cấp' },
            { value: 'HOAN_TU_DON_HUY', label: 'Hoàn lại từ đơn hủy' },
            { value: 'DIEU_CHINH_KHAC', label: 'Lý do khác' }
        ].forEach(function (opt) {
            reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
        });

        var poInput = hub.el('input', { type: 'text', placeholder: 'Số PO / Hóa đơn…', class: 'ws-input' });
        var rowReasonPo = hub.el('div', { class: 'ws-inv-grid-2' }, [
            makeInvField('Lý do', reasonSelect, true),
            makeInvField('Số hóa đơn / PO', poInput, false)
        ]);

        var noteInput = hub.el('input', { type: 'text', placeholder: 'Nhập ghi chú (bắt buộc nếu chọn Điều chỉnh khác)…', class: 'ws-input' });

        var previewBox = hub.el('div', { class: 'ws-inv-preview-box' }, ['Sau khi nhập: Tồn mới = —']);

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

        var cancelBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-secondary', style: 'flex: 1;' }, ['Hủy']);
        var confirmBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-primary green', style: 'flex: 1;' }, ['Xác nhận nhập']);
        var footer = makeInvFooter(cancelBtn, confirmBtn);

        var modal = createInventoryModal({
            title: 'Nhập nguyên liệu',
            subtitle: 'Tăng số lượng tồn kho nguyên liệu chi nhánh',
            theme: 'green',
            maxWidth: '520px',
            body: [
                branchHint,
                makeInvField('Nguyên liệu', matSelect, true),
                makeInvField('Số lượng nhập', qtyInput, true),
                rowCostSupplier,
                rowReasonPo,
                makeInvField('Ghi chú', noteInput, false),
                previewBox
            ],
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(matSelect);
            clearFieldError(qtyInput);
            clearFieldError(costInput);
            clearFieldError(noteInput);

            var hasError = false;
            var firstErr = null;

            var mId = matSelect.value;
            if (!mId) {
                setFieldError(matSelect, 'Vui lòng chọn nguyên liệu.');
                hasError = true;
                if (!firstErr) firstErr = matSelect;
            }

            var qtyRaw = (qtyInput.value || '').trim();
            var qtyVal = parseFloat(qtyRaw);
            if (!qtyRaw || isNaN(qtyVal) || qtyVal <= 0) {
                setFieldError(qtyInput, 'Số lượng nhập phải lớn hơn 0.');
                hasError = true;
                if (!firstErr) firstErr = qtyInput;
            }

            var costRaw = (costInput.value || '').trim();
            if (costRaw) {
                var costVal = parseFloat(costRaw);
                if (isNaN(costVal) || costVal < 0) {
                    setFieldError(costInput, 'Đơn giá không được âm.');
                    hasError = true;
                    if (!firstErr) firstErr = costInput;
                }
            }

            var reasonVal = reasonSelect.value;
            var noteVal = (noteInput.value || '').trim();
            if (reasonVal === 'DIEU_CHINH_KHAC' && noteVal.length < 10) {
                setFieldError(noteInput, 'Khi chọn lý do "Điều chỉnh khác", ghi chú bắt buộc phải có ít nhất 10 ký tự.');
                hasError = true;
                if (!firstErr) firstErr = noteInput;
            }

            if (hasError) {
                if (firstErr) firstErr.focus();
                return;
            }

            confirmBtn.disabled = true;
            confirmBtn.textContent = 'Đang lưu…';

            hub.api('/inventory', {
                method: 'POST',
                body: {
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
                }
            }).then(function () {
                modal.close();
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
        var matSelect = hub.el('select', { class: 'ws-select' });
        matSelect.appendChild(hub.el('option', { value: '' }, ['Chọn nguyên liệu…']));
        (materials || []).forEach(function (it) {
            var mId = it.materialId || it.id;
            matSelect.appendChild(hub.el('option', { value: String(mId) }, [it.name + ' (' + (it.qty != null ? it.qty : 0) + ' ' + (it.unit || '') + ')']));
        });
        if (selectedMaterial && (selectedMaterial.materialId || selectedMaterial.id)) {
            matSelect.value = String(selectedMaterial.materialId || selectedMaterial.id);
        }

        var currentQtyDisplay = hub.el('strong', {}, ['0']);
        var currentQtyWrap = hub.el('div', { class: 'ws-inv-hint' }, ['Tồn hiện tại: ', currentQtyDisplay]);

        var currentItem = null;
        function updateCurrentQty() {
            var selectedId = matSelect.value;
            currentItem = (materials || []).find(function (it) { return String(it.materialId || it.id) === selectedId; }) || null;
            var q = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
            var u = currentItem ? (currentItem.unit || '') : '';
            currentQtyDisplay.textContent = q + ' ' + u;
            updateRealtimeCheck();
        }

        var radioMinusObj = makeInvRadio('mr5Type', 'MINUS', 'GIẢM', 'minus', true);
        var radioPlusObj = makeInvRadio('mr5Type', 'PLUS', 'TĂNG', 'plus', false);
        var radioSetObj = makeInvRadio('mr5Type', 'SET', 'SỬA THẲNG', 'set', false);
        var radioMinus = radioMinusObj.radio;
        var radioPlus = radioPlusObj.radio;
        var radioSet = radioSetObj.radio;

        var typeGroup = hub.el('div', { class: 'ws-inv-group' }, [
            hub.el('label', { class: 'ws-inv-label' }, ['Loại điều chỉnh', hub.el('span', { class: 'ws-inv-req' }, [' *'])]),
            hub.el('div', { class: 'ws-inv-radio-group' }, [radioMinusObj.label, radioPlusObj.label, radioSetObj.label])
        ]);

        var qtyInput = hub.el('input', { type: 'number', step: 'any', min: '0.01', placeholder: 'Nhập số lượng giảm (vd: 5.5)…', class: 'ws-input' });
        var qtyField = makeInvField('Số lượng giảm', qtyInput, true);

        var reasonSelect = hub.el('select', { class: 'ws-select' });
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
            var opts = reasonsDecrease;
            if (radioPlus.checked) {
                opts = reasonsIncrease;
            } else if (radioSet.checked) {
                var curQ = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
                var setVal = parseFloat(qtyInput.value) || 0;
                opts = setVal >= curQ ? reasonsIncrease : reasonsDecrease;
            }
            reasonSelect.replaceChildren();
            opts.forEach(function (opt) {
                reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
            });
        }

        var noteInput = hub.el('input', { type: 'text', placeholder: 'Nhập ghi chú thêm…', class: 'ws-input' });
        var noteField = makeInvField('Ghi chú', noteInput, false);

        var warningBox = hub.el('div', { class: 'ws-inv-warn-box', style: 'display: none;' });

        function updateRealtimeCheck() {
            var curQ = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
            var minQ = currentItem ? (currentItem.minStock != null ? currentItem.minStock : 0) : 0;
            var unit = currentItem ? (currentItem.unit || '') : '';
            var val = parseFloat(qtyInput.value) || 0;

            var newQty = curQ;
            if (radioMinus.checked) newQty = curQ - val;
            else if (radioPlus.checked) newQty = curQ + val;
            else if (radioSet.checked) newQty = val;

            if (currentItem && newQty < minQ) {
                warningBox.style.display = 'block';
                warningBox.innerHTML = '<strong>Cảnh báo:</strong> Tồn sau điều chỉnh (' + (Math.round(newQty * 100) / 100) + ' ' + unit + ') dưới mức tối thiểu (' + minQ + ' ' + unit + ')';
            } else {
                warningBox.style.display = 'none';
            }
        }

        function onTypeChange() {
            var lbl = qtyField.querySelector('.ws-inv-label');
            if (radioMinus.checked) {
                if (lbl) lbl.innerHTML = 'Số lượng giảm <span class="ws-inv-req">*</span>';
                qtyInput.placeholder = 'Nhập số lượng giảm (vd: 5.5)…';
            } else if (radioPlus.checked) {
                if (lbl) lbl.innerHTML = 'Số lượng tăng <span class="ws-inv-req">*</span>';
                qtyInput.placeholder = 'Nhập số lượng tăng (vd: 10.0)…';
            } else {
                if (lbl) lbl.innerHTML = 'Tồn kho mới (Set value) <span class="ws-inv-req">*</span>';
                qtyInput.placeholder = 'Nhập số tồn kho thực tế mới (vd: 120)…';
            }
            updateReasonList();
            updateRealtimeCheck();
        }

        matSelect.addEventListener('change', function () {
            updateCurrentQty();
            updateReasonList();
        });
        radioMinus.addEventListener('change', onTypeChange);
        radioPlus.addEventListener('change', onTypeChange);
        radioSet.addEventListener('change', onTypeChange);
        qtyInput.addEventListener('input', function () {
            if (radioSet.checked) updateReasonList();
            updateRealtimeCheck();
        });

        updateCurrentQty();
        onTypeChange();

        var cancelBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-secondary', style: 'flex: 1;' }, ['Hủy']);
        var confirmBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-primary slate', style: 'flex: 1;' }, ['Xác nhận điều chỉnh']);
        var footer = makeInvFooter(cancelBtn, confirmBtn);

        var modal = createInventoryModal({
            title: 'Điều chỉnh nguyên liệu',
            subtitle: 'Kiểm kê, hao hụt hoặc cân bằng số tồn',
            theme: 'slate',
            maxWidth: '500px',
            body: [
                makeInvField('Nguyên liệu', matSelect, true),
                currentQtyWrap,
                typeGroup,
                qtyField,
                makeInvField('Lý do', reasonSelect, true),
                noteField,
                warningBox
            ],
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(matSelect);
            clearFieldError(qtyInput);
            clearFieldError(reasonSelect);
            clearFieldError(noteInput);

            var hasError = false;
            var firstErr = null;

            var mId = matSelect.value;
            if (!mId) {
                setFieldError(matSelect, 'Vui lòng chọn nguyên liệu.');
                hasError = true;
                if (!firstErr) firstErr = matSelect;
            }

            var curQ = currentItem ? (currentItem.qty != null ? currentItem.qty : 0) : 0;
            var qtyRaw = (qtyInput.value || '').trim();
            var val = parseFloat(qtyRaw);
            var deltaQty = 0;

            if (!qtyRaw || isNaN(val)) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng.');
                hasError = true;
                if (!firstErr) firstErr = qtyInput;
            } else if (radioMinus.checked) {
                if (val <= 0) {
                    setFieldError(qtyInput, 'Số lượng giảm phải lớn hơn 0.');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else if (mId && val > curQ) {
                    setFieldError(qtyInput, 'Số lượng giảm (' + val + ') không được vượt quá tồn hiện tại (' + curQ + ')!');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else {
                    deltaQty = -val;
                }
            } else if (radioPlus.checked) {
                if (val <= 0) {
                    setFieldError(qtyInput, 'Số lượng tăng phải lớn hơn 0.');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else {
                    deltaQty = val;
                }
            } else if (radioSet.checked) {
                if (val < 0) {
                    setFieldError(qtyInput, 'Số lượng tồn mới không được âm.');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else {
                    deltaQty = Math.round((val - curQ) * 100) / 100;
                    if (mId && deltaQty === 0) {
                        setFieldError(qtyInput, 'Số tồn mới bằng tồn hiện tại, không có chênh lệch để điều chỉnh.');
                        hasError = true;
                        if (!firstErr) firstErr = qtyInput;
                    }
                }
            }

            var reasonVal = reasonSelect.value;
            if (!reasonVal) {
                setFieldError(reasonSelect, 'Vui lòng chọn lý do điều chỉnh.');
                hasError = true;
                if (!firstErr) firstErr = reasonSelect;
            }

            var noteVal = (noteInput.value || '').trim();
            if ((reasonVal === 'OTHER' || reasonVal === 'DIEU_CHINH_KHAC') && noteVal.length < 10) {
                setFieldError(noteInput, 'Khi chọn lý do Khác / Điều chỉnh khác, ghi chú bắt buộc phải có ít nhất 10 ký tự.');
                hasError = true;
                if (!firstErr) firstErr = noteInput;
            }

            if (hasError) {
                if (firstErr) firstErr.focus();
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
                modal.close();
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
        var rawMaterials = [];
        var currentPage = 0;
        var pageSize = 10;

        // Header
        var header = hub.el('div', { class: 'ws-page-header' }, [
            hub.el('h1', { class: 'ws-page-title' }, ['Kho Nguyên Liệu']),
            hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý tồn kho nguyên liệu phục vụ chế biến bắp nước tại chi nhánh.'])
        ]);
        page.appendChild(header);

        // Vùng 4 Summary Cards
        var cardTotal = makeStatCard('total', 'TỔNG NGUYÊN LIỆU', 'mrTotalCount');
        var cardInStock = makeStatCard('in-stock', 'CÒN HÀNG', 'mrInStockCount');
        var cardLowStock = makeStatCard('low-stock', 'SẮP HẾT', 'mrLowStockCount');
        var cardOutOfStock = makeStatCard('out-stock', 'HẾT HÀNG', 'mrOutOfStockCount');
        var cardsContainer = hub.el('div', { class: 'ws-inv-cards' }, [cardTotal, cardInStock, cardLowStock, cardOutOfStock]);
        page.appendChild(cardsContainer);

        // Toolbar
        var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
        var tbLeft = hub.el('div', { class: 'ws-list-toolbar-left' });

        var branchSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Chi nhánh' });
        (branches || []).forEach(function (b) {
            var opt = hub.el('option', { value: String(b.id) }, [b.name]);
            if (String(b.id) === String(branchId)) opt.selected = true;
            branchSelect.appendChild(opt);
        });
        tbLeft.appendChild(branchSelect);

        var searchWrap = hub.el('div', { class: 'ws-search' });
        searchWrap.appendChild(hub.el('span', { 'aria-hidden': 'true', innerHTML: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>' }));
        var searchInput = hub.el('input', { type: 'search', placeholder: 'Tìm nguyên liệu theo tên…', 'aria-label': 'Tìm nguyên liệu' });
        searchWrap.appendChild(searchInput);
        tbLeft.appendChild(searchWrap);

        var groupSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Nhóm' });
        [
            { value: '', label: 'Tất cả nhóm' },
            { value: 'BAP', label: 'Bắp hạt' },
            { value: 'DO_UONG', label: 'Đồ uống' },
            { value: 'BAO_BI', label: 'Bao bì & Ly' },
            { value: 'GIA_VI', label: 'Gia vị & Bơ' }
        ].forEach(function (g) {
            groupSelect.appendChild(hub.el('option', { value: g.value }, [g.label]));
        });
        tbLeft.appendChild(groupSelect);

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

        var tbRight = hub.el('div', { class: 'ws-list-toolbar-right' });
        if (!isStaff) {
            var btnTopRestock = hub.el('button', { class: 'ws-btn primary', type: 'button', style: 'background: #16a34a; border-color: #16a34a;' }, ['+ Nhập Nguyên Liệu']);
            btnTopRestock.addEventListener('click', function () {
                var curBranchObj = (branches || []).find(function (b) { return String(b.id) === String(branchSelect.value); });
                var curBranchId = curBranchObj ? curBranchObj.id : branchId;
                var curBranchName = curBranchObj ? curBranchObj.name : '';
                showMaterialRestockModal(curBranchId, curBranchName, rawMaterials, loadData);
            });

            var btnTopAdjust = hub.el('button', { class: 'ws-btn secondary', type: 'button' }, ['Điều chỉnh']);
            btnTopAdjust.addEventListener('click', function () {
                var curBranchObj = (branches || []).find(function (b) { return String(b.id) === String(branchSelect.value); });
                var curBranchId = curBranchObj ? curBranchObj.id : branchId;
                showMaterialAdjustModal(curBranchId, null, rawMaterials, loadData);
            });

            tbRight.appendChild(btnTopRestock);
            tbRight.appendChild(btnTopAdjust);
        }
        toolbar.appendChild(tbRight);
        page.appendChild(toolbar);

        var tableCard = hub.el('div', { class: 'ws-card', style: 'padding: 0; overflow: hidden;' });
        var tableRegion = hub.el('div');
        tableCard.appendChild(tableRegion);
        page.appendChild(tableCard);

        targetRoot.replaceChildren(page);

        // Click summary card để lọc nhanh
        cardTotal.addEventListener('click', function () { statusSelect.value = ''; applyFilters(); });
        cardInStock.addEventListener('click', function () { statusSelect.value = 'IN_STOCK'; applyFilters(); });
        cardLowStock.addEventListener('click', function () { statusSelect.value = 'LOW_STOCK'; applyFilters(); });
        cardOutOfStock.addEventListener('click', function () { statusSelect.value = 'OUT_OF_STOCK'; applyFilters(); });

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

                    var total = rawMaterials.length;
                    var inStock = 0;
                    var lowStock = 0;
                    var outOfStock = 0;

                    rawMaterials.forEach(function (m) {
                        var q = m.qty != null ? m.qty : 0;
                        var minQ = m.minStock != null ? m.minStock : 0;
                        if (q <= 0) outOfStock++;
                        else if (q <= minQ) lowStock++;
                        else inStock++;
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

        function applyFilters(resetPage) {
            if (resetPage !== false) currentPage = 0;
            var search = (searchInput.value || '').trim().toLowerCase();
            var searchNorm = search.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
            var catFilter = groupSelect.value;
            var stFilter = statusSelect.value;
            var sortVal = sortSelect.value;

            highlightCard(stFilter);

            var filtered = rawMaterials.filter(function (it) {
                if (search) {
                    var nameLower = (it.name || '').toLowerCase();
                    var nameNorm = nameLower.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
                    var codeLower = (it.code || '').toLowerCase();
                    var matchName = nameLower.indexOf(search) !== -1 || nameNorm.indexOf(searchNorm) !== -1;
                    var matchCode = codeLower.indexOf(search) !== -1;
                    if (!matchName && !matchCode) return false;
                }
                if (catFilter) {
                    var cFormatted = formatMaterialCategory(it.category);
                    var c = (it.category || '').toUpperCase();
                    var f = catFilter.toUpperCase();
                    var fFormatted = formatMaterialCategory(catFilter);
                    if (c !== f && c.indexOf(f) === -1 && cFormatted !== fFormatted && cFormatted.indexOf(f) === -1) return false;
                }
                if (stFilter) {
                    var q = it.qty != null ? it.qty : 0;
                    var minQ = it.minStock != null ? it.minStock : 0;
                    if (stFilter === 'OUT_OF_STOCK' && q > 0) return false;
                    if (stFilter === 'LOW_STOCK' && (q <= 0 || q > minQ)) return false;
                    if (stFilter === 'IN_STOCK' && q <= minQ) return false;
                }
                return true;
            });

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

            var table = hub.el('table', { class: 'ws-table' });
            var chkAll = hub.el('input', { type: 'checkbox' });
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
                    hub.el('th', { style: 'text-align: right; width: ' + (isStaff ? '100px' : '200px') + ';' }, ['Thao tác'])
                ])
            ]);

            var tbody = hub.el('tbody');
            var itemCheckboxes = [];

            var totalItems = items.length;
            var totalPages = Math.ceil(totalItems / pageSize) || 1;
            if (currentPage >= totalPages) currentPage = totalPages - 1;
            if (currentPage < 0) currentPage = 0;

            var startIdx = currentPage * pageSize;
            var pageItems = items.slice(startIdx, startIdx + pageSize);

            pageItems.forEach(function (s) {
                var q = s.qty != null ? s.qty : 0;
                var minQ = s.minStock != null ? s.minStock : 0;
                var unit = s.unit || '';
                var unitFormatted = formatMaterialUnit(unit);

                var rowChk = hub.el('input', { type: 'checkbox' });
                itemCheckboxes.push(rowChk);

                var nameEl = hub.el('div', { class: 'ws-link', style: 'font-weight: 700; cursor: pointer;' }, [s.name || ('NL #' + (s.materialId || s.id))]);
                nameEl.addEventListener('click', function () {
                    showMaterialDetailModal(branchSelect.value || branchId, s, rawMaterials, loadData, isStaff);
                });

                var catBadge = hub.el('span', { class: 'ws-badge', style: 'background: #f1f5f9; color: #334155;' }, [formatMaterialCategory(s.category)]);

                var unitTd = hub.el('div', { style: 'text-align: center;' }, [
                    hub.el('span', { class: 'ws-badge' }, [unitFormatted])
                ]);

                var qtyTd = hub.el('div', { style: 'text-align: right;' }, [
                    hub.el('strong', { style: 'color: #0f172a; font-size: 0.98rem;' }, [Number(q).toLocaleString('vi-VN')]),
                    hub.el('span', { class: 'muted', style: 'margin-left: 4px;' }, [unitFormatted])
                ]);

                var minTd = hub.el('div', { style: 'text-align: right;' }, [
                    hub.el('span', { class: 'muted' }, [Number(minQ).toLocaleString('vi-VN')]),
                    hub.el('span', { class: 'muted', style: 'font-size: 0.78rem; margin-left: 3px;' }, [unitFormatted])
                ]);

                var updatedTd = hub.el('span', { class: 'muted', style: 'font-size: 0.84rem; white-space: nowrap;' }, [s.updatedAt || '—']);

                var stBadge;
                if (q <= 0) stBadge = hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-red' }, ['Hết hàng']);
                else if (q <= minQ) stBadge = hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-orange' }, ['Sắp hết']);
                else stBadge = hub.el('span', { class: 'ws-badge ws-status-pill ws-badge-green' }, ['Còn hàng']);

                var stWrap = hub.el('div', { style: 'text-align: center;' }, [stBadge]);

                var btnHist = hub.el('button', { type: 'button', class: 'ws-btn secondary small' }, ['Lịch sử']);
                btnHist.addEventListener('click', function () {
                    showMaterialDetailModal(branchSelect.value || branchId, s, rawMaterials, loadData, isStaff);
                });
                var actionBtns = [btnHist];
                if (!isStaff) {
                    var btnEditMin = hub.el('button', { type: 'button', class: 'ws-btn secondary small', style: 'margin-left: 6px;' }, ['Sửa định mức']);
                    btnEditMin.addEventListener('click', function () {
                        showMaterialMinStockModal(s, loadData, branchSelect.value || branchId);
                    });
                    actionBtns.push(btnEditMin);
                }

                tbody.appendChild(hub.el('tr', {}, [
                    hub.el('td', { style: 'text-align: center;' }, [rowChk]),
                    hub.el('td', {}, [nameEl]),
                    hub.el('td', {}, [catBadge]),
                    hub.el('td', {}, [unitTd]),
                    hub.el('td', {}, [qtyTd]),
                    hub.el('td', {}, [minTd]),
                    hub.el('td', {}, [updatedTd]),
                    hub.el('td', {}, [stWrap]),
                    hub.el('td', { style: 'text-align: right;' }, actionBtns)
                ]));
            });

            chkAll.addEventListener('change', function () {
                var checked = chkAll.checked;
                itemCheckboxes.forEach(function (c) { c.checked = checked; });
            });

            table.appendChild(thead);
            table.appendChild(tbody);

            var startItem = totalItems === 0 ? 0 : startIdx + 1;
            var endItem = Math.min(startIdx + pageSize, totalItems);

            var paginationWrap = hub.el('div', { class: 'ws-pagination' });
            var infoEl = hub.el('div', { class: 'ws-pagination-info' }, [
                'Hiển thị ', hub.el('strong', {}, [String(startItem)]), '–', hub.el('strong', {}, [String(endItem)]),
                ' trên ', hub.el('strong', {}, [String(totalItems)]), ' mục'
            ]);
            paginationWrap.appendChild(infoEl);

            var controls = hub.el('div', { class: 'ws-pagination-controls' });
            var prevBtn = hub.el('button', { class: 'ws-pagination-btn', type: 'button', 'aria-label': 'Trang trước', disabled: currentPage === 0 }, ['←']);
            prevBtn.addEventListener('click', function () {
                if (currentPage > 0) { currentPage--; renderTable(items); }
            });
            controls.appendChild(prevBtn);

            for (var p = 0; p < totalPages; p++) {
                (function (pageIndex) {
                    var pBtn = hub.el('button', {
                        class: 'ws-pagination-btn' + (pageIndex === currentPage ? ' active' : ''),
                        type: 'button'
                    }, [String(pageIndex + 1)]);
                    pBtn.addEventListener('click', function () {
                        currentPage = pageIndex;
                        renderTable(items);
                    });
                    controls.appendChild(pBtn);
                })(p);
            }

            var nextBtn = hub.el('button', { class: 'ws-pagination-btn', type: 'button', 'aria-label': 'Trang sau', disabled: currentPage >= totalPages - 1 }, ['→']);
            nextBtn.addEventListener('click', function () {
                if (currentPage < totalPages - 1) { currentPage++; renderTable(items); }
            });
            controls.appendChild(nextBtn);
            paginationWrap.appendChild(controls);

            var sizeSel = hub.el('select', { class: 'ws-select', style: 'padding: 4px 8px; font-size: 0.85rem; height: 32px;' });
            [10, 20, 50, 100].forEach(function (n) {
                var opt = hub.el('option', { value: String(n) }, [String(n)]);
                if (n === pageSize) opt.selected = true;
                sizeSel.appendChild(opt);
            });
            sizeSel.addEventListener('change', function () {
                pageSize = parseInt(sizeSel.value, 10) || 10;
                currentPage = 0;
                renderTable(items);
            });
            var sizeWrap = hub.el('label', { class: 'ws-page-size' }, ['Mỗi trang:', sizeSel]);
            paginationWrap.appendChild(sizeWrap);

            tableRegion.replaceChildren(table, paginationWrap);
        }

        if (branchSelect.tagName === 'SELECT') branchSelect.addEventListener('change', loadData);
        searchInput.addEventListener('input', applyFilters);
        groupSelect.addEventListener('change', applyFilters);
        statusSelect.addEventListener('change', applyFilters);
        sortSelect.addEventListener('change', applyFilters);

        loadData();
    }




    inv.showMaterialMinStockModal = showMaterialMinStockModal;
    inv.showMaterialDetailModal = showMaterialDetailModal;
    inv.showMaterialRestockModal = showMaterialRestockModal;
    inv.showMaterialAdjustModal = showMaterialAdjustModal;
    inv.renderMaterialInventoryPage = renderMaterialInventoryPage;
})();
