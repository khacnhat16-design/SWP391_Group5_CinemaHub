/**
 * inventory-product.js — Quản lý Kho Thành Phẩm (Bắp nước bán lẻ & Combo).
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var inv = window.CinemaHubInventory;
    var list = window.CinemaHubList;
    if (!hub || !inv) return;

    var notifyError = inv.notifyError;
    var createInventoryModal = inv.createInventoryModal;
    var makeInvField = inv.makeInvField;
    var makeInvFooter = inv.makeInvFooter;
    var makeInvRadio = inv.makeInvRadio;
    var makeStatCard = inv.makeStatCard;
    var formatInventoryReason = inv.formatInventoryReason;
    var formatMoneyVnd = inv.formatMoneyVnd;
    var formatProductType = inv.formatProductType;
    var setFieldError = inv.setFieldError;
    var clearFieldError = inv.clearFieldError;

    // 1. Modal xem chi tiết sản phẩm và lịch sử biến động kho
    function showProductDetailModal(branchId, product) {
        var pId = product.productId || product.id;
        var qty = product.qty != null ? product.qty : 0;
        var unit = product.unit || '';
        var updatedText = product.updatedAt || '—';
        var statusBadgeClass = qty <= 0 ? 'ws-badge-red' : (qty < 10 ? 'ws-badge-orange' : 'ws-badge-green');
        var statusText = qty <= 0 ? 'Hết hàng' : (qty < 10 ? ('Sắp hết (' + qty + ')') : 'Còn hàng');

        var infoCard = hub.el('div', { class: 'ws-inv-info-card' }, [
            hub.el('div', { class: 'info-head' }, [
                hub.el('div', { class: 'info-row' }, [
                    hub.el('div', { class: 'ws-inv-avatar blue' }, ['SP']),
                    hub.el('div', {}, [
                        hub.el('h4', { class: 'info-title' }, [product.name || ('Sản phẩm #' + pId)]),
                        hub.el('div', { class: 'info-meta' }, [
                            hub.el('span', {}, ['Loại: ', hub.el('strong', {}, [formatProductType(product.type)])]),
                            hub.el('span', {}, ['Tồn hiện tại: ', hub.el('strong', {}, [qty + ' ' + unit])]),
                            hub.el('span', {}, ['Cập nhật: ', hub.el('strong', {}, [updatedText])])
                        ])
                    ])
                ]),
                hub.el('span', { class: 'ws-badge ws-status-pill ' + statusBadgeClass }, [statusText])
            ])
        ]);

        var historyTitle = hub.el('h5', { class: 'ws-inv-label' }, ['Lịch sử biến động']);
        var tableWrap = hub.el('div', { class: 'ws-inv-history-wrap' });
        tableWrap.appendChild(hub.el('p', { class: 'muted', style: 'padding: 24px; text-align: center; margin: 0;' }, ['Đang tải lịch sử…']));

        var paginationWrap = hub.el('div', { style: 'display: flex; justify-content: flex-end; align-items: center; gap: 6px; margin-top: 12px;' });
        var closeFoot = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-secondary', type: 'button', style: 'width: 100%;' }, ['Đóng']);
        var footWrap = hub.el('div', { class: 'ws-inv-modal-footer' }, [closeFoot]);

        var modal = createInventoryModal({
            title: 'Chi tiết tồn kho & Lịch sử biến động',
            subtitle: 'Lịch sử biến động tồn kho sản phẩm',
            theme: 'blue',
            maxWidth: '720px',
            body: [infoCard, historyTitle, tableWrap, paginationWrap],
            footer: footWrap
        });
        closeFoot.addEventListener('click', modal.close);

        hub.api('/inventory?action=history&branchId=' + branchId + '&productId=' + pId)
            .then(function (res) {
                var rawList = (res && res.data) || res || [];
                if (!Array.isArray(rawList) || rawList.length === 0) {
                    tableWrap.replaceChildren(hub.el('p', { class: 'muted', style: 'padding: 24px; text-align: center; margin: 0;' }, ['Chưa có lịch sử biến động nào cho sản phẩm này.']));
                    return;
                }

                var pageSize = 5;
                var curPage = 0;
                var totalPages = Math.ceil(rawList.length / pageSize);

                function renderHistoryTable() {
                    var table = hub.el('table', { class: 'ws-inv-history-table' });
                    var thead = hub.el('thead', {}, [
                        hub.el('tr', {}, [
                            hub.el('th', {}, ['Ngày giờ']),
                            hub.el('th', {}, ['Tăng/giảm']),
                            hub.el('th', {}, ['Số lượng']),
                            hub.el('th', {}, ['Lý do']),
                            hub.el('th', {}, ['Người thực hiện'])
                        ])
                    ]);
                    var tbody = hub.el('tbody');
                    var start = curPage * pageSize;
                    var pageItems = rawList.slice(start, start + pageSize);

                    pageItems.forEach(function (h) {
                        var isPlus = h.deltaQty > 0;
                        var sign = isPlus ? '+' : '-';
                        var absQty = Math.abs(h.deltaQty);

                        tbody.appendChild(hub.el('tr', {}, [
                            hub.el('td', { class: 'muted', style: 'white-space: nowrap;' }, [h.createdAt || '—']),
                            hub.el('td', {}, [
                                hub.el('span', { class: 'ws-inv-badge-sign ' + (isPlus ? 'plus' : 'minus') }, [sign])
                            ]),
                            hub.el('td', { style: 'font-weight: 600;' }, [absQty + ' ' + unit]),
                            hub.el('td', {}, [formatInventoryReason(h.reason)]),
                            hub.el('td', { style: 'font-weight: 600;' }, [h.actorName || ('ID: ' + h.actorId)])
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

    var showDetailModal = showProductDetailModal;
    var showInventoryHistoryModal = showProductDetailModal;

    // 2. Modal nhập kho thành phẩm mới
    function showProductRestockModal(branchId, stockItems, onSuccess) {
        var productSelect = hub.el('select', { class: 'ws-select' });
        productSelect.appendChild(hub.el('option', { value: '' }, ['Chọn sản phẩm…']));
        (stockItems || []).filter(function (it) {
            return !it.productStatus || it.productStatus.toUpperCase() === 'ACTIVE' || it.status === 'ACTIVE';
        }).forEach(function (it) {
            var pId = it.productId || it.id;
            productSelect.appendChild(hub.el('option', { value: String(pId) }, [it.name + ' (' + (it.type || '') + ')']));
        });

        var qtyInput = hub.el('input', { type: 'number', min: '1', placeholder: 'Nhập số lượng (> 0)', class: 'ws-input' });

        var reasonSelect = hub.el('select', { class: 'ws-select' });
        [
            { value: '', label: 'Chọn lý do…' },
            { value: 'NHAP_HANG_TU_NCC', label: 'Nhập hàng từ nhà cung cấp' },
            { value: 'NHAP_LAI_TU_NCC', label: 'Nhập lại từ NCC' },
            { value: 'KIEM_KE_THUA', label: 'Kiểm kê thừa' },
            { value: 'HOAN_TU_DON_HUY', label: 'Hoàn từ đơn hủy' },
            { value: 'DIEU_CHINH_KHAC', label: 'Điều chỉnh khác' }
        ].forEach(function (opt) {
            reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
        });

        var noteInput = hub.el('input', { type: 'text', placeholder: 'Nhập ghi chú thêm (bắt buộc ít nhất 10 ký tự nếu chọn Điều chỉnh khác)…', class: 'ws-input' });

        var cancelBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-secondary', style: 'flex: 1;' }, ['Hủy']);
        var confirmBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-primary blue', style: 'flex: 1;' }, ['Xác nhận']);
        var footer = makeInvFooter(cancelBtn, confirmBtn);

        var modal = createInventoryModal({
            title: 'Nhập kho thành phẩm',
            subtitle: 'Tăng số lượng tồn kho theo chi nhánh',
            theme: 'blue',
            maxWidth: '480px',
            body: [
                makeInvField('Sản phẩm', productSelect, true),
                makeInvField('Số lượng nhập', qtyInput, true),
                makeInvField('Lý do', reasonSelect, true),
                makeInvField('Ghi chú', noteInput, false)
            ],
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(productSelect);
            clearFieldError(qtyInput);
            clearFieldError(reasonSelect);
            clearFieldError(noteInput);

            var hasError = false;
            var firstErr = null;

            var pId = productSelect.value;
            if (!pId) {
                setFieldError(productSelect, 'Vui lòng chọn sản phẩm.');
                hasError = true;
                if (!firstErr) firstErr = productSelect;
            }

            var qtyRaw = (qtyInput.value || '').trim();
            var qtyVal = parseInt(qtyRaw, 10);
            if (!qtyRaw || isNaN(qtyVal) || qtyVal <= 0) {
                setFieldError(qtyInput, 'Số lượng nhập phải lớn hơn 0.');
                hasError = true;
                if (!firstErr) firstErr = qtyInput;
            }

            var rVal = reasonSelect.value;
            if (!rVal) {
                setFieldError(reasonSelect, 'Vui lòng chọn lý do nhập kho.');
                hasError = true;
                if (!firstErr) firstErr = reasonSelect;
            }

            var note = (noteInput.value || '').trim();
            if (rVal === 'DIEU_CHINH_KHAC' && note.length < 10) {
                setFieldError(noteInput, 'Khi chọn lý do "Điều chỉnh khác", ghi chú bắt buộc phải có ít nhất 10 ký tự.');
                hasError = true;
                if (!firstErr) firstErr = noteInput;
            }

            if (hasError) {
                if (firstErr) firstErr.focus();
                return;
            }

            var rLabel = reasonSelect.options[reasonSelect.selectedIndex].text;
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
                modal.close();
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
        var productSelect = hub.el('select', { class: 'ws-select' });
        productSelect.appendChild(hub.el('option', { value: '' }, ['Chọn sản phẩm…']));
        (stockItems || []).forEach(function (it) {
            var pId = it.productId || it.id;
            productSelect.appendChild(hub.el('option', { value: String(pId) }, [it.name + ' (' + (it.qty || 0) + ' ' + (it.unit || '') + ')']));
        });
        if (selectedProduct && (selectedProduct.productId || selectedProduct.id)) {
            productSelect.value = String(selectedProduct.productId || selectedProduct.id);
        }

        var currentQtyDisplay = hub.el('strong', {}, ['0']);
        var currentQtyWrap = hub.el('div', { class: 'ws-inv-hint' }, ['Tồn hiện tại: ', currentQtyDisplay]);

        function updateCurrentQty() {
            var selectedId = productSelect.value;
            var found = (stockItems || []).find(function (it) { return String(it.productId || it.id) === selectedId; });
            var q = found ? (found.qty || 0) : 0;
            currentQtyDisplay.textContent = String(q);
        }
        productSelect.addEventListener('change', updateCurrentQty);
        updateCurrentQty();

        var radioMinusObj = makeInvRadio('m4AdjustType', 'MINUS', 'GIẢM', 'minus', true);
        var radioPlusObj = makeInvRadio('m4AdjustType', 'PLUS', 'TĂNG', 'plus', false);
        var radioSetObj = makeInvRadio('m4AdjustType', 'SET', 'SỬA THẲNG', 'set', false);
        var radioMinus = radioMinusObj.radio;
        var radioPlus = radioPlusObj.radio;
        var radioSet = radioSetObj.radio;
        var typeGroup = hub.el('div', { class: 'ws-inv-group' }, [
            hub.el('label', { class: 'ws-inv-label' }, ['Loại điều chỉnh', hub.el('span', { class: 'ws-inv-req' }, [' *'])]),
            hub.el('div', { class: 'ws-inv-radio-group' }, [radioMinusObj.label, radioPlusObj.label, radioSetObj.label])
        ]);

        var qtyInput = hub.el('input', { type: 'number', min: '1', placeholder: 'Nhập số lượng giảm (> 0)…', class: 'ws-input' });
        var qtyField = makeInvField('Số lượng giảm', qtyInput, true);

        var reasonSelect = hub.el('select', { class: 'ws-select' });
        var reasonsDecrease = [
            { value: '', label: 'Chọn lý do…' },
            { value: 'HU_HONG', label: 'Hư hỏng / Rách vỡ' },
            { value: 'HET_HAN', label: 'Hết hạn sử dụng' },
            { value: 'MAT_DO_KHACH', label: 'Thất thoát / Mất do khách' },
            { value: 'KIEM_KE_THIEU', label: 'Kiểm kê thiếu' },
            { value: 'KHAC', label: 'Lý do khác' }
        ];
        var reasonsIncrease = [
            { value: '', label: 'Chọn lý do…' },
            { value: 'KIEM_KE_THUA', label: 'Kiểm kê thừa' },
            { value: 'DIEU_CHINH_KHAC', label: 'Điều chỉnh khác' },
            { value: 'KHAC', label: 'Lý do khác' }
        ];

        function updateReasonOptions() {
            var opts = reasonsDecrease;
            if (radioPlus.checked) {
                opts = reasonsIncrease;
            } else if (radioSet.checked) {
                var curQ = parseInt(currentQtyDisplay.textContent, 10) || 0;
                var setVal = parseInt(qtyInput.value, 10) || 0;
                opts = setVal >= curQ ? reasonsIncrease : reasonsDecrease;
            }
            reasonSelect.replaceChildren();
            opts.forEach(function (opt) {
                reasonSelect.appendChild(hub.el('option', { value: opt.value }, [opt.label]));
            });
        }

        function onTypeChange() {
            var lbl = qtyField.querySelector('.ws-inv-label');
            if (radioMinus.checked) {
                if (lbl) lbl.innerHTML = 'Số lượng giảm <span class="ws-inv-req">*</span>';
                qtyInput.placeholder = 'Nhập số lượng giảm (> 0)…';
            } else if (radioPlus.checked) {
                if (lbl) lbl.innerHTML = 'Số lượng tăng <span class="ws-inv-req">*</span>';
                qtyInput.placeholder = 'Nhập số lượng tăng (> 0)…';
            } else {
                if (lbl) lbl.innerHTML = 'Tồn kho thực tế mới (Set value) <span class="ws-inv-req">*</span>';
                qtyInput.placeholder = 'Nhập số lượng tồn đếm thực tế (>= 0)…';
            }
            updateReasonOptions();
        }

        radioPlus.addEventListener('change', onTypeChange);
        radioMinus.addEventListener('change', onTypeChange);
        radioSet.addEventListener('change', onTypeChange);
        qtyInput.addEventListener('input', function () {
            if (radioSet.checked) updateReasonOptions();
        });
        onTypeChange();

        var noteInput = hub.el('input', { type: 'text', placeholder: 'Nhập ghi chú thêm (bắt buộc ít nhất 10 ký tự nếu chọn Điều chỉnh khác)…', class: 'ws-input' });

        var cancelBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-secondary', style: 'flex: 1;' }, ['Hủy']);
        var confirmBtn = hub.el('button', { type: 'button', class: 'ws-inv-btn ws-inv-btn-primary blue', style: 'flex: 1;' }, ['Xác nhận']);
        var footer = makeInvFooter(cancelBtn, confirmBtn);

        var modal = createInventoryModal({
            title: 'Điều chỉnh tồn kho thành phẩm',
            subtitle: 'Tăng/giảm hoặc cân bằng số lượng tồn kho theo chi nhánh',
            theme: 'blue',
            maxWidth: '480px',
            body: [
                makeInvField('Sản phẩm', productSelect, true),
                currentQtyWrap,
                typeGroup,
                qtyField,
                makeInvField('Lý do', reasonSelect, true),
                makeInvField('Ghi chú', noteInput, false)
            ],
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        confirmBtn.addEventListener('click', function () {
            clearFieldError(productSelect);
            clearFieldError(qtyInput);
            clearFieldError(reasonSelect);
            clearFieldError(noteInput);

            var hasError = false;
            var firstErr = null;

            var pId = productSelect.value;
            if (!pId) {
                setFieldError(productSelect, 'Vui lòng chọn sản phẩm.');
                hasError = true;
                if (!firstErr) firstErr = productSelect;
            }

            var qtyRaw = (qtyInput.value || '').trim();
            var qtyVal = parseInt(qtyRaw, 10);
            var currentQty = parseInt(currentQtyDisplay.textContent, 10) || 0;
            var deltaQty = 0;

            if (!qtyRaw || isNaN(qtyVal)) {
                setFieldError(qtyInput, 'Vui lòng nhập số lượng hợp lệ.');
                hasError = true;
                if (!firstErr) firstErr = qtyInput;
            } else if (radioMinus.checked) {
                if (qtyVal <= 0) {
                    setFieldError(qtyInput, 'Số lượng giảm phải lớn hơn 0.');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else if (pId && qtyVal > currentQty) {
                    setFieldError(qtyInput, 'Số lượng giảm (' + qtyVal + ') không được vượt quá tồn kho hiện tại (' + currentQty + ')!');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else {
                    deltaQty = -qtyVal;
                }
            } else if (radioPlus.checked) {
                if (qtyVal <= 0) {
                    setFieldError(qtyInput, 'Số lượng tăng phải lớn hơn 0.');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else {
                    deltaQty = qtyVal;
                }
            } else if (radioSet.checked) {
                if (qtyVal < 0) {
                    setFieldError(qtyInput, 'Số tồn kho mới không được âm.');
                    hasError = true;
                    if (!firstErr) firstErr = qtyInput;
                } else {
                    deltaQty = qtyVal - currentQty;
                    if (pId && deltaQty === 0) {
                        setFieldError(qtyInput, 'Số tồn mới bằng tồn hiện tại, không có chênh lệch để điều chỉnh.');
                        hasError = true;
                        if (!firstErr) firstErr = qtyInput;
                    }
                }
            }

            var reasonVal = reasonSelect.value;
            if (!reasonVal) {
                setFieldError(reasonSelect, 'Vui lòng chọn lý do.');
                hasError = true;
                if (!firstErr) firstErr = reasonSelect;
            }

            var noteVal = (noteInput.value || '').trim();
            if ((reasonVal === 'DIEU_CHINH_KHAC' || reasonVal === 'KHAC') && noteVal.length < 10) {
                setFieldError(noteInput, 'Khi chọn lý do Khác / Điều chỉnh khác, ghi chú bắt buộc phải có ít nhất 10 ký tự.');
                hasError = true;
                if (!firstErr) firstErr = noteInput;
            }

            if (hasError) {
                if (firstErr) firstErr.focus();
                return;
            }

            var reasonLabel = reasonSelect.options[reasonSelect.selectedIndex].text;
            var finalReason = reasonLabel + (noteVal ? (' - ' + noteVal) : '');

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
                modal.close();
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
                type: 'button'
            }, ['Điều chỉnh kho']);
            btnAdjust.addEventListener('click', function () {
                if (!currentBranchId) { hub.notify('Vui lòng chọn chi nhánh trước.', 'warn'); return; }
                showProductAdjustModal(currentBranchId, null, currentItems, refreshProductList);
            });

            var btnRestock = hub.el('button', {
                class: 'ws-btn primary',
                type: 'button'
            }, ['+ Nhập kho mới']);
            btnRestock.addEventListener('click', function () {
                if (!currentBranchId) { hub.notify('Vui lòng chọn chi nhánh trước.', 'warn'); return; }
                showProductRestockModal(currentBranchId, currentItems, refreshProductList);
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
                        { value: 'POPCORN', label: 'Bắp rang' },
                        { value: 'DRINK', label: 'Nước uống' },
                        { value: 'COMBO', label: 'Combo' },
                        { value: 'OTHER', label: 'Khác' }
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
                if (!activeBranch) return Promise.resolve({ items: [], total: 0 });

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
                        var elName = hub.el('strong', { class: 'ws-link', style: 'cursor: pointer;' }, [s.name]);
                        elName.addEventListener('click', function () {
                            showProductDetailModal(currentBranchId, s);
                        });
                        return elName;
                    }
                },
                {
                    label: 'Loại',
                    render: function (s) {
                        return hub.el('span', {}, [formatProductType(s.type)]);
                    }
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

    inv.showProductDetailModal = showProductDetailModal;
    inv.showProductRestockModal = showProductRestockModal;
    inv.showProductAdjustModal = showProductAdjustModal;
    inv.renderProductInventoryPage = renderProductInventoryPage;
})();
