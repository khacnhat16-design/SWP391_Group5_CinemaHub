/**
 * modules-voucher.js — Quản lý voucher cho Admin / Branch Manager.
 *
 * Voucher là mã giảm giá khách dùng khi đặt vé:
 *   PERCENT — giảm % trên tổng đơn
 *   FIXED   — giảm số tiền cố định
 *
 * Backend: GET /api/vouchers (danh sách), POST /api/vouchers (tạo),
 *           POST /api/vouchers/{id}/status (khoá / mở).
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

    function money(value) {
        var n = Number(value || 0);
        return n.toLocaleString('vi-VN') + ' đ';
    }

    function discountLabel(v) {
        if (v.discountType === 'PERCENT') return Number(v.discountValue || 0) + '%';
        return money(v.discountValue);
    }

    function statusBadge(v) {
        var active = v.status === 'ACTIVE';
        var expired = v.expired;
        var tone = 'gray';
        var label = v.status || 'INACTIVE';
        if (active && expired) { tone = 'gray'; label = 'Hết hạn'; }
        else if (active) { tone = 'green'; label = 'Đang hoạt động'; }
        else { tone = 'red'; label = 'Đã khoá'; }
        var wrap = document.createElement('span');
        wrap.className = 'ws-badge ws-status-pill ws-badge-' + tone;
        wrap.textContent = label;
        return wrap;
    }

    function usageLabel(v) {
        var max = Number(v.maxUses || 0);
        var used = Number(v.usedCount || 0);
        return used + ' / ' + max;
    }

    // ================================================================
    //  FORM — tạo voucher mới
    // ================================================================

    function renderVoucherForm() {
        form.renderFormPage({
            breadcrumb: ['Quản lý', { label: 'Voucher', href: ctx + '/console?module=voucher' }, 'Tạo mới'],
            title: 'Tạo voucher mới',
            subtitle: 'Mã giảm giá cho khách khi đặt vé. Voucher áp dụng cho tổng đơn từ mức tối thiểu.',
            submitLabel: 'Tạo voucher',
            sections: [{
                title: 'Thông tin voucher',
                fields: [
                    {
                        name: 'code', label: 'Mã voucher', type: 'text', required: true,
                        placeholder: 'VD: WELCOME10', maxLength: 50,
                        hint: 'Không khoảng trắng, không phân biệt hoa thường khi khách nhập.'
                    },
                    {
                        name: 'discountType', label: 'Loại giảm giá', type: 'select', required: true,
                        options: [
                            { value: 'PERCENT', label: 'Giảm phần trăm (%)' },
                            { value: 'FIXED', label: 'Giảm số tiền cố định (đ)' }
                        ],
                        hint: 'PERCENT: nhập số 1–100. FIXED: nhập số tiền giảm.'
                    },
                    {
                        name: 'discountValue', label: 'Giá trị giảm', type: 'number', required: true,
                        min: 1,
                        customValidate: function (value) {
                            var typeInput = document.getElementById('fld-discountType');
                            if (typeInput && typeInput.value === 'PERCENT' && Number(value) > 100) {
                                return 'Giảm phần trăm chỉ được nhập từ 1 đến 100. Nếu muốn giảm 10.000đ, hãy chọn loại giảm cố định.';
                            }
                            return '';
                        }
                    },
                    {
                        name: 'minOrderAmount', label: 'Đơn tối thiểu (đ)', type: 'number',
                        min: 0, value: 0
                    },
                    {
                        name: 'maxUses', label: 'Số lượt sử dụng', type: 'number', required: true,
                        min: 1, value: 100, hint: 'Mỗi lượt áp dụng sẽ trừ 1. Hết lượt thì voucher không dùng được nữa.'
                    },
                    {
                        name: 'validFrom', label: 'Bắt đầu hiệu lực', type: 'date', required: true,
                        hint: 'Ngày voucher bắt đầu được áp dụng.'
                    },
                    {
                        name: 'validTo', label: 'Kết thúc hiệu lực', type: 'date', required: true,
                        hint: 'Ngày cuối cùng voucher còn hiệu lực.'
                    }
                ]
            }],
            onSubmit: function (data) {
                var payload = {
                    code: String(data.code || '').trim().toUpperCase(),
                    discountType: data.discountType,
                    discountValue: Number(data.discountValue || 0),
                    minOrderAmount: Number(data.minOrderAmount || 0),
                    maxUses: Number(data.maxUses || 0),
                    validFrom: data.validFrom,
                    validTo: data.validTo
                };
                hub.api('/api/vouchers', {
                    method: 'POST',
                    body: JSON.stringify(payload),
                    contentType: 'application/json;charset=UTF-8'
                }).then(function () {
                    hub.notify('Đã tạo voucher ' + payload.code + '.', 'ok');
                    router.go('voucher');
                }).catch(function (err) {
                    notifyError(err);
                });
            },
            onReady: function (formElement) {
                var typeInput = formElement.querySelector('[name="discountType"]');
                var valueInput = formElement.querySelector('[name="discountValue"]');
                var hint = valueInput && valueInput.closest('.ws-field')
                    ? valueInput.closest('.ws-field').querySelector('.ws-field-hint') : null;
                function updateDiscountHelp() {
                    if (!typeInput || !valueInput) return;
                    var isPercent = typeInput.value === 'PERCENT';
                    if (isPercent) valueInput.setAttribute('max', '100');
                    else valueInput.removeAttribute('max');
                    if (hint) {
                        hint.textContent = isPercent
                            ? 'Nhập phần trăm từ 1–100. Muốn giảm số tiền như 10.000đ, chọn loại giảm cố định.'
                            : 'Nhập số tiền muốn giảm (đ).';
                    }
                }
                if (typeInput) typeInput.addEventListener('change', updateDiscountHelp);
                updateDiscountHelp();
            },
            onCancel: function () { router.go('voucher'); }
        });
    }

    // ================================================================
    //  LIST — danh sách voucher
    // ================================================================

    router.register('voucher', {
        list: function () {
            list.render({
                title: 'Voucher',
                subtitle: 'Tạo mã giảm giá và quản lý số lượt sử dụng cho khách hàng.',
                addUrl: ctx + '/console?module=voucher&action=create',
                addLabel: 'Tạo voucher',
                fetcher: function () {
                    return hub.api('/api/vouchers').then(function (res) {
                        // VoucherController bọc kết quả: { success, data: { items: [...] } }
                        var payload = (res && res.success && res.data) ? res.data : res;
                        var items = (payload && Array.isArray(payload.items)) ? payload.items
                            : (Array.isArray(payload) ? payload : []);
                        return { items: items, total: items.length, offset: 0, limit: items.length || 1 };
                    });
                },
                emptyTitle: 'Chưa có voucher nào',
                emptyMessage: 'Bấm "Tạo voucher" để tạo mã giảm giá đầu tiên.',
                columns: [
                    { label: 'Mã', key: 'code' },
                    { label: 'Loại giảm', render: function (v) { return discountLabel(v); } },
                    { label: 'Đơn tối thiểu', render: function (v) { return money(v.minOrderAmount); } },
                    { label: 'Đã dùng', render: function (v) { return usageLabel(v); } },
                    { label: 'Hiệu lực', render: function (v) {
                        return (v.validFrom || '—') + ' → ' + (v.validTo || '—');
                    }},
                    { label: 'Trạng thái', render: function (v) { return statusBadge(v); } }
                ],
                actions: function (v) {
                    var isActive = v.status === 'ACTIVE';
                    return [{
                        label: isActive ? 'Khoá' : 'Mở khoá',
                        class: isActive ? 'danger' : 'secondary',
                        onClick: function () {
                            confirmAction(
                                (isActive ? 'Khoá voucher "' : 'Mở khoá voucher "') + v.code + '"?',
                                function () {
                                    hub.api('/api/vouchers/' + v.id + '/status', {
                                        method: 'POST',
                                        body: JSON.stringify({ status: isActive ? 'INACTIVE' : 'ACTIVE' }),
                                        contentType: 'application/json;charset=UTF-8'
                                    }).then(function () {
                                        hub.notify('Đã cập nhật voucher ' + v.code + '.', 'ok');
                                        router.go('voucher');
                                    }).catch(notifyError);
                                }
                            );
                        }
                    }];
                }
            });
        },
        create: function () { renderVoucherForm(); },
        edit: function (id) {
            // Backend chưa hỗ trợ sửa voucher → tải voucher hiện tại và mở form tạo
            // với giá trị điền sẵn để nhân viên tạo phiên bản mới.
            hub.api('/api/vouchers').then(function (res) {
                var payload = (res && res.success && res.data) ? res.data : res;
                var items = (payload && Array.isArray(payload.items)) ? payload.items : [];
                var v = items.find(function (item) { return String(item.id) === String(id); });
                if (!v) throw new Error('Không tìm thấy voucher.');
                hub.notify('Voucher không sửa tại chỗ — hãy tạo voucher mới với mã khác.', 'warn');
                router.go('voucher');
            }).catch(notifyError);
        }
    });
    
    function notifyError(err) {
        hub.notify((err && err.message) || 'Có lỗi xảy ra. Vui lòng thử lại.', 'err');
    }

    function confirmAction(message, onYes) {
        if (typeof window.CinemaHubConfirm === 'function') {
            window.CinemaHubConfirm(message, onYes);
            return;
        }
        if (window.confirm(message)) onYes();
    }

    window.CinemaHubVoucherModule = {
        renderVoucherForm: renderVoucherForm
    };
})();
