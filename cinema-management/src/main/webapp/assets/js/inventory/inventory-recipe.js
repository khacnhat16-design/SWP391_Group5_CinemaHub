/**
 * inventory-recipe.js — Quản lý Công thức Định mức (BOM) & Xuất kho Sản xuất.
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
    var makeStatCard = inv.makeStatCard;
    var formatMaterialUnit = inv.formatMaterialUnit;
    var formatMoneyVnd = inv.formatMoneyVnd;
    var formatDosageNumber = inv.formatDosageNumber;
    var isDiscreteUnit = inv.isDiscreteUnit;
    var getLossPercentage = inv.getLossPercentage;
    var getLossRatio = inv.getLossRatio;
    var resolveCookingTime = inv.resolveCookingTime;

    function formatProductType(type) {
        if (!type) return 'Món';
        var t = String(type).trim().toUpperCase();
        if (t === 'POPCORN' || t === 'FOOD') return 'Bắp rang';
        if (t === 'DRINK') return 'Nước uống';
        if (t === 'COMBO') return 'Combo';
        return type;
    }

    function showRecipeEditorModal(product, existingRecipe, rawMaterials, onReload, allProducts) {
        var selectableProducts = (allProducts || []).filter(function (p) {
            return (p.type || p.productType || '').toUpperCase() !== 'COMBO';
        });
        var currentProduct = product || (selectableProducts && selectableProducts[0]) || null;
        var pId = currentProduct ? (currentProduct.productId || currentProduct.id) : null;
        var items = [];

        if (existingRecipe && Array.isArray(existingRecipe) && existingRecipe.length > 0) {
            items = existingRecipe.map(function (it) {
                return {
                    materialId: it.materialId,
                    dosage: it.dosage,
                    lossRate: it.lossRate || 0,
                    note: it.note || ''
                };
            });
        } else if (currentProduct && Array.isArray(currentProduct.items) && currentProduct.items.length > 0) {
            items = currentProduct.items.map(function (it) {
                return {
                    materialId: it.materialId,
                    dosage: it.dosage,
                    lossRate: it.lossRate || 0,
                    note: it.note || ''
                };
            });
        }

        // Nếu chưa có dòng nào thì thêm 1 dòng mẫu
        if (items.length === 0 && rawMaterials && rawMaterials.length > 0) {
            var firstMat = rawMaterials[0];
            var isDiscFirst = isDiscreteUnit(firstMat ? firstMat.unit : null);
            items.push({
                materialId: firstMat.id || firstMat.materialId,
                dosage: isDiscFirst ? 1 : 0.1,
                lossRate: isDiscFirst ? 0 : 5,
                note: ''
            });
        }

        // Header info
        var prodSelectWrap = hub.el('div', { style: 'margin-bottom: 16px;' });
        var prodSelect = null;
        if (!product && selectableProducts && selectableProducts.length > 0) {
            prodSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; font-size: 0.95rem; font-weight: 600;' });
            selectableProducts.forEach(function (p) {
                var pTypeVn = formatProductType(p.productType || p.type);
                var opt = hub.el('option', { value: String(p.productId || p.id) }, [
                    (p.productName || p.name) + ' (' + pTypeVn + ' - ' + formatMoneyVnd(p.price || p.productPrice) + ')'
                ]);
                if (String(p.productId || p.id) === String(pId)) opt.selected = true;
                prodSelect.appendChild(opt);
            });
            prodSelectWrap.appendChild(hub.el('label', { class: 'ws-inv-label', style: 'margin-bottom: 6px; display: block;' }, [
                'Chọn sản phẩm cần thiết lập công thức (Chỉ món chế biến lẻ):',
                hub.el('span', { class: 'ws-inv-req' }, [' *'])
            ]));
            prodSelectWrap.appendChild(prodSelect);
            prodSelect.addEventListener('change', function () {
                var selectedId = prodSelect.value;
                currentProduct = selectableProducts.find(function (p) { return String(p.productId || p.id) === String(selectedId); }) || currentProduct;
                pId = currentProduct ? (currentProduct.productId || currentProduct.id) : null;
                recalculateEconomics();
            });
        } else if (currentProduct) {
            var cTime = currentProduct.cookingTime || resolveCookingTime(currentProduct.type || currentProduct.productType);
            prodSelectWrap.appendChild(hub.el('div', { class: 'ws-inv-info-card' }, [
                hub.el('div', { class: 'info-head' }, [
                    hub.el('div', { class: 'info-row' }, [
                        hub.el('div', { class: 'ws-inv-avatar blue' }, ['CT']),
                        hub.el('div', {}, [
                            hub.el('h4', { class: 'info-title' }, [currentProduct.name || currentProduct.productName || ('Sản phẩm #' + pId)]),
                            hub.el('div', { class: 'info-meta' }, [
                                hub.el('span', {}, ['Phân loại: ', hub.el('strong', {}, [formatProductType(currentProduct.type || currentProduct.productType)])]),
                                hub.el('span', {}, ['Giá bán: ', hub.el('strong', { style: 'color: #16a34a;' }, [formatMoneyVnd(currentProduct.price != null ? currentProduct.price : currentProduct.productPrice)])]),
                                hub.el('span', {}, ['Thời gian chuẩn: ', hub.el('strong', { style: 'color: #7c3aed;' }, [cTime])])
                            ])
                        ])
                    ]),
                    hub.el('span', { class: 'ws-badge ws-status-pill ' + (items.length > 0 ? 'good' : 'warning') }, [
                        items.length > 0 ? (items.length + ' nguyên liệu') : 'Chưa có công thức'
                    ])
                ])
            ]));
        }

        var tableWrap = hub.el('div', { style: 'overflow-x: auto; margin-top: 10px; border: 1px solid #e2e8f0; border-radius: 8px;' });
        var table = hub.el('table', { class: 'ws-recipe-table' });
        var thead = hub.el('thead', {}, [
            hub.el('tr', {}, [
                hub.el('th', { style: 'width: 28%;' }, ['Nguyên liệu']),
                hub.el('th', { style: 'width: 14%; text-align: right;' }, ['Định lượng']),
                hub.el('th', { style: 'width: 8%; text-align: center;' }, ['ĐVT']),
                hub.el('th', { style: 'width: 12%; text-align: right;' }, ['% Hao hụt']),
                hub.el('th', { style: 'width: 14%; text-align: right;' }, ['Thực tế']),
                hub.el('th', { style: 'width: 14%; text-align: right;' }, ['Thành tiền']),
                hub.el('th', { style: 'width: 10%; text-align: center;' }, ['Xóa'])
            ])
        ]);
        var tbody = hub.el('tbody');
        table.appendChild(thead);
        table.appendChild(tbody);
        tableWrap.appendChild(table);

        var addRowBtn = hub.el('button', {
            class: 'ws-btn ws-btn-secondary',
            type: 'button',
            style: 'margin-top: 12px; display: inline-flex; align-items: center; gap: 6px; font-weight: 600;'
        }, ['Thêm nguyên liệu']);

        // Economic Summary Cards
        var sumCostEl = hub.el('div', { class: 'ws-recipe-summary-val cost' }, ['0 đ']);
        var sumPriceEl = hub.el('div', { class: 'ws-recipe-summary-val' }, ['0 đ']);
        var sumFoodCostPctEl = hub.el('div', { class: 'ws-recipe-summary-val' }, ['0%']);
        var sumProfitEl = hub.el('div', { class: 'ws-recipe-summary-val profit' }, ['0 đ']);

        var summaryBox = hub.el('div', { class: 'ws-recipe-summary-box' }, [
            hub.el('div', { class: 'ws-recipe-summary-item' }, [
                hub.el('div', { class: 'ws-recipe-summary-label' }, ['Chi phí nguyên liệu']),
                sumCostEl
            ]),
            hub.el('div', { class: 'ws-recipe-summary-item' }, [
                hub.el('div', { class: 'ws-recipe-summary-label' }, ['Giá bán niêm yết']),
                sumPriceEl
            ]),
            hub.el('div', { class: 'ws-recipe-summary-item' }, [
                hub.el('div', { class: 'ws-recipe-summary-label' }, ['Tỷ lệ giá vốn (%)']),
                sumFoodCostPctEl
            ]),
            hub.el('div', { class: 'ws-recipe-summary-item' }, [
                hub.el('div', { class: 'ws-recipe-summary-label' }, ['Lãi gộp ước tính']),
                sumProfitEl
            ])
        ]);

        var errorBanner = hub.el('div', { class: 'ws-alert-banner danger', style: 'display: none;' });

        function findMaterial(mId) {
            return (rawMaterials || []).find(function (m) {
                return String(m.id || m.materialId) === String(mId);
            });
        }

        function recalculateEconomics() {
            var totalCost = 0;
            var rows = tbody.querySelectorAll('tr[data-idx]');
            rows.forEach(function (tr) {
                var selMat = tr.querySelector('.recipe-mat-select');
                var inpDosage = tr.querySelector('.recipe-dosage-input');
                var inpLoss = tr.querySelector('.recipe-loss-input');
                var cellEff = tr.querySelector('.recipe-effective-cell');
                var cellCost = tr.querySelector('.recipe-cost-cell');
                var cellUnit = tr.querySelector('.recipe-unit-cell');

                var m = findMaterial(selMat ? selMat.value : null);
                if (cellUnit) cellUnit.textContent = m ? formatMaterialUnit(m.unit) : '—';

                var isDiscrete = isDiscreteUnit(m ? m.unit : null);
                var dosage = parseFloat(inpDosage ? inpDosage.value : 0) || 0;
                var lossRate = parseFloat(inpLoss ? inpLoss.value : 0) || 0;
                var lossRatio = isDiscrete ? 0 : getLossRatio(lossRate);
                var eff = isDiscrete ? Math.max(1, Math.ceil(dosage)) : (dosage * (1 + lossRatio));
                if (cellEff) cellEff.textContent = formatDosageNumber(eff);

                var costPrice = (m && m.costPrice != null) ? m.costPrice : 0;
                var lineCost = eff * costPrice;
                if (cellCost) cellCost.textContent = formatMoneyVnd(Math.round(lineCost));
                totalCost += lineCost;
            });

            var price = currentProduct ? (currentProduct.price || currentProduct.productPrice || 0) : 0;
            sumCostEl.textContent = formatMoneyVnd(Math.round(totalCost));
            sumPriceEl.textContent = formatMoneyVnd(price);

            var pct = price > 0 ? Math.round((totalCost / price) * 100) : 0;
            sumFoodCostPctEl.textContent = pct + '%';
            if (pct <= 35) {
                sumFoodCostPctEl.style.color = '#16a34a';
            } else if (pct <= 50) {
                sumFoodCostPctEl.style.color = '#d97706';
            } else {
                sumFoodCostPctEl.style.color = '#dc2626';
            }

            var profit = price - totalCost;
            sumProfitEl.textContent = formatMoneyVnd(Math.round(profit));
            if (profit >= 0) {
                sumProfitEl.style.color = '#16a34a';
            } else {
                sumProfitEl.style.color = '#dc2626';
            }
        }

        function renderRows() {
            tbody.innerHTML = '';
            if (items.length === 0) {
                var emptyTr = hub.el('tr', {}, [
                    hub.el('td', { colspan: '7', style: 'text-align: center; color: #64748b; padding: 24px;' }, [
                        'Chưa có nguyên liệu nào trong công thức. Bấm "+ Thêm nguyên liệu" bên dưới.'
                    ])
                ]);
                tbody.appendChild(emptyTr);
                recalculateEconomics();
                return;
            }

            items.forEach(function (item, idx) {
                var tr = hub.el('tr', { 'data-idx': String(idx) });

                var curM = findMaterial(item.materialId);
                var isDiscrete = isDiscreteUnit(curM ? curM.unit : null);

                // Cột 1: Select nguyên liệu
                var selMat = hub.el('select', { class: 'ws-select recipe-mat-select', style: 'width: 100%; font-size: 0.85rem;' });
                (rawMaterials || []).forEach(function (m) {
                    var mIdVal = m.id || m.materialId;
                    var opt = hub.el('option', { value: String(mIdVal) }, [
                        m.name + ' (' + formatMoneyVnd(m.costPrice) + '/' + formatMaterialUnit(m.unit) + ')'
                    ]);
                    if (String(mIdVal) === String(item.materialId)) opt.selected = true;
                    selMat.appendChild(opt);
                });
                var tdMat = hub.el('td', {}, [selMat]);

                // Cột 2: Định lượng cơ bản
                var initDosage = item.dosage != null ? item.dosage : (isDiscrete ? 1 : 0.1);
                if (isDiscrete && (!Number.isInteger(initDosage) || initDosage < 1)) {
                    initDosage = Math.max(1, Math.round(initDosage));
                }
                item.dosage = initDosage;

                var inpDosage = hub.el('input', {
                    type: 'number',
                    class: 'ws-input recipe-dosage-input',
                    step: isDiscrete ? '1' : '0.001',
                    min: isDiscrete ? '1' : '0.001',
                    placeholder: isDiscrete ? '1' : '0.1',
                    value: String(initDosage),
                    style: 'width: 100%; text-align: right; font-weight: 600;'
                });
                var tdDosage = hub.el('td', {}, [inpDosage]);

                // Cột 3: Đơn vị tính
                var tdUnit = hub.el('td', { class: 'recipe-unit-cell', style: 'text-align: center; font-weight: 500; color: #475569;' }, [
                    curM ? formatMaterialUnit(curM.unit) : '—'
                ]);

                // Cột 4: % Hao hụt
                var initLossPct = isDiscrete ? 0 : getLossPercentage(item.lossRate);
                var inpLoss = hub.el('input', {
                    type: 'number',
                    class: 'ws-input recipe-loss-input',
                    step: '0.5',
                    min: '0',
                    max: '100',
                    placeholder: '0',
                    value: String(initLossPct),
                    disabled: isDiscrete,
                    style: 'width: 100%; text-align: right;' + (isDiscrete ? ' background: #f1f5f9; cursor: not-allowed;' : ''),
                    title: isDiscrete ? 'Bao bì đếm chiếc không áp dụng hao hụt' : ''
                });
                var tdLoss = hub.el('td', {}, [inpLoss]);

                selMat.addEventListener('change', function () {
                    item.materialId = selMat.value;
                    var newM = findMaterial(item.materialId);
                    var newDiscrete = isDiscreteUnit(newM ? newM.unit : null);
                    if (newDiscrete) {
                        inpDosage.step = '1';
                        inpDosage.min = '1';
                        inpDosage.placeholder = '1';
                        var curVal = parseInt(inpDosage.value, 10);
                        if (isNaN(curVal) || curVal < 1) {
                            inpDosage.value = '1';
                            item.dosage = 1;
                        } else {
                            inpDosage.value = String(curVal);
                            item.dosage = curVal;
                        }
                        inpLoss.value = '0';
                        inpLoss.disabled = true;
                        inpLoss.style.background = '#f1f5f9';
                        inpLoss.style.cursor = 'not-allowed';
                        inpLoss.title = 'Bao bì đếm chiếc không áp dụng hao hụt';
                        item.lossRate = 0;
                    } else {
                        inpDosage.step = '0.001';
                        inpDosage.min = '0.001';
                        inpDosage.placeholder = '0.1';
                        inpLoss.disabled = false;
                        inpLoss.style.background = '';
                        inpLoss.style.cursor = '';
                        inpLoss.title = '';
                    }
                    recalculateEconomics();
                });

                inpDosage.addEventListener('input', function () {
                    var val = parseFloat(inpDosage.value) || 0;
                    var thisM = findMaterial(selMat.value);
                    if (isDiscreteUnit(thisM ? thisM.unit : null)) {
                        val = Math.max(1, Math.round(val));
                    }
                    item.dosage = val;
                    recalculateEconomics();
                });

                inpLoss.addEventListener('input', function () {
                    item.lossRate = parseFloat(inpLoss.value) || 0;
                    recalculateEconomics();
                });

                // Cột 5: Định lượng thực tế
                var tdEff = hub.el('td', { class: 'recipe-effective-cell', style: 'text-align: right; font-weight: 600; color: #0f172a;' }, ['0']);

                // Cột 6: Thành tiền ước tính
                var tdCost = hub.el('td', { class: 'recipe-cost-cell', style: 'text-align: right; font-weight: 600; color: #7c3aed;' }, ['0 đ']);

                // Cột 7: Nút xóa dòng
                var btnDel = hub.el('button', {
                    type: 'button',
                    class: 'ws-btn danger sm',
                    style: 'padding: 4px 8px; border-radius: 4px;',
                    title: 'Xóa nguyên liệu này khỏi công thức'
                }, ['✕']);
                btnDel.addEventListener('click', function () {
                    items.splice(idx, 1);
                    renderRows();
                });
                var tdDel = hub.el('td', { style: 'text-align: center;' }, [btnDel]);

                tr.appendChild(tdMat);
                tr.appendChild(tdDosage);
                tr.appendChild(tdUnit);
                tr.appendChild(tdLoss);
                tr.appendChild(tdEff);
                tr.appendChild(tdCost);
                tr.appendChild(tdDel);
                tbody.appendChild(tr);
            });

            recalculateEconomics();
        }

        addRowBtn.addEventListener('click', function () {
            var firstMat = rawMaterials && rawMaterials[0] ? rawMaterials[0] : null;
            var isDiscFirst = isDiscreteUnit(firstMat ? firstMat.unit : null);
            items.push({
                materialId: firstMat ? (firstMat.id || firstMat.materialId) : 1,
                dosage: isDiscFirst ? 1 : 0.1,
                lossRate: isDiscFirst ? 0 : 0,
                note: ''
            });
            renderRows();
        });

        renderRows();

        var cancelBtn = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-secondary', type: 'button' }, ['Đóng']);
        var saveBtn = hub.el('button', {
            class: 'ws-inv-btn ws-inv-btn-primary',
            type: 'button'
        }, ['Lưu công thức']);

        var footer = makeInvFooter(cancelBtn, saveBtn);

        var modal = createInventoryModal({
            title: 'Thiết lập công thức',
            subtitle: 'Định mức nguyên liệu cho từng đơn vị sản phẩm.',
            maxWidth: '880px',
            body: [prodSelectWrap, errorBanner, tableWrap, addRowBtn, summaryBox],
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        saveBtn.addEventListener('click', function () {
            errorBanner.style.display = 'none';

            if (!pId) {
                errorBanner.textContent = 'Vui lòng chọn sản phẩm cần thiết lập công thức.';
                errorBanner.style.display = 'flex';
                return;
            }

            if (items.length === 0) {
                errorBanner.textContent = 'Công thức phải có ít nhất 1 nguyên liệu thành phần.';
                errorBanner.style.display = 'flex';
                return;
            }

            // Check duplicate materials & validate values
            var usedMaterials = new Set();
            for (var i = 0; i < items.length; i++) {
                var it = items[i];
                var mIdKey = String(it.materialId);
                if (usedMaterials.has(mIdKey)) {
                    var dupM = findMaterial(it.materialId);
                    errorBanner.textContent = 'Nguyên liệu "' + (dupM ? dupM.name : it.materialId) + '" bị trùng lặp nhiều lần trong công thức. Vui lòng gộp chung định lượng vào 1 dòng.';
                    errorBanner.style.display = 'flex';
                    return;
                }
                usedMaterials.add(mIdKey);

                var thisM = findMaterial(it.materialId);
                var isDisc = isDiscreteUnit(thisM ? thisM.unit : null);
                if (isDisc) {
                    if (!Number.isInteger(it.dosage) || it.dosage < 1) {
                        errorBanner.textContent = 'Nguyên liệu "' + (thisM ? thisM.name : it.materialId) + '" có đơn vị tính đếm chiếc (' + formatMaterialUnit(thisM.unit) + '), định lượng bắt buộc phải là số nguyên >= 1 chiếc/cái.';
                        errorBanner.style.display = 'flex';
                        return;
                    }
                    it.lossRate = 0;
                }

                if (isNaN(it.dosage) || it.dosage <= 0) {
                    errorBanner.textContent = 'Định lượng của tất cả nguyên liệu phải là số dương lớn hơn 0.';
                    errorBanner.style.display = 'flex';
                    return;
                }
                if (isNaN(it.lossRate) || it.lossRate < 0) {
                    errorBanner.textContent = 'Tỷ lệ hao hụt không được âm (tối thiểu 0%).';
                    errorBanner.style.display = 'flex';
                    return;
                }
            }

            saveBtn.disabled = true;
            saveBtn.textContent = 'Đang lưu công thức…';

            var payload = items.map(function (it) {
                return {
                    materialId: Number(it.materialId),
                    dosage: Number(it.dosage),
                    lossRate: Number(it.lossRate || 0),
                    note: it.note || ''
                };
            });

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    action: 'save-recipe',
                    productId: pId,
                    items: JSON.stringify(payload)
                }
            }).then(function () {
                modal.close();
                hub.notify('Lưu công thức định mức thành công!', 'ok');
                if (onReload) onReload();
            }).catch(function (err) {
                saveBtn.disabled = false;
                saveBtn.textContent = 'Lưu công thức';
                errorBanner.textContent = (err && err.message) || 'Không thể lưu công thức. Vui lòng kiểm tra lại.';
                errorBanner.style.display = 'flex';
                notifyError(err);
            });
        });

        modal.open();
    }

    /**
     * M-R4: Form Xuất nguyên liệu / Sản xuất (Atomic Transaction Flow)
     * Trừ tự động nhiều nguyên liệu cùng lúc theo Recipe, thiếu 1 nguyên liệu là rollback.
     */
    function showProductionModal(branchId, selectedProduct, productsWithRecipe, rawMaterials, onReload, branches, allProductsList) {
        var rawList = (productsWithRecipe && productsWithRecipe.length > 0) ? productsWithRecipe : (allProductsList || []);
        var productList = rawList.filter(function (p) {
            var pType = (p.type || p.productType || '').toUpperCase();
            return pType !== 'COMBO';
        });
        if (productList.length === 0) {
            hub.notify('Không có sản phẩm bán thành phẩm nào khả dụng để sản xuất (Các gói Combo không thực hiện sản xuất mẻ).', 'warn');
            return;
        }

        var curBranchId = branchId;
        var curProduct = (selectedProduct && (selectedProduct.type || selectedProduct.productType || '').toUpperCase() !== 'COMBO') ? selectedProduct : productList[0];
        var curQty = 10;

        // Branch Selection
        var branchSelect = null;
        var branchWrap = hub.el('div', { class: 'ws-inv-group', style: 'margin-bottom: 14px;' });
        if (branches && branches.length > 1) {
            branchSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%;' });
            branches.forEach(function (b) {
                var opt = hub.el('option', { value: String(b.id) }, [b.name]);
                if (String(b.id) === String(curBranchId)) opt.selected = true;
                branchSelect.appendChild(opt);
            });
            branchWrap.appendChild(hub.el('label', { class: 'ws-inv-label' }, ['Chi nhánh sản xuất / chế biến:', hub.el('span', { class: 'ws-inv-req' }, [' *'])]));
            branchWrap.appendChild(branchSelect);
        }

        // Product Selection
        var prodSelect = hub.el('select', { class: 'ws-select', style: 'width: 100%; font-size: 0.95rem; font-weight: 600;' });
        productList.forEach(function (p) {
            var pIdVal = p.productId || p.id;
            var cnt = (p.items ? p.items.length : (p.itemCount != null ? p.itemCount : (p.materialCount || 0)));
            var opt = hub.el('option', { value: String(pIdVal) }, [
                (p.name || p.productName || ('Sản phẩm #' + pIdVal)) + (cnt > 0 ? (' (' + cnt + ' NL định mức)') : ' (Chưa có công thức)')
            ]);
            if (curProduct && String(pIdVal) === String(curProduct.productId || curProduct.id)) opt.selected = true;
            prodSelect.appendChild(opt);
        });
        var prodGroup = makeInvField('Sản phẩm cần sản xuất / chế biến (Chỉ món chế biến lẻ)', prodSelect, true);

        // Cooking Time Badge & Batch Estimated Time Card
        var cookingTimeBadge = hub.el('span', { class: 'ws-badge', style: 'background: #ede9fe; color: #6d28d9; font-weight: 600;' }, ['—']);
        var totalBatchTimeEl = hub.el('span', { style: 'font-size: 0.85rem; color: #475569; font-weight: 500;' }, ['—']);
        var prodTimeCard = hub.el('div', {
            class: 'ws-inv-info-card',
            style: 'margin-bottom: 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; display: flex; justify-content: space-between; align-items: center;'
        }, [
            hub.el('div', { style: 'display: flex; align-items: center; gap: 8px;' }, [
                hub.el('span', { style: 'font-size: 0.85rem; color: #64748b; font-weight: 600;' }, ['Định mức thời gian:']),
                cookingTimeBadge
            ]),
            totalBatchTimeEl
        ]);

        // Quantity Input
        var qtyInput = hub.el('input', {
            type: 'number',
            class: 'ws-input',
            min: '1',
            step: '1',
            value: String(curQty),
            style: 'font-size: 1.1rem; font-weight: 700; color: #0f172a;'
        });
        var qtyGroup = makeInvField('Số lượng thành phẩm cần sản xuất (phần / ly / hộp)', qtyInput, true);

        // Note Input
        var noteInput = hub.el('input', {
            type: 'text',
            class: 'ws-input',
            placeholder: 'Ghi chú sản xuất…'
        });
        var noteGroup = makeInvField('Ghi chú sản xuất', noteInput, false);

        // Form Grid
        var formGrid = hub.el('div', { style: 'display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 16px;' }, [
            qtyGroup,
            noteGroup
        ]);

        // Realtime BOM Inspection Table
        var bomTitle = hub.el('h4', { style: 'margin: 16px 0 8px; font-size: 0.95rem; font-weight: 700; color: #1e293b; display: flex; align-items: center; justify-content: space-between;' }, [
            hub.el('span', {}, ['Kiểm tra tồn kho nguyên liệu:']),
            null
        ]);

        var bomTableWrap = hub.el('div', { style: 'overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 8px;' });
        var bomTable = hub.el('table', { class: 'ws-bom-table' });
        var bomThead = hub.el('thead', {}, [
            hub.el('tr', {}, [
                hub.el('th', { style: 'width: 30%;' }, ['Nguyên liệu']),
                hub.el('th', { style: 'width: 15%; text-align: right;' }, ['Định mức / 1 SP']),
                hub.el('th', { style: 'width: 15%; text-align: right;' }, ['Tổng cần xuất']),
                hub.el('th', { style: 'width: 18%; text-align: right;' }, ['Tồn kho khả dụng']),
                hub.el('th', { style: 'width: 22%; text-align: center;' }, ['Trạng thái'])
            ])
        ]);
        var bomTbody = hub.el('tbody');
        bomTable.appendChild(bomThead);
        bomTable.appendChild(bomTbody);
        bomTableWrap.appendChild(bomTable);

        // Atomic Status Alert Banner
        var statusBanner = hub.el('div', { class: 'ws-alert-banner' });

        var cancelBtn = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-secondary', type: 'button' }, ['Hủy']);
        var produceBtn = hub.el('button', {
            class: 'ws-inv-btn ws-inv-btn-primary',
            type: 'button'
        }, ['Xác nhận sản xuất']);

        var footer = makeInvFooter(cancelBtn, produceBtn);

        var modal = createInventoryModal({
            title: 'Xuất kho sản xuất',
            subtitle: 'Trừ nguyên liệu và nhập kho thành phẩm.',
            maxWidth: '820px',
            body: [
                branchSelect ? branchWrap : null,
                prodGroup,
                prodTimeCard,
                formGrid,
                bomTitle,
                bomTableWrap,
                statusBanner
            ].filter(Boolean),
            footer: footer
        });

        cancelBtn.addEventListener('click', modal.close);

        function updateBOMCheck() {
            var selectedId = prodSelect.value;
            curProduct = productList.find(function (p) {
                return String(p.productId || p.id) === String(selectedId);
            }) || curProduct;

            var pType = curProduct ? (curProduct.type || curProduct.productType || '') : '';
            var cTime = (curProduct && curProduct.cookingTime) || resolveCookingTime(pType);
            cookingTimeBadge.textContent = cTime;

            var qtyVal = parseInt(qtyInput.value, 10);
            if (isNaN(qtyVal) || qtyVal <= 0) {
                bomTbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: #dc2626; padding: 18px;">Vui lòng nhập số lượng sản xuất hợp lệ (> 0).</td></tr>';
                statusBanner.className = 'ws-alert-banner danger';
                statusBanner.textContent = 'Số lượng sản xuất phải lớn hơn 0.';
                totalBatchTimeEl.textContent = '—';
                produceBtn.disabled = true;
                produceBtn.style.background = '#94a3b8';
                produceBtn.style.borderColor = '#94a3b8';
                produceBtn.style.opacity = '0.5';
                produceBtn.style.cursor = 'not-allowed';
                return;
            }

            var upperType = pType.toUpperCase();
            if (upperType.indexOf('FOOD') !== -1 || upperType.indexOf('POPCORN') !== -1) {
                var batches = Math.ceil(qtyVal / 5);
                totalBatchTimeEl.textContent = 'Ước tính thời gian: ~' + (batches * 2.5) + ' phút (' + batches + ' mẻ rang)';
            } else if (upperType.indexOf('DRINK') !== -1) {
                totalBatchTimeEl.textContent = 'Ước tính thời gian rót: ~' + (qtyVal * 0.5) + ' phút';
            } else {
                totalBatchTimeEl.textContent = 'Ước tính thời gian: ~' + qtyVal + ' phút';
            }

            var rawItems = (curProduct && curProduct.items) ? curProduct.items : [];
            // Filter to only genuine recipe items (must have materialId or dosage)
            var recipeItems = rawItems.filter(function (it) {
                return it && (it.materialId != null || (it.dosage != null && it.dosage > 0));
            });
            if (recipeItems.length === 0) {
                var btnGoEdit = hub.el('button', {
                    class: 'ws-btn primary sm',
                    type: 'button',
                    style: 'margin-top: 8px; background: #7c3aed; border-color: #7c3aed; font-weight: 600;',
                    onClick: function () {
                        modal.close();
                        showRecipeEditorModal(curProduct, null, rawMaterials, onReload, productList);
                    }
                }, ['Thiết lập công thức']);

                bomTbody.replaceChildren(hub.el('tr', {}, [
                    hub.el('td', { colspan: '5', style: 'text-align: center; padding: 24px;' }, [
                        hub.el('div', { style: 'color: #d97706; font-weight: 700; margin-bottom: 4px;' }, ['Sản phẩm "' + (curProduct.name || curProduct.productName || 'này') + '" chưa có công thức định mức.']),
                        hub.el('div', { class: 'muted', style: 'font-size: 0.85rem; margin-bottom: 8px;' }, ['Vui lòng thiết lập nguyên liệu và định lượng trước khi xuất kho chế biến.']),
                        btnGoEdit
                    ])
                ]));
                statusBanner.className = 'ws-alert-banner danger';
                statusBanner.textContent = 'Món chưa có công thức định mức.';
                produceBtn.disabled = true;
                produceBtn.style.background = '#94a3b8';
                produceBtn.style.borderColor = '#94a3b8';
                produceBtn.style.opacity = '0.5';
                produceBtn.style.cursor = 'not-allowed';
                produceBtn.title = 'Vui lòng thiết lập công thức trước';
                return;
            }

            bomTbody.innerHTML = '';
            var hasShortage = false;
            var shortCount = 0;

            recipeItems.forEach(function (item) {
                var mId = item.materialId;
                var mat = (rawMaterials || []).find(function (m) {
                    return String(m.id || m.materialId) === String(mId);
                });

                var mName = (mat ? mat.name : null) || item.materialName || ('Nguyên liệu #' + mId);
                var unit = formatMaterialUnit((mat ? mat.unit : null) || item.materialUnit);
                var isDiscrete = isDiscreteUnit(unit);

                var lossRateVal = item.lossRate != null ? item.lossRate : 0;
                var lossPct = isDiscrete ? 0 : getLossPercentage(lossRateVal);
                var lossRatio = isDiscrete ? 0 : getLossRatio(lossRateVal);

                var effDosage = item.effectiveDosage != null ? item.effectiveDosage : (isDiscrete ? Math.max(1, Math.ceil(item.dosage || 1)) : (item.dosage * (1 + lossRatio)));
                var totalNeeded = isDiscrete ? Math.ceil(qtyVal * effDosage) : (qtyVal * effDosage);
                var currentStock = (mat && mat.qty != null) ? mat.qty : 0;
                var isShort = currentStock < totalNeeded;

                if (isShort) {
                    hasShortage = true;
                    shortCount++;
                }

                var tr = hub.el('tr', { class: isShort ? 'ws-bom-row-short' : '' });

                // Cột 1: Tên nguyên liệu
                var tdName = hub.el('td', {}, [
                    hub.el('strong', { style: 'color: #0f172a;' }, [mName]),
                    lossPct > 0 ? hub.el('span', { class: 'ws-recipe-pill-loss' }, ['+' + lossPct + '% hh']) : null
                ].filter(Boolean));

                // Cột 2: Định mức 1 SP
                var tdEff = hub.el('td', { style: 'text-align: right; color: #475569;' }, [
                    formatDosageNumber(effDosage) + ' ' + unit
                ]);

                // Cột 3: Tổng cần xuất
                var tdTotal = hub.el('td', { style: 'text-align: right; font-weight: 700; color: #0f172a;' }, [
                    formatDosageNumber(totalNeeded) + ' ' + unit
                ]);

                // Cột 4: Tồn kho hiện tại
                var tdStock = hub.el('td', { style: 'text-align: right; font-weight: 600;' }, [
                    hub.el('span', { style: 'color: ' + (isShort ? '#dc2626' : '#16a34a') + ';' }, [
                        formatDosageNumber(currentStock) + ' ' + unit
                    ])
                ]);

                // Cột 5: Trạng thái
                var tdStatus = hub.el('td', { style: 'text-align: center;' });
                if (isShort) {
                    var diff = totalNeeded - currentStock;
                    tdStatus.appendChild(hub.el('span', { class: 'ws-bom-badge-short' }, [
                        'Thiếu ' + formatDosageNumber(diff) + ' ' + unit
                    ]));
                } else {
                    tdStatus.appendChild(hub.el('span', { class: 'ws-bom-badge-ok' }, [
                        'Đủ tồn kho'
                    ]));
                }

                tr.appendChild(tdName);
                tr.appendChild(tdEff);
                tr.appendChild(tdTotal);
                tr.appendChild(tdStock);
                tr.appendChild(tdStatus);
                bomTbody.appendChild(tr);
            });

            if (hasShortage) {
                statusBanner.className = 'ws-alert-banner danger';
                statusBanner.innerHTML = '<strong>Không đủ nguyên liệu:</strong> Có ' + shortCount + ' nguyên liệu không đủ tồn kho để sản xuất ' + qtyVal + ' phần.<div style="margin-top: 6px; font-weight: 600; color: #b91c1c;">Tồn kho nguyên liệu không đủ để thực hiện mẻ sản xuất này. Vui lòng nhập thêm kho trước.</div>';
                produceBtn.disabled = true;
                produceBtn.style.background = '#94a3b8';
                produceBtn.style.borderColor = '#94a3b8';
                produceBtn.style.opacity = '0.5';
                produceBtn.style.cursor = 'not-allowed';
                produceBtn.title = 'Tồn kho nguyên liệu không đủ để thực hiện mẻ sản xuất này. Vui lòng nhập thêm kho trước.';
            } else {
                statusBanner.className = 'ws-alert-banner success';
                statusBanner.innerHTML = '<strong>Đủ điều kiện sản xuất:</strong> Toàn bộ nguyên liệu đều đáp ứng đủ số lượng trong kho.';
                produceBtn.disabled = false;
                produceBtn.style.background = '#0284c7';
                produceBtn.style.borderColor = '#0284c7';
                produceBtn.style.opacity = '1';
                produceBtn.style.cursor = 'pointer';
                produceBtn.title = 'Bấm để thực hiện xuất nguyên liệu và sản xuất thành phẩm';
            }
        }

        function ensureRecipeAndCheck() {
            var selectedId = prodSelect.value;
            curProduct = productList.find(function (p) {
                return String(p.productId || p.id) === String(selectedId);
            }) || curProduct;

            var pIdVal = curProduct ? (curProduct.productId || curProduct.id) : null;
            if (pIdVal && (!curProduct.items || curProduct.items.length === 0)) {
                bomTbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: #64748b; padding: 18px;"><div class="ws-spinner" style="margin: 0 auto 8px; width: 20px; height: 20px;"></div>Đang tải công thức định mức từ cơ sở dữ liệu…</td></tr>';
                hub.api('/inventory?action=recipe&productId=' + pIdVal).then(function (res) {
                    var fetched = (res && res.data) || res || [];
                    var validItems = Array.isArray(fetched) ? fetched.filter(function (it) {
                        return it && (it.materialId != null || (it.dosage != null && it.dosage > 0));
                    }) : [];
                    if (validItems.length > 0) {
                        curProduct.items = validItems;
                        curProduct.hasRecipe = true;
                    } else {
                        curProduct.items = [];
                        curProduct.hasRecipe = false;
                    }
                    updateBOMCheck();
                }).catch(function () {
                    updateBOMCheck();
                });
            } else {
                updateBOMCheck();
            }
        }

        prodSelect.addEventListener('change', ensureRecipeAndCheck);
        qtyInput.addEventListener('input', updateBOMCheck);

        if (branchSelect) {
            branchSelect.addEventListener('change', function () {
                curBranchId = branchSelect.value;
                hub.api('/inventory?type=material&branchId=' + curBranchId).then(function (res) {
                    rawMaterials = (res && res.data) || res || [];
                    updateBOMCheck();
                }).catch(notifyError);
            });
        }

        ensureRecipeAndCheck();

        produceBtn.addEventListener('click', function () {
            if (produceBtn.disabled) {
                hub.notify('Tồn kho nguyên liệu không đủ để thực hiện mẻ sản xuất này. Vui lòng nhập thêm kho trước.', 'err');
                return;
            }
            var selectedId = prodSelect.value;
            var qtyVal = parseInt(qtyInput.value, 10);
            var noteVal = (noteInput.value || '').trim();

            produceBtn.disabled = true;
            produceBtn.style.background = '#94a3b8';
            produceBtn.style.borderColor = '#94a3b8';
            produceBtn.style.opacity = '0.5';
            produceBtn.style.cursor = 'not-allowed';
            produceBtn.textContent = 'Đang xử lý sản xuất…';

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    action: 'produce',
                    branchId: curBranchId,
                    productId: selectedId,
                    quantity: qtyVal,
                    note: noteVal
                }
            }).then(function (res) {
                modal.close();
                var msg = (res && res.message) || ('Sản xuất thành công ' + qtyVal + ' sản phẩm!');
                hub.notify(msg, 'ok');
                if (onReload) onReload();
            }).catch(function (err) {
                produceBtn.disabled = false;
                produceBtn.style.background = '#0284c7';
                produceBtn.style.borderColor = '#0284c7';
                produceBtn.style.opacity = '1';
                produceBtn.style.cursor = 'pointer';
                produceBtn.textContent = 'Xác nhận sản xuất';
                notifyError(err);
                updateBOMCheck();
            });
        });

        modal.open();
    }

    /**
     * Modal xem giải thích chi tiết combo
     */
    function showComboDetailModal(product) {
        var pName = product.name || product.productName || 'Combo';
        var isCouple = pName.toLowerCase().indexOf('couple') !== -1;
        var itemCount = isCouple ? 3 : 2;

        var infoCard = hub.el('div', { class: 'ws-inv-info-card', style: 'border-left: 4px solid #7c3aed;' }, [
            hub.el('div', { class: 'info-head' }, [
                hub.el('div', { class: 'info-row' }, [
                    hub.el('div', { class: 'ws-inv-avatar', style: 'background: #ede9fe; color: #7c3aed;' }, ['CB']),
                    hub.el('div', {}, [
                        hub.el('h4', { class: 'info-title' }, [pName]),
                        hub.el('div', { class: 'info-meta' }, [
                            hub.el('span', {}, ['Phân loại: ', hub.el('strong', {}, ['Combo (' + itemCount + ' món)'])]),
                            hub.el('span', {}, ['Quy cách: ', hub.el('strong', { style: 'color: #7c3aed;' }, ['Bán tại quầy'])])
                        ])
                    ])
                ]),
                hub.el('span', { class: 'ws-badge', style: 'background: #ede9fe; color: #6d28d9; font-weight: 600;' }, [
                    'Combo (' + itemCount + ' món)'
                ])
            ])
        ]);

        var compBox = hub.el('div', { style: 'background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px; margin-top: 14px;' }, [
            hub.el('div', { style: 'font-weight: 600; color: #0f172a; margin-bottom: 8px;' }, ['Các món trong combo:']),
            hub.el('ul', { style: 'margin: 0; padding-left: 20px; color: #334155; line-height: 1.8;' }, isCouple ? [
                hub.el('li', {}, [hub.el('strong', {}, ['1x']), ' Bắp rang lớn']),
                hub.el('li', {}, [hub.el('strong', {}, ['2x']), ' Nước ngọt Coca-Cola'])
            ] : [
                hub.el('li', {}, [hub.el('strong', {}, ['1x']), ' Bắp rang']),
                hub.el('li', {}, [hub.el('strong', {}, ['1x']), ' Nước ngọt Coca-Cola'])
            ])
        ]);

        var btnClose = hub.el('button', { class: 'ws-inv-btn ws-inv-btn-primary', type: 'button' }, ['Đóng']);
        var footer = hub.el('div', { class: 'ws-inv-modal-footer' }, [btnClose]);

        var modal = createInventoryModal({
            title: 'Chi tiết Combo',
            subtitle: pName,
            theme: 'blue',
            maxWidth: '520px',
            body: [infoCard, compBox],
            footer: footer
        });

        btnClose.addEventListener('click', modal.close);
        modal.open();
    }

    /**
     * Modal xác nhận xóa công thức định mức (đơn giản, dễ hiểu, nút bấm rõ chữ)
     */
    function showDeleteRecipeConfirmModal(product, onDeleted) {
        var pId = product.productId || product.id;
        var pName = product.name || product.productName || ('Sản phẩm #' + pId);

        var body = hub.el('div', { style: 'padding: 8px 4px 16px 4px; font-size: 0.95rem; color: #334155; line-height: 1.6;' }, [
            hub.el('p', { style: 'margin: 0 0 10px 0; font-size: 1rem;' }, [
                'Bạn có chắc chắn muốn xóa công thức định mức của món ',
                hub.el('strong', { style: 'color: #0f172a;' }, [pName]),
                ' không?'
            ]),
            hub.el('p', { style: 'margin: 0; font-size: 0.85rem; color: #64748b;' }, [
                'Sau khi xóa, món này sẽ trở về trạng thái "Chưa thiết lập" và không thể xuất kho sản xuất.'
            ])
        ]);

        var btnCancel = hub.el('button', {
            class: 'ws-inv-btn ws-inv-btn-secondary',
            type: 'button',
            style: 'padding: 8px 18px; font-size: 0.9rem;'
        }, ['Hủy']);

        var btnConfirm = hub.el('button', {
            type: 'button',
            style: 'padding: 8px 18px; font-size: 0.9rem; font-weight: 600; color: #ffffff !important; background-color: #dc2626 !important; border: 1px solid #b91c1c !important; border-radius: 6px; cursor: pointer;'
        }, ['Xóa']);

        var footer = hub.el('div', { class: 'ws-inv-modal-footer', style: 'display: flex; justify-content: flex-end; gap: 10px;' }, [btnCancel, btnConfirm]);

        var modal = createInventoryModal({
            title: 'Xóa công thức định mức',
            subtitle: pName,
            theme: 'orange',
            maxWidth: '460px',
            body: body,
            footer: footer
        });

        btnCancel.addEventListener('click', modal.close);

        btnConfirm.addEventListener('click', function () {
            btnConfirm.disabled = true;
            btnConfirm.textContent = 'Đang xóa…';
            btnCancel.disabled = true;

            hub.api('/inventory', {
                method: 'POST',
                body: {
                    action: 'delete-recipe',
                    productId: pId
                }
            }).then(function () {
                modal.close();
                if (onDeleted) onDeleted();
            }).catch(function (err) {
                btnConfirm.disabled = false;
                btnConfirm.textContent = 'Xóa';
                btnCancel.disabled = false;
                modal.close();
                notifyError(err);
            });
        });

        modal.open();
    }

    /**
     * M-X1: Màn hình Danh sách Công thức & Luồng Sản xuất
     */
    function renderRecipeProductionPage(targetRoot, branchId, branches, isStaff, onSwitchTab) {
        if (!targetRoot) return;
        if (isStaff) {
            hub.notify('Nhân viên không có quyền truy cập module Công thức & Sản xuất.', 'warn');
            if (onSwitchTab) onSwitchTab('product');
            return;
        }

        hub.setWorkspaceHeader('Công thức & Sản xuất');

        var page = hub.el('div', { class: 'ws-page' });
        var allRecipes = [];
        var rawMaterials = [];
        var currentBranchId = branchId || (branches && branches[0] ? branches[0].id : null);
        var currentPage = 0;
        var pageSize = 10;

        // Header
        var header = hub.el('div', { class: 'ws-page-header' }, [
            hub.el('h1', { class: 'ws-page-title' }, ['Công thức & Sản xuất']),
            hub.el('p', { class: 'ws-page-subtitle' }, ['Quản lý định mức tiêu hao nguyên liệu và xuất kho chế biến.'])
        ]);
        page.appendChild(header);

        // 4 Stat Cards
        var cardTotal = makeStatCard('total', 'TỔNG MÓN CHẾ BIẾN', 'rcTotalCount');
        var cardConfigured = makeStatCard('in-stock', 'ĐÃ CÓ CÔNG THỨC', 'rcConfiguredCount');
        var cardUnconfigured = makeStatCard('low-stock', 'CHƯA THIẾT LẬP', 'rcUnconfiguredCount');
        var cardShortage = makeStatCard('out-stock', 'MÓN CẦN BỔ SUNG NGUYÊN LIỆU', 'rcShortageCount');

        var cardsContainer = hub.el('div', { class: 'ws-inv-cards' }, [cardTotal, cardConfigured, cardUnconfigured, cardShortage]);
        page.appendChild(cardsContainer);

        // Toolbar
        var toolbar = hub.el('div', { class: 'ws-list-toolbar' });
        var tbLeft = hub.el('div', { class: 'ws-list-toolbar-left' });

        var branchSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Chi nhánh' });
        (branches || []).forEach(function (b) {
            var opt = hub.el('option', { value: String(b.id) }, [b.name]);
            if (String(b.id) === String(currentBranchId)) opt.selected = true;
            branchSelect.appendChild(opt);
        });
        tbLeft.appendChild(branchSelect);

        var searchWrap = hub.el('div', { class: 'ws-search' });
        searchWrap.appendChild(hub.el('span', { 'aria-hidden': 'true', innerHTML: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>' }));
        var searchInput = hub.el('input', { type: 'search', placeholder: 'Tìm theo món, loại hoặc nguyên liệu…', 'aria-label': 'Tìm kiếm' });
        searchWrap.appendChild(searchInput);
        tbLeft.appendChild(searchWrap);

        var typeSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Loại món' });
        [
            { value: '', label: 'Tất cả phân loại' },
            { value: 'FOOD', label: 'Bắp & Đồ ăn' },
            { value: 'DRINK', label: 'Nước uống' },
            { value: 'COMBO', label: 'Combo' }
        ].forEach(function (t) {
            typeSelect.appendChild(hub.el('option', { value: t.value }, [t.label]));
        });
        tbLeft.appendChild(typeSelect);

        var statusSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Trạng thái' });
        [
            { value: '', label: 'Tất cả trạng thái' },
            { value: 'CONFIGURED', label: 'Đã có công thức' },
            { value: 'NOT_CONFIGURED', label: 'Chưa thiết lập' },
            { value: 'SHORTAGE', label: 'Món cần bổ sung nguyên liệu' }
        ].forEach(function (s) {
            statusSelect.appendChild(hub.el('option', { value: s.value }, [s.label]));
        });
        tbLeft.appendChild(statusSelect);

        var sortSelect = hub.el('select', { class: 'ws-select', 'aria-label': 'Sắp xếp' });
        [
            { value: '', label: 'Mặc định (Tên món)' },
            { value: 'NAME_ASC', label: 'Tên món A-Z' },
            { value: 'COST_DESC', label: 'Giá thành giảm dần' },
            { value: 'COST_ASC', label: 'Giá thành tăng dần' },
            { value: 'ITEMS_DESC', label: 'Số nguyên liệu giảm dần' }
        ].forEach(function (s) {
            sortSelect.appendChild(hub.el('option', { value: s.value }, [s.label]));
        });
        tbLeft.appendChild(sortSelect);

        var tbRight = hub.el('div', { class: 'ws-list-toolbar-right', style: 'display: flex; gap: 8px;' });

        var openProduceBtn = hub.el('button', {
            class: 'ws-btn primary',
            type: 'button',
            style: 'background: #0284c7; border-color: #0284c7; font-weight: 600;'
        }, ['Xuất kho sản xuất']);

        var openEditorBtn = hub.el('button', {
            class: 'ws-btn primary',
            type: 'button',
            style: 'background: #7c3aed; border-color: #7c3aed; font-weight: 600;'
        }, ['Thiết lập công thức']);

        tbRight.appendChild(openProduceBtn);
        tbRight.appendChild(openEditorBtn);

        toolbar.appendChild(tbLeft);
        toolbar.appendChild(tbRight);
        page.appendChild(toolbar);

        var tableRegion = hub.el('div', { class: 'ws-table-wrap' });
        page.appendChild(tableRegion);

        targetRoot.replaceChildren(page);

        // Click stat cards to filter
        cardTotal.style.cursor = 'pointer';
        cardTotal.addEventListener('click', function () { statusSelect.value = ''; applyFilters(); });
        cardConfigured.style.cursor = 'pointer';
        cardConfigured.addEventListener('click', function () { statusSelect.value = 'CONFIGURED'; applyFilters(); });
        cardUnconfigured.style.cursor = 'pointer';
        cardUnconfigured.addEventListener('click', function () { statusSelect.value = 'NOT_CONFIGURED'; applyFilters(); });
        cardShortage.style.cursor = 'pointer';
        cardShortage.addEventListener('click', function () { statusSelect.value = 'SHORTAGE'; applyFilters(); });

        openEditorBtn.addEventListener('click', function () {
            showRecipeEditorModal(null, null, rawMaterials, loadData, allRecipes);
        });

        openProduceBtn.addEventListener('click', function () {
            var configuredProducts = allRecipes.filter(function (r) {
                var pType = (r.type || r.productType || '').toUpperCase();
                return pType !== 'COMBO' && (r.hasRecipe || (r.items && r.items.length > 0) || (r.itemCount && r.itemCount > 0));
            });
            var listToUse = configuredProducts.length > 0 ? configuredProducts : allRecipes.filter(function (r) {
                return (r.type || r.productType || '').toUpperCase() !== 'COMBO';
            });
            showProductionModal(currentBranchId, null, listToUse, rawMaterials, loadData, branches, allRecipes);
        });

        function loadData() {
            currentBranchId = branchSelect.value || currentBranchId;
            tableRegion.replaceChildren(hub.el('div', { class: 'ws-list-loading', style: 'padding: 40px; text-align: center;' }, [
                hub.el('div', { class: 'ws-spinner' }),
                hub.el('span', { style: 'display: block; margin-top: 10px; color: #64748b;' }, ['Đang tải danh sách công thức & tồn kho nguyên liệu…'])
            ]));

            Promise.all([
                hub.api('/inventory?action=recipes'),
                hub.api('/inventory?type=material&branchId=' + currentBranchId).catch(function () { return []; })
            ]).then(function (results) {
                var recipeRes = results[0];
                var matRes = results[1];

                allRecipes = (recipeRes && recipeRes.data) || recipeRes || [];
                rawMaterials = (matRes && matRes.data) || matRes || [];

                // Cập nhật 4 Cards
                var total = allRecipes.length;
                var configured = allRecipes.filter(function (r) { return r.hasRecipe != null ? r.hasRecipe : (r.items && r.items.length > 0); }).length;
                var unconfigured = total - configured;

                // Tính số món cần bổ sung nguyên liệu (có nguyên liệu thiếu hoặc tồn kho <= 0)
                var shortageCount = 0;
                allRecipes.forEach(function (r) {
                    var pType = (r.type || r.productType || '').toUpperCase();
                    if (pType !== 'COMBO' && r.hasRecipe && r.items && r.items.length > 0) {
                        var isShort = false;
                        for (var i = 0; i < r.items.length; i++) {
                            var it = r.items[i];
                            var mat = rawMaterials.find(function (m) {
                                return String(m.id || m.materialId) === String(it.materialId);
                            });
                            var currentStock = (mat && mat.qty != null) ? mat.qty : 0;
                            if (currentStock <= 0 || currentStock < (it.dosage || 0)) {
                                isShort = true;
                                break;
                            }
                        }
                        if (isShort) shortageCount++;
                    }
                });

                var elTotal = document.getElementById('rcTotalCount');
                var elConfigured = document.getElementById('rcConfiguredCount');
                var elUnconfigured = document.getElementById('rcUnconfiguredCount');
                var elShortage = document.getElementById('rcShortageCount');

                if (elTotal) elTotal.textContent = String(total);
                if (elConfigured) elConfigured.textContent = String(configured);
                if (elUnconfigured) elUnconfigured.textContent = String(unconfigured);
                if (elShortage) elShortage.textContent = String(shortageCount);

                applyFilters();
            }).catch(function (err) {
                tableRegion.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không thể tải danh sách công thức']),
                    hub.el('p', {}, [(err && err.message) || 'Vui lòng kiểm tra kết nối mạng và thử lại.'])
                ]));
            });
        }

        function applyFilters(resetPage) {
            if (resetPage !== false) currentPage = 0;
            var search = (searchInput.value || '').trim().toLowerCase();
            var searchNorm = search.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
            var typeVal = typeSelect.value;
            var stVal = statusSelect.value;
            var sortVal = sortSelect.value;

            var filtered = allRecipes.filter(function (r) {
                if (search) {
                    var nameLower = (r.name || r.productName || '').toLowerCase();
                    var nameNorm = nameLower.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
                    var matchName = nameLower.indexOf(search) !== -1 || nameNorm.indexOf(searchNorm) !== -1;

                    // Match ingredients
                    var matchMat = false;
                    if (r.items && Array.isArray(r.items)) {
                        for (var i = 0; i < r.items.length; i++) {
                            var matName = (r.items[i].materialName || '').toLowerCase();
                            if (matName.indexOf(search) !== -1) {
                                matchMat = true;
                                break;
                            }
                        }
                    }
                    if (!matchName && !matchMat) return false;
                }

                if (typeVal) {
                    var t = (r.type || r.productType || '').toUpperCase();
                    if (t.indexOf(typeVal.toUpperCase()) === -1) return false;
                }

                if (stVal === 'CONFIGURED' && !r.hasRecipe) return false;
                if (stVal === 'NOT_CONFIGURED' && r.hasRecipe) return false;
                if (stVal === 'SHORTAGE') {
                    if (!r.hasRecipe || !r.items || r.items.length === 0) return false;
                    var hasShort = false;
                    for (var i = 0; i < r.items.length; i++) {
                        var it = r.items[i];
                        var mat = rawMaterials.find(function (m) {
                            return String(m.id || m.materialId) === String(it.materialId);
                        });
                        var currentStock = (mat && mat.qty != null) ? mat.qty : 0;
                        if (currentStock <= 0 || currentStock < (it.dosage || 0)) {
                            hasShort = true;
                            break;
                        }
                    }
                    if (!hasShort) return false;
                }

                return true;
            });

            if (sortVal === 'NAME_ASC') {
                filtered.sort(function (a, b) { return (a.name || a.productName || '').localeCompare(b.name || b.productName || ''); });
            } else if (sortVal === 'COST_DESC') {
                filtered.sort(function (a, b) { return (b.totalEstimatedCost || 0) - (a.totalEstimatedCost || 0); });
            } else if (sortVal === 'COST_ASC') {
                filtered.sort(function (a, b) { return (a.totalEstimatedCost || 0) - (b.totalEstimatedCost || 0); });
            } else if (sortVal === 'ITEMS_DESC') {
                filtered.sort(function (a, b) { return (b.itemCount || 0) - (a.itemCount || 0); });
            }

            renderTable(filtered);
        }

        function renderTable(items) {
            if (!items.length) {
                tableRegion.replaceChildren(hub.el('div', { class: 'ws-empty' }, [
                    hub.el('h3', {}, ['Không có sản phẩm']),
                    hub.el('p', {}, ['Không tìm thấy sản phẩm nào theo bộ lọc hiện tại.'])
                ]));
                return;
            }

            var table = hub.el('table', { class: 'ws-table' });
            var thead = hub.el('thead', {}, [
                hub.el('tr', {}, [
                    hub.el('th', { style: 'width: 25%;' }, ['Món / Thành phẩm']),
                    hub.el('th', { style: 'width: 15%;' }, ['Trạng thái định mức']),
                    hub.el('th', { style: 'width: 35%;' }, ['Thành phần nguyên liệu']),
                    hub.el('th', { style: 'width: 13%; text-align: right;' }, ['Giá thành']),
                    hub.el('th', { style: 'width: 12%; text-align: center;' }, ['Thao tác'])
                ])
            ]);

            var tbody = hub.el('tbody');
            var totalPages = Math.ceil(items.length / pageSize) || 1;
            if (currentPage >= totalPages) currentPage = totalPages - 1;
            if (currentPage < 0) currentPage = 0;

            var start = currentPage * pageSize;
            var pageSlice = items.slice(start, start + pageSize);

            pageSlice.forEach(function (r) {
                var tr = hub.el('tr');

                var pName = r.name || r.productName || ('Sản phẩm #' + (r.productId || r.id));
                var pType = r.type || r.productType || 'SP';
                var isCombo = (pType.toUpperCase() === 'COMBO');
                var pItems = r.items || [];
                var pHasRecipe = r.hasRecipe != null ? r.hasRecipe : (pItems.length > 0);
                var pItemCount = r.itemCount != null ? r.itemCount : (r.materialCount != null ? r.materialCount : pItems.length);
                var pTotalCost = r.totalEstimatedCost != null ? r.totalEstimatedCost : 0;

                // Cột 1: Món / Thành phẩm (gọn gàng, chỉ hiển thị tên món tiếng Việt rõ ràng)
                var tdProd = hub.el('td', {}, [
                    hub.el('div', { style: 'font-weight: 700; color: #0f172a; font-size: 0.95rem;' }, [pName])
                ]);

                // Cột 2: Trạng thái định mức
                var tdStatus = hub.el('td', {});
                if (isCombo) {
                    var comboItemCount = pName.toLowerCase().indexOf('couple') !== -1 ? 3 : 2;
                    var comboDesc = pName.toLowerCase().indexOf('couple') !== -1
                        ? 'Gồm 1 Bắp rang lớn + 2 Coca-Cola'
                        : 'Gồm 1 Bắp rang + 1 Coca-Cola';
                    var badge = hub.el('span', {
                        class: 'ws-badge ws-status-pill',
                        style: 'background: #ede9fe; color: #6d28d9; cursor: pointer; font-weight: 600;',
                        title: comboDesc
                    }, [
                        'Combo (' + comboItemCount + ' món)'
                    ]);
                    badge.addEventListener('click', function () {
                        showComboDetailModal(r);
                    });
                    tdStatus.appendChild(badge);
                } else if (pHasRecipe) {
                    tdStatus.appendChild(hub.el('span', { class: 'ws-badge ws-status-pill good' }, [
                        pItemCount + ' nguyên liệu'
                    ]));
                } else {
                    tdStatus.appendChild(hub.el('span', { class: 'ws-badge ws-status-pill warning' }, [
                        'Chưa thiết lập'
                    ]));
                }

                // Cột 3: Danh sách nguyên liệu (BOM)
                var tdIngredients = hub.el('td', {});
                if (isCombo) {
                    var comboDesc = pName.toLowerCase().indexOf('couple') !== -1
                        ? 'Gồm 1 Bắp rang lớn + 2 Coca-Cola'
                        : 'Gồm 1 Bắp rang + 1 Coca-Cola';
                    var comboShortText = pName.toLowerCase().indexOf('couple') !== -1 ? '1 Bắp lớn • 2 Coca-Cola' : '1 Bắp rang • 1 Coca-Cola';
                    var comboEl = hub.el('div', {
                        style: 'font-size: 0.88rem; color: #475569; cursor: pointer;',
                        title: comboDesc + ' (Bấm để xem chi tiết)'
                    }, [comboShortText]);
                    comboEl.addEventListener('click', function () {
                        showComboDetailModal(r);
                    });
                    tdIngredients.appendChild(comboEl);
                } else if (pHasRecipe && pItems && pItems.length > 0) {
                    var fullBomTooltip = pItems.map(function (it) {
                        var mName = it.materialName || it.name || ('NL #' + it.materialId);
                        var mUnit = formatMaterialUnit(it.materialUnit || it.unit);
                        var isDisc = isDiscreteUnit(mUnit);
                        var dosageVal = isDisc ? Math.max(1, Math.ceil(it.dosage || 1)) : it.dosage;
                        var lossPct = isDisc ? 0 : getLossPercentage(it.lossRate);
                        var dUnitStr = /^[a-zA-Z]+$/.test(mUnit) ? mUnit : (' ' + mUnit);
                        return mName + ' (' + formatDosageNumber(dosageVal) + dUnitStr + (lossPct > 0 ? (' +' + lossPct + '%hh') : '') + ')';
                    }).join(' • ');

                    var firstTwo = pItems.slice(0, 2).map(function (it) {
                        var mName = it.materialName || it.name || ('NL #' + it.materialId);
                        var mUnit = formatMaterialUnit(it.materialUnit || it.unit);
                        var isDisc = isDiscreteUnit(mUnit);
                        var dosageVal = isDisc ? Math.max(1, Math.ceil(it.dosage || 1)) : it.dosage;
                        var dUnitStr = /^[a-zA-Z]+$/.test(mUnit) ? mUnit : (' ' + mUnit);
                        return mName + ' (' + formatDosageNumber(dosageVal) + dUnitStr + ')';
                    });

                    var summaryText = firstTwo.join(' • ');
                    if (pItems.length > 2) {
                        summaryText += ' • +' + (pItems.length - 2) + ' NL khác...';
                    }

                    var bomEl = hub.el('div', {
                        style: 'font-size: 0.88rem; color: #334155; line-height: 1.4; cursor: help;',
                        title: 'Nguyên liệu: ' + fullBomTooltip
                    }, [summaryText]);
                    tdIngredients.appendChild(bomEl);
                } else {
                    tdIngredients.appendChild(hub.el('span', { class: 'muted', style: 'font-size: 0.82rem; font-style: italic;' }, [
                        'Chưa cấu hình định mức nguyên liệu'
                    ]));
                }

                // Cột 4: Giá thành (BOM)
                var tdCost = hub.el('td', { style: 'text-align: right;' });
                if (!isCombo && pHasRecipe && pTotalCost > 0) {
                    tdCost.appendChild(hub.el('span', {
                        style: 'font-weight: 700; color: #0f172a; font-size: 0.95rem;'
                    }, [formatMoneyVnd(Math.round(pTotalCost))]));
                } else {
                    tdCost.appendChild(hub.el('span', { class: 'muted' }, ['—']));
                }

                // Cột 5: Thao tác
                var tdActions = hub.el('td', { style: 'text-align: center;' });
                var actWrap = hub.el('div', { style: 'display: inline-flex; gap: 6px;' });

                if (isCombo) {
                    var comboDesc = pName.toLowerCase().indexOf('couple') !== -1
                        ? 'Gồm 1 Bắp rang lớn + 2 Coca-Cola'
                        : 'Gồm 1 Bắp rang + 1 Coca-Cola';
                    var btnViewCombo = hub.el('button', {
                        class: 'ws-btn secondary sm',
                        type: 'button',
                        title: comboDesc
                    }, ['Xem combo']);
                    btnViewCombo.addEventListener('click', function () {
                        showComboDetailModal(r);
                    });
                    actWrap.appendChild(btnViewCombo);
                } else {
                    // Nút sửa/thiết lập
                    var btnEdit = hub.el('button', {
                        class: 'ws-btn secondary sm',
                        type: 'button',
                        title: r.hasRecipe ? 'Chỉnh sửa công thức định mức' : 'Thiết lập công thức cho món này'
                    }, [r.hasRecipe ? 'Sửa' : 'Thiết lập']);
                    btnEdit.addEventListener('click', function () {
                        showRecipeEditorModal(r, r.items, rawMaterials, loadData, allRecipes);
                    });
                    actWrap.appendChild(btnEdit);

                    // Nút sản xuất
                    if (pHasRecipe) {
                        var btnProduce = hub.el('button', {
                            class: 'ws-btn primary sm',
                            type: 'button',
                            style: 'background: #0284c7; border-color: #0284c7;',
                            title: 'Xuất kho nguyên liệu và chế biến sản phẩm này'
                        }, ['Sản xuất']);
                        btnProduce.addEventListener('click', function () {
                            var configuredProducts = allRecipes.filter(function (x) {
                                var xType = (x.type || x.productType || '').toUpperCase();
                                return xType !== 'COMBO' && x.hasRecipe;
                            });
                            showProductionModal(currentBranchId, r, configuredProducts, rawMaterials, loadData, branches, allRecipes);
                        });
                        actWrap.appendChild(btnProduce);
                    }

                    // Nút xóa công thức
                    if (pHasRecipe) {
                        var btnDelete = hub.el('button', {
                            class: 'ws-btn danger sm',
                            type: 'button',
                            title: 'Xóa toàn bộ công thức của món này'
                        }, ['Xóa']);
                        btnDelete.addEventListener('click', function () {
                            showDeleteRecipeConfirmModal(r, function () {
                                hub.notify('Đã xóa công thức thành công!', 'ok');
                                loadData();
                            });
                        });
                        actWrap.appendChild(btnDelete);
                    }
                }

                tdActions.appendChild(actWrap);

                tr.appendChild(tdProd);
                tr.appendChild(tdStatus);
                tr.appendChild(tdIngredients);
                tr.appendChild(tdCost);
                tr.appendChild(tdActions);
                tbody.appendChild(tr);
            });

            table.appendChild(thead);
            table.appendChild(tbody);

            // Pagination Controls
            var paginationWrap = hub.el('div', { class: 'ws-pagination', style: 'margin-top: 16px; display: flex; justify-content: space-between; align-items: center;' });
            var infoText = hub.el('div', { class: 'muted', style: 'font-size: 0.85rem;' }, [
                'Hiển thị ',
                hub.el('strong', {}, [String(start + 1)]),
                ' - ',
                hub.el('strong', {}, [String(Math.min(start + pageSize, items.length))]),
                ' trên ',
                hub.el('strong', {}, [String(items.length)]),
                ' món'
            ]);
            paginationWrap.appendChild(infoText);

            var pageBtns = hub.el('div', { style: 'display: flex; gap: 4px; align-items: center;' });

            var prevBtn = hub.el('button', { class: 'ws-btn secondary sm', type: 'button', disabled: currentPage === 0 }, ['«']);
            prevBtn.addEventListener('click', function () {
                if (currentPage > 0) {
                    currentPage--;
                    renderTable(items);
                }
            });
            pageBtns.appendChild(prevBtn);

            for (var p = 0; p < totalPages; p++) {
                (function (pageIdx) {
                    var pBtn = hub.el('button', {
                        class: 'ws-btn ' + (pageIdx === currentPage ? 'primary' : 'secondary') + ' sm',
                        type: 'button',
                        style: 'min-width: 32px;'
                    }, [String(pageIdx + 1)]);
                    pBtn.addEventListener('click', function () {
                        currentPage = pageIdx;
                        renderTable(items);
                    });
                    pageBtns.appendChild(pBtn);
                })(p);
            }

            var nextBtn = hub.el('button', { class: 'ws-btn secondary sm', type: 'button', disabled: currentPage >= totalPages - 1 }, ['»']);
            nextBtn.addEventListener('click', function () {
                if (currentPage < totalPages - 1) {
                    currentPage++;
                    renderTable(items);
                }
            });
            pageBtns.appendChild(nextBtn);
            paginationWrap.appendChild(pageBtns);

            tableRegion.replaceChildren(table, paginationWrap);
        }

        if (branchSelect.tagName === 'SELECT') branchSelect.addEventListener('change', loadData);
        searchInput.addEventListener('input', applyFilters);
        typeSelect.addEventListener('change', applyFilters);
        statusSelect.addEventListener('change', applyFilters);
        sortSelect.addEventListener('change', applyFilters);

        loadData();
    }



    inv.showRecipeEditorModal = showRecipeEditorModal;
    inv.showProductionModal = showProductionModal;
    inv.showComboDetailModal = showComboDetailModal;
    inv.showDeleteRecipeConfirmModal = showDeleteRecipeConfirmModal;
    inv.renderRecipeProductionPage = renderRecipeProductionPage;
})();
