/**
 * inventory-shared.js — Tiện ích và hàm dùng chung cho module Kho hàng.
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    if (!hub) return;

    var inv = window.CinemaHubInventory = window.CinemaHubInventory || {};

    function notifyError(err) {
        hub.notify((err && err.message) || 'Đã xảy ra lỗi.', 'err');
    }

    function branchListApiPath(roleMeta) {
        if (!roleMeta) {
            var meta = document.querySelector('meta[name="user-role"]');
            roleMeta = meta ? meta.content : '';
        }
        return (roleMeta === 'ADMIN') ? '/branch' : '/branch/my-location';
    }

  
    //  MODULE KHO HÃ€NG (INVENTORY)

    function setFieldError(fieldEl, message) {
        if (!fieldEl) return;
        clearFieldError(fieldEl);
        fieldEl.style.borderColor = '#dc2626';
        fieldEl.style.boxShadow = '0 0 0 1px #dc2626';
        var err = hub.el('div', {
            class: 'ws-field-error-msg',
            style: 'color: #dc2626; font-size: 0.82rem; font-weight: 500; margin-top: 5px; display: block;'
        }, [message]);
        if (fieldEl.parentNode) {
            if (fieldEl.nextSibling) {
                fieldEl.parentNode.insertBefore(err, fieldEl.nextSibling);
            } else {
                fieldEl.parentNode.appendChild(err);
            }
        }
        var onInput = function () {
            clearFieldError(fieldEl);
            fieldEl.removeEventListener('input', onInput);
            fieldEl.removeEventListener('change', onInput);
        };
        fieldEl.addEventListener('input', onInput);
        fieldEl.addEventListener('change', onInput);
    }

    function clearFieldError(fieldEl) {
        if (!fieldEl) return;
        fieldEl.style.borderColor = '';
        fieldEl.style.boxShadow = '';
        if (fieldEl.parentNode) {
            var existing = fieldEl.parentNode.querySelector('.ws-field-error-msg');
            if (existing) existing.remove();
        }
    }

// Helper functions & Complete Inventory Module (Product & Material)
    function createInventoryModal(opts) {
        var overlay = hub.el('div', { class: 'ws-inv-modal-overlay', role: 'presentation' });
        var panel = hub.el('div', { class: 'ws-inv-modal-panel', role: 'dialog', 'aria-modal': 'true' });
        if (opts.maxWidth) panel.style.maxWidth = opts.maxWidth;

        var closeBtn = hub.el('button', {
            class: 'ws-inv-modal-close',
            type: 'button',
            'aria-label': 'Đóng'
        }, ['×']);

        var theme = opts.theme || 'cyan';
        var head = hub.el('div', { class: 'ws-inv-modal-head ' + theme }, [
            hub.el('div', {}, [
                hub.el('h3', {}, [opts.title || '']),
                opts.subtitle ? hub.el('small', {}, [opts.subtitle]) : null
            ].filter(Boolean)),
            closeBtn
        ]);

        var body = hub.el('div', { class: 'ws-inv-modal-body' });
        if (opts.body) {
            if (Array.isArray(opts.body)) {
                opts.body.forEach(function (el) { if (el) body.appendChild(el); });
            } else {
                body.appendChild(opts.body);
            }
        }

        panel.appendChild(head);
        panel.appendChild(body);
        if (opts.footer) panel.appendChild(opts.footer);

        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        function closeModal() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
            document.removeEventListener('keydown', onKeyDown);
        }
        function onKeyDown(e) { if (e.key === 'Escape') closeModal(); }
        closeBtn.addEventListener('click', closeModal);
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) closeModal();
        });
        document.addEventListener('keydown', onKeyDown);

        return {
            overlay: overlay,
            panel: panel,
            body: body,
            close: closeModal
        };
    }

    function makeInvField(labelText, inputEl, required) {
        var grp = hub.el('div', { class: 'ws-inv-group' });
        if (labelText) {
            var children = [labelText];
            if (required) children.push(hub.el('span', { class: 'ws-inv-req' }, [' *']));
            grp.appendChild(hub.el('label', { class: 'ws-inv-label' }, children));
        }
        if (inputEl) grp.appendChild(inputEl);
        return grp;
    }

    function makeInvFooter(cancelBtn, confirmBtn) {
        return hub.el('div', { class: 'ws-inv-modal-footer' }, [cancelBtn, confirmBtn].filter(Boolean));
    }

    function makeInvRadio(name, value, labelText, colorClass, checked) {
        var radio = hub.el('input', { type: 'radio', name: name, value: value });
        if (checked) radio.checked = true;
        var lbl = hub.el('label', { class: 'ws-inv-radio-opt ' + (colorClass || '') }, [radio, labelText]);
        return { radio: radio, label: lbl };
    }

    function makeStatCard(cls, title, numId) {
        return hub.el('div', { class: 'ws-inv-card ' + cls }, [
            hub.el('div', { class: 'card-title' }, [title]),
            hub.el('div', { id: numId, class: 'card-num' }, ['0'])
        ]);
    }

    function formatMaterialUnit(u) {
        if (!u) return '—';
        var str = String(u).trim();
        var lower = str.toLowerCase();
        if (lower === 'kg') return 'Kg';
        if (lower === 'g' || lower === 'gram') return 'Gram';
        if (lower === 'chai') return 'Chai';
        if (lower === 'cai' || lower === 'cái') return 'Cái';
        if (lower === 'hop' || lower === 'hộp') return 'Hộp';
        if (lower === 'ly') return 'Ly';
        if (lower === 'lon') return 'Lon';
        if (lower === 'tui' || lower === 'túi') return 'Túi';
        if (lower === 'l' || lower === 'lit' || lower === 'lít') return 'Lít';
        if (lower === 'ml') return 'ml';
        return str.charAt(0).toUpperCase() + str.slice(1);
    }

    function formatMaterialCategory(c) {
        if (!c) return '—';
        var str = String(c).trim();
        var upper = str.toUpperCase();
        var norm = upper.normalize('NFD').replace(/[\u0300-\u036f]/g, '');

        if (upper === 'BAP' || norm.indexOf('BAP') !== -1 || upper.indexOf('POPCORN') !== -1 || upper.indexOf('BÁº') !== -1) {
            return 'Bắp hạt';
        }
        if (upper === 'DO_UONG' || norm.indexOf('UONG') !== -1 || upper.indexOf('DRINK') !== -1 || upper.indexOf('BEVERAGE') !== -1 || upper.indexOf('SODA') !== -1 || upper.indexOf('Ä') !== -1) {
            return 'Đồ uống';
        }
        if (upper === 'BAO_BI' || norm.indexOf('BAO') !== -1 || norm.indexOf('LY') !== -1 || upper.indexOf('PACKAGING') !== -1 || upper.indexOf('COC') !== -1 || upper.indexOf('BÃ') !== -1) {
            return 'Bao bì & Ly';
        }
        if (upper === 'GIA_VI' || norm.indexOf('GIA VI') !== -1 || upper.indexOf('SPICE') !== -1 || upper.indexOf('INGREDIENT') !== -1 || upper.indexOf('SEASONING') !== -1 || upper.indexOf('GIA VÁ') !== -1 || upper.indexOf('VÁ»') !== -1) {
            return 'Gia vị & Bơ';
        }
        return str;
    }

    function formatProductType(type) {
        if (!type) return 'Món lẻ';
        var t = String(type).trim().toUpperCase();
        if (t === 'POPCORN' || t === 'FOOD') return 'Bắp rang';
        if (t === 'DRINK') return 'Nước uống';
        if (t === 'COMBO') return 'Combo';
        return type;
    }

    function formatInventoryReason(reason) {
        if (!reason) return '—';
        var str = String(reason).trim();
        var map = {
            'NHAP_HANG_TU_NCC': 'Nhập hàng từ nhà cung cấp',
            'NHAP_LAI_TU_NCC': 'Nhập đổi trả từ NCC',
            'NHAP_KHO': 'Nhập hàng từ nhà cung cấp',
            'RESTOCK': 'Nhập hàng từ nhà cung cấp',
            'KIEM_KE_THUA': 'Kiểm kê thừa (Thực tế nhiều hơn)',
            'KIEM_KE_THIEU': 'Kiểm kê thiếu (Hụt kho)',
            'HU_HONG': 'Hư hỏng / Rách bao bì',
            'HET_HAN': 'Hết hạn sử dụng',
            'MAT_DO_KHACH': 'Thất thoát / Đổ vỡ',
            'XUAT_CHE_BIEN': 'Xuất kho chế biến bắp nước',
            'NHAP_TU_CHE_BIEN': 'Nhập từ chế biến bắp nước',
            'CHE_BIEN': 'Xuất kho chế biến bắp nước',
            'PRODUCE': 'Xuất kho chế biến bắp nước',
            'XUAT_BAN': 'Xuất bán tại quầy',
            'BAN_HANG': 'Xuất bán tại quầy',
            'DON_HANG': 'Xuất bán theo đơn',
            'HOAN_TU_DON_HUY': 'Hoàn từ đơn hủy',
            'DIEU_CHINH_KHAC': 'Điều chỉnh khác',
            'OTHER': 'Lý do khác',
            'ADJUST': 'Điều chỉnh kho'
        };

        var upper = str.toUpperCase();
        if (map[upper]) return map[upper];

        var dashIdx = str.indexOf(' - ');
        if (dashIdx > 0) {
            var code = str.substring(0, dashIdx).trim().toUpperCase();
            var detail = str.substring(dashIdx + 3).trim();
            var label = map[code] || code;
            return detail ? (label + ' — ' + detail) : label;
        }
        return str;
    }

    function formatMoneyVnd(val) {
        if (val == null || val === '' || isNaN(val)) return '—';
        return Number(val).toLocaleString('vi-VN') + ' đ';
    }


    function isDiscreteUnit(u) {
        if (!u) return false;
        var str = String(u).trim().toLowerCase();
        return str === 'cái' || str === 'cai' || str === 'ly' || str === 'cốc' || str === 'coc' ||
               str === 'hộp' || str === 'hop' || str === 'lon' || str === 'túi' || str === 'tui' ||
               str === 'chai' || str === 'ống' || str === 'ong' || str === 'phần' || str === 'phan';
    }

    function getLossPercentage(lossRate) {
        if (lossRate == null || lossRate === '' || isNaN(lossRate)) return 0;
        var r = parseFloat(lossRate);
        if (r <= 0) return 0;
        return r <= 1.0 ? Math.round(r * 10000) / 100 : r;
    }

    function getLossRatio(lossRate) {
        var pct = getLossPercentage(lossRate);
        return pct / 100.0;
    }

    function resolveCookingTime(type) {
        if (!type) return '2.0 phút/phần';
        var t = String(type).trim().toUpperCase();
        if (t === 'POPCORN') return '2.5 phút/mẻ';
        if (t === 'DRINK') return '30 giây/ly';
        if (t === 'COMBO') return 'Đóng gói combo (1 phút)';
        return '2.0 phút/phần';
    }

    function formatDosageNumber(val) {
        if (val == null || val === '' || isNaN(val)) return '0';
        var n = Number(val);
        return Number(n.toFixed(3)).toString();
    }


    inv.notifyError = notifyError;
    inv.branchListApiPath = branchListApiPath;
    inv.setFieldError = setFieldError;
    inv.clearFieldError = clearFieldError;
    inv.createInventoryModal = createInventoryModal;
    inv.makeInvField = makeInvField;
    inv.makeInvFooter = makeInvFooter;
    inv.makeInvRadio = makeInvRadio;
    inv.makeStatCard = makeStatCard;
    inv.formatMaterialUnit = formatMaterialUnit;
    inv.formatMaterialCategory = formatMaterialCategory;
    inv.formatProductType = formatProductType;
    inv.formatInventoryReason = formatInventoryReason;
    inv.formatMoneyVnd = formatMoneyVnd;
    inv.isDiscreteUnit = isDiscreteUnit;
    inv.getLossPercentage = getLossPercentage;
    inv.getLossRatio = getLossRatio;
    inv.resolveCookingTime = resolveCookingTime;
    inv.formatDosageNumber = formatDosageNumber;
})();
