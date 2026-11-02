/**
 * fnb-customer.js — UI cho khách hàng đặt bắp nước (F&B pre-order).
 *
 * Đăng ký các page qua window.CinemaHubFormPages:
 *   - fnb-catalog: thực đơn F&B + thêm vào giỏ (lưu localStorage).
 *   - fnb-cart:   giỏ hàng + chọn chi nhánh nhận + checkout.
 *   - fnb-checkout: đang chờ thanh toán VNPay (polling trạng thái).
 *   - fnb-orders: danh sách đơn của tôi + chi tiết đơn.
 *
 * Backend endpoint đã có sẵn trong ConcessionController:
 *   GET  /concession/products                — danh sách ACTIVE
 *   POST /concession/order                   — tạo pre-order (CUSTOMER, có ticketId hoặc branchId)
 *   POST /concession/order/{id}/pay-vnpay    — tạo session VNPay
 *   GET  /concession/order/{id}/payment-status — polling trạng thái
 *   GET  /concession/orders/mine             — đơn của customer hiện tại
 *   GET  /concession/orders/{id}             — chi tiết đơn
 */
(function () {
    'use strict';

    var hub = window.CinemaHub;
    var router = window.CinemaHubRouter;
    if (!hub || !router) return;

    var el = hub.el;
    var fmtMoney = hub.fmtMoney;
    var fmtDateTime = hub.fmtDateTime;

    // ===== Helpers =====

    function viewRoot() { return document.getElementById('viewRoot'); }

    function card(title, sub) {
        var box = el('section', { class: 'app-card' });
        if (title) box.appendChild(el('h2', {}, [title]));
        if (sub) box.appendChild(el('p', { class: 'sub' }, [sub]));
        return box;
    }

    function emptyState(target, title, message, actionLabel, actionHref, onClick) {
        var box = el('div', { class: 'empty-state' }, [
            el('h3', {}, [title]),
            el('p', {}, [message])
        ]);
        if (actionLabel) {
            var btn = el('button', { class: 'btn', type: 'button' }, [actionLabel]);
            btn.addEventListener('click', function () {
                if (typeof onClick === 'function') onClick();
                else if (actionHref) window.location.href = actionHref;
            });
            box.appendChild(btn);
        }
        target.appendChild(box);
    }

    function errorBox(err) {
        return el('div', { class: 'flash', style: 'color:var(--err);padding:12px;background:rgba(217,54,62,.08);border-radius:8px;margin-bottom:14px' },
            [err && err.message ? err.message : 'Đã xảy ra lỗi.']);
    }

    function requestVnPaySession(orderId) {
        return hub.api('/concession/order/' + encodeURIComponent(orderId) + '/pay-vnpay', {
            method: 'POST',
            body: {
                successUrl: hub.ctx + '/console?module=fnb-checkout&orderId=' + orderId + '&status=success',
                cancelUrl: hub.ctx + '/console?module=fnb-cart'
            }
        });
    }

    function statusBadge(label) {
        var status = {
            'PENDING': { tone: 'orange', text: 'Chờ thanh toán' },
            'PAID': { tone: 'green', text: 'Đã thanh toán' },
            'READY_FOR_PICKUP': { tone: 'green', text: 'Sẵn sàng nhận' },
            'FULFILLED': { tone: 'purple', text: 'Đã nhận hàng' },
            'CANCELLED': { tone: 'red', text: 'Đã hủy' },
            'EXPIRED_NO_SHOW': { tone: 'gray', text: 'Quá hạn nhận' },
            'EXPIRED': { tone: 'gray', text: 'Hết hạn' },
            'FAILED': { tone: 'red', text: 'Thất bại' },
            'APPROVED': { tone: 'green', text: 'Đã duyệt' },
            'REJECTED': { tone: 'red', text: 'Từ chối' }
        }[label] || { tone: 'gray', text: label || 'Không rõ' };
        var span = el('span', {
            class: 'fnb-status-badge fnb-status-' + status.tone
        }, [status.text]);
        return span;
    }

    // ===== Cart (localStorage) =====

    var CART_KEY = 'cinima.fnb.cart.v1';

    function readCart() {
        try {
            var raw = localStorage.getItem(CART_KEY);
            if (!raw) return {};
            var parsed = JSON.parse(raw);
            return (parsed && typeof parsed === 'object') ? parsed : {};
        } catch (_) { return {}; }
    }

    function writeCart(cart) {
        try { localStorage.setItem(CART_KEY, JSON.stringify(cart)); } catch (_) {}
        updateCartBadge();
    }

    function cartCount(cart) {
        var n = 0;
        Object.keys(cart).forEach(function (k) { n += Number(cart[k].qty || 0); });
        return n;
    }

    function cartTotal(cart, products) {
        var sum = 0;
        Object.keys(cart).forEach(function (id) {
            var p = products.find(function (x) { return String(x.id) === String(id); });
            if (p) sum += Number(p.price || 0) * Number(cart[id].qty || 0);
        });
        return sum;
    }

    function updateCartBadge() {
        var badge = document.getElementById('fnb-cart-badge');
        var c = readCart();
        var n = cartCount(c);
        if (badge) {
            badge.textContent = String(n);
            badge.hidden = n === 0;
        }
        document.querySelectorAll('.fnb-cart-button-count').forEach(function (count) {
            count.textContent = String(n);
            count.hidden = n === 0;
        });
    }

    function confirmCartAction(title, message, confirmLabel) {
        return new Promise(function (resolve) {
            var overlay = el('div', {
                class: 'fnb-confirm-overlay',
                role: 'presentation'
            });
            var titleId = 'fnb-confirm-title';
            var dialog = el('section', {
                class: 'fnb-confirm-dialog',
                role: 'alertdialog',
                'aria-modal': 'true',
                'aria-labelledby': titleId
            });
            var icon = el('span', { class: 'fnb-confirm-icon', 'aria-hidden': 'true' }, ['!']);
            var heading = el('h2', { id: titleId }, [title]);
            var description = el('p', {}, [message]);
            var actions = el('div', { class: 'fnb-confirm-actions' });
            var cancelButton = el('button', {
                class: 'btn ghost',
                type: 'button'
            }, ['Giữ lại']);
            var confirmButton = el('button', {
                class: 'btn fnb-confirm-delete',
                type: 'button'
            }, [confirmLabel]);
            var settled = false;

            function finish(confirmed) {
                if (settled) return;
                settled = true;
                document.removeEventListener('keydown', onKeyDown);
                overlay.remove();
                resolve(confirmed);
            }

            function onKeyDown(event) {
                if (event.key === 'Escape') finish(false);
                if (event.key === 'Tab') {
                    event.preventDefault();
                    (event.shiftKey ? confirmButton : cancelButton).focus();
                }
            }

            cancelButton.addEventListener('click', function () { finish(false); });
            confirmButton.addEventListener('click', function () { finish(true); });
            overlay.addEventListener('click', function (event) {
                if (event.target === overlay) finish(false);
            });
            actions.appendChild(cancelButton);
            actions.appendChild(confirmButton);
            dialog.appendChild(icon);
            dialog.appendChild(heading);
            dialog.appendChild(description);
            dialog.appendChild(actions);
            overlay.appendChild(dialog);
            document.body.appendChild(overlay);
            document.addEventListener('keydown', onKeyDown);
            cancelButton.focus();
        });
    }

    // ================================================================
    //  FNB CATALOG — thực đơn
    // ================================================================
    function fnbCatalog() {
        if (typeof hub.setWorkspaceHeader === 'function') hub.setWorkspaceHeader('Thực đơn F&B');
        var root = viewRoot();
        root.replaceChildren();

        var box = card('Thực đơn F&B',
            'Chọn món bắp nước và combo để thêm vào giỏ. Bạn có thể nhận tại quầy chi nhánh đã chọn.');
        box.classList.add('fnb-catalog-page');

        // Toolbar: link tới giỏ + nút reset giỏ
        var toolbar = el('div', { class: 'fnb-catalog-toolbar' });
        var goCartBtn = el('button', { class: 'btn ghost fnb-cart-button', type: 'button', 'aria-label': 'Xem giỏ hàng' }, [
            el('svg', {
                viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.8',
                'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true'
            }, [
                el('path', { d: 'M3 4h2l2.2 10.2a2 2 0 0 0 2 1.6h8.7a2 2 0 0 0 1.9-1.4L22 8H6' }),
                el('circle', { cx: '10', cy: '19', r: '1.4' }),
                el('circle', { cx: '18', cy: '19', r: '1.4' })
            ]),
            'Giỏ hàng',
            el('span', { class: 'fnb-cart-button-count' }, [String(cartCount(readCart()))])
        ]);
        goCartBtn.addEventListener('click', function () { router.go('fnb-cart'); });
        toolbar.appendChild(goCartBtn);
        var clearBtn = el('button', { class: 'btn-ghost fnb-clear-cart', type: 'button' }, [
            el('svg', {
                viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.8',
                'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true'
            }, [
                el('path', { d: 'M3 6h18' }),
                el('path', { d: 'M8 6V4h8v2' }),
                el('path', { d: 'm19 6-1 14H6L5 6' }),
                el('path', { d: 'M10 11v5M14 11v5' })
            ]),
            'Xóa giỏ'
        ]);
        clearBtn.addEventListener('click', function () {
            if (cartCount(readCart()) === 0) return;
            confirmCartAction(
                'Xóa toàn bộ giỏ hàng?',
                'Tất cả món đã chọn sẽ bị xóa khỏi giỏ. Bạn có chắc muốn tiếp tục?',
                'Xóa giỏ hàng'
            ).then(function (confirmed) {
                if (!confirmed) return;
                writeCart({});
                renderProducts();
            });
        });
        toolbar.appendChild(clearBtn);
        box.appendChild(toolbar);

        var grid = el('div', { class: 'fnb-product-grid' });
        box.appendChild(grid);

        root.appendChild(box);

        var products = [];
        hub.api('/concession/products')
            .then(function (data) {
                products = Array.isArray(data) ? data : [];
                renderProducts();
            })
            .catch(function (err) {
                grid.replaceChildren(errorBox(err));
            });

        function renderProducts() {
            grid.replaceChildren();
            if (!products.length) {
                emptyState(grid, 'Chưa có sản phẩm',
                    'Chi nhánh hiện không có sản phẩm F&B nào đang bán.',
                    'Thử lại', null, function () { router.go('fnb-catalog'); });
                return;
            }
            var cart = readCart();
            products.forEach(function (p) {
                var inCart = (cart[String(p.id)] && cart[String(p.id)].qty) || 0;
                var cardNode = el('article', { class: 'fnb-product-card' });

                // Hình ảnh placeholder (placeholder icon, có thể thay bằng URL sau)
                var imgBox = el('div', { class: 'fnb-product-img' }, [
                    el('span', { class: 'fnb-product-placeholder' }, ['🍿']),
                    el('span', { class: 'fnb-product-tag' },
                        [String(p.name || '').toLowerCase().indexOf('combo') >= 0 ? 'COMBO' : 'BẮP & NƯỚC'])
                ]);
                cardNode.appendChild(imgBox);

                // Phần thông tin
                var info = el('div', { class: 'fnb-product-info' });
                info.appendChild(el('h3', { class: 'fnb-product-name' }, [p.name || ('#' + p.id)]));
                if (p.description) {
                    info.appendChild(el('p', { class: 'fnb-product-desc' }, [p.description]));
                }
                info.appendChild(el('div', { class: 'fnb-product-price' }, [fmtMoney(p.price || 0)]));

                // Điều khiển số lượng + nút thêm
                var ctrl = el('div', { class: 'fnb-cart-row' });
                ctrl.appendChild(el('label', { class: 'fnb-qty-label' }, ['Số lượng']));
                var qtyInp = el('input', {
                    type: 'number', min: '1', value: '1',
                    class: 'ws-input-sm fnb-qty-input', 'aria-label': 'Số lượng ' + (p.name || 'sản phẩm')
                });
                ctrl.appendChild(qtyInp);
                var addBtn = el('button', { class: 'btn small', type: 'button' }, ['Thêm vào giỏ']);
                addBtn.addEventListener('click', function () {
                    var q = Math.max(1, parseInt(qtyInp.value, 10) || 1);
                    addToCart(p, q);
                });
                ctrl.appendChild(addBtn);
                info.appendChild(ctrl);

                if (inCart > 0) {
                    info.appendChild(el('div', { class: 'fnb-added-note' },
                        ['Đã thêm: ' + inCart + ' phần']));
                }

                cardNode.appendChild(info);
                grid.appendChild(cardNode);
            });
        }

        function addToCart(product, qty) {
            var cart = readCart();
            var key = String(product.id);
            cart[key] = {
                id: product.id,
                name: product.name,
                price: product.price,
                qty: (cart[key] ? cart[key].qty : 0) + qty
            };
            writeCart(cart);
            hub.notify('Đã thêm ' + qty + ' × ' + product.name + ' vào giỏ', 'ok');
            renderProducts();
        }
    }

    // ================================================================
    //  FNB CART — giỏ hàng + checkout
    // ================================================================
    function fnbCart() {
        if (typeof hub.setWorkspaceHeader === 'function') hub.setWorkspaceHeader('Giỏ hàng F&B');
        var root = viewRoot();
        root.replaceChildren();

        var products = [];
        var branches = [];
        var loyaltyInfo = null;
        var selectedBranchId = '';
        var cart = readCart();

        var box = card('Giỏ hàng F&B',
            'Xem lại đơn bắp nước và chọn chi nhánh nhận hàng trước khi thanh toán.');
        box.classList.add('fnb-cart-page');
        root.appendChild(box);

        var content = el('div', { class: 'fnb-cart-content' });
        box.appendChild(content);

        Promise.all([
            hub.api('/concession/products'),
            hub.api('/branch'),
            hub.api('/loyalty/me')
        ]).then(function (results) {
            products = Array.isArray(results[0]) ? results[0] : [];
            branches = Array.isArray(results[1]) ? results[1] : [];
            loyaltyInfo = results[2];
            render();
        }).catch(function (err) {
            content.replaceChildren(errorBox(err));
        });

        function maxRedeemablePoints() {
            var balance = Number(loyaltyInfo && loyaltyInfo.points);
            var rate = Number(loyaltyInfo && loyaltyInfo.redemptionRate);
            if (!Number.isSafeInteger(balance) || balance < 0 || !Number.isSafeInteger(rate) || rate <= 0) {
                return 0;
            }
            return balance;
        }

        function render() {
            content.replaceChildren();
            cart = readCart();
            var ids = Object.keys(cart);
            if (!ids.length) {
                emptyState(content, 'Giỏ hàng trống',
                    'Bạn chưa thêm sản phẩm nào vào giỏ. Hãy quay lại thực đơn để chọn món.',
                    'Đến thực đơn', null, function () { router.go('fnb-catalog'); });
                return;
            }

            var layout = el('div', { class: 'fnb-cart-layout' });
            var list = el('div', { class: 'fnb-cart-items' });
            ids.forEach(function (id) {
                var line = cart[id];
                var product = products.find(function (p) { return String(p.id) === String(id); });
                if (!product) return;
                var row = el('article', { class: 'fnb-cart-item' });
                row.appendChild(el('div', { class: 'fnb-cart-item-img' }, [
                    el('span', { class: 'fnb-product-placeholder' }, ['🍿'])
                ]));
                var stockHint = null;
                var isOverStock = false;
                if (typeof product.stock === 'number') {
                    if (line.qty > product.stock) {
                        isOverStock = true;
                        stockHint = el('span', { style: 'display:inline-block;color:#dc2626;font-weight:600;font-size:0.8rem;margin-left:6px;' }, ['(\u26A0 Chi nh\u00E1nh ch\u1EC9 c\u00F2n ' + product.stock + ')']);
                    } else {
                        stockHint = el('span', { style: 'display:inline-block;color:#059669;font-size:0.8rem;margin-left:6px;' }, ['(C\u00F2n ' + product.stock + ')']);
                    }
                }
                var unitSpans = [fmtMoney(product.price || 0) + ' / ph\u1EA7n'];
                if (stockHint) unitSpans.push(stockHint);
                row.appendChild(el('div', { class: 'fnb-cart-item-info' }, [
                    el('strong', { class: 'fnb-cart-item-name' }, [product.name || ('#' + product.id)]),
                    el('span', { class: 'fnb-cart-item-unit' }, unitSpans)
                ]));
                var qtyWrap = el('div', { class: 'fnb-qty-ctrl' });
                var minus = el('button', {
                    class: 'fnb-qty-button', type: 'button', 'aria-label': 'Giảm số lượng'
                }, ['−']);
                var qtyInp = el('input', {
                    type: 'number', class: 'ws-input-sm fnb-qty-val',
                    min: '1', max: '999', step: '1', value: String(line.qty),
                    style: 'width:60px;text-align:center;margin:0 4px;padding:2px 4px;',
                    'aria-label': 'Số lượng'
                });
                qtyInp.addEventListener('change', function () {
                    var val = parseInt(this.value, 10);
                    if (isNaN(val) || val < 1) val = 1;
                    if (val > 999) val = 999;
                    setQty(id, val);
                });
                var plus = el('button', {
                    class: 'fnb-qty-button', type: 'button', 'aria-label': 'Tăng số lượng'
                }, ['+']);
                minus.addEventListener('click', function () { changeQty(id, -1); });
                plus.addEventListener('click', function () { changeQty(id, 1); });
                qtyWrap.appendChild(minus);
                qtyWrap.appendChild(qtyInp);
                qtyWrap.appendChild(plus);
                row.appendChild(qtyWrap);

                var right = el('div', { class: 'fnb-cart-item-right' }, [
                    el('strong', { class: 'fnb-cart-item-total' }, [fmtMoney((product.price || 0) * line.qty)])
                ]);
                var rmBtn = el('button', { class: 'fnb-remove-button', type: 'button' }, ['Xóa']);
                rmBtn.addEventListener('click', function () { removeItem(id, product.name); });
                right.appendChild(rmBtn);
                row.appendChild(right);

                list.appendChild(row);
            });
            layout.appendChild(list);

            var total = cartTotal(cart, products);
            var maxPoints = maxRedeemablePoints();
            var summary = el('aside', { class: 'fnb-cart-summary' });
            summary.appendChild(el('h3', { class: 'fnb-summary-title' }, ['Tóm tắt đơn hàng']));
            summary.appendChild(el('div', { class: 'fnb-subtotal-row' }, [
                el('span', {}, ['Tạm tính (' + cartCount(cart) + ' phần)']),
                el('strong', {}, [fmtMoney(total)])
            ]));
            var totalRow = el('div', { class: 'fnb-final-row' }, [
                el('span', {}, ['Tổng cộng']),
                el('strong', {}, [fmtMoney(total)])
            ]);
            summary.appendChild(totalRow);

            var pointsWrap = el('div', { class: 'fnb-summary-section' });
            pointsWrap.appendChild(el('label', { class: 'ws-form-label' }, ['Điểm muốn dùng để giảm giá']));
            var pointsInput = el('input', {
                type: 'number',
                min: '0',
                max: String(maxPoints),
                step: '1',
                value: '0',
                inputmode: 'numeric',
                class: 'ws-input fnb-points-input'
            });
            pointsWrap.appendChild(pointsInput);
            var pointsNote = el('small', { class: 'fnb-field-note', 'aria-live': 'polite' }, [
                'Bạn có ' + Number(loyaltyInfo.points || 0).toLocaleString('vi-VN')
                    + ' điểm khả dụng. Điểm chỉ bị trừ khi thanh toán thành công.'
            ]);
            pointsWrap.appendChild(pointsNote);
            pointsInput.addEventListener('input', function () {
                var used = pointsInput.value === '' ? 0 : Number(pointsInput.value);
                var invalid = !Number.isSafeInteger(used) || used < 0 || used > maxPoints;
                pointsInput.classList.toggle('is-invalid', invalid);
                pointsInput.setAttribute('aria-invalid', String(invalid));
                pointsNote.classList.toggle('is-error', invalid);
                pointsNote.textContent = invalid
                    ? 'Số điểm phải từ 0 đến ' + maxPoints.toLocaleString('vi-VN') + '.'
                    : 'Bạn có ' + Number(loyaltyInfo.points || 0).toLocaleString('vi-VN')
                        + ' điểm khả dụng. Điểm chỉ bị trừ khi thanh toán thành công.';
            });
            summary.appendChild(pointsWrap);

            var branchWrap = el('div', { class: 'fnb-summary-section' });
            branchWrap.appendChild(el('label', {
                class: 'ws-form-label',
                for: 'fnb-branch-select'
            }, ['Chi nhánh nhận hàng']));
            var branchSel = el('select', {
                id: 'fnb-branch-select',
                class: 'ws-select',
                required: 'required',
                'aria-required': 'true',
                'aria-describedby': 'fnb-branch-error',
                'aria-invalid': 'false'
            });
            branchSel.appendChild(el('option', { value: '' }, ['\u2014 Ch\u1ECDn chi nh\u00E1nh \u2014']));
            branches.forEach(function (b) {
                var opt = el('option', { value: String(b.id) }, [b.name || ('#' + b.id)]);
                if (String(b.id) === String(selectedBranchId)) opt.selected = true;
                branchSel.appendChild(opt);
            });
            if (selectedBranchId) branchSel.value = String(selectedBranchId);
            branchWrap.appendChild(branchSel);
            var branchError = el('small', {
                id: 'fnb-branch-error',
                class: 'fnb-field-error',
                role: 'alert'
            }, ['Vui lòng chọn chi nhánh nhận hàng.']);
            branchError.hidden = true;
            if (!branches.length) {
                branchSel.disabled = true;
                branchError.textContent = 'Hiện không có chi nhánh đang hoạt động để nhận đơn.';
                branchError.hidden = false;
            }
            branchSel.addEventListener('change', function () {
                branchError.hidden = true;
                branchSel.classList.remove('is-invalid');
                branchSel.setAttribute('aria-invalid', 'false');
                selectedBranchId = branchSel.value;
                if (selectedBranchId) {
                    hub.api('/concession/products?branchId=' + encodeURIComponent(selectedBranchId))
                        .then(function (res) {
                            if (Array.isArray(res)) {
                                products = res;
                                render();
                            }
                        }).catch(function () {});
                } else {
                    products.forEach(function (p) { delete p.stock; });
                    render();
                }
            });
            branchWrap.appendChild(branchError);
            summary.appendChild(branchWrap);

            var payWrap = el('div', { class: 'fnb-summary-section' });
            payWrap.appendChild(el('label', { class: 'ws-form-label' }, ['Phương thức thanh toán']));
            var paySel = el('select', { class: 'ws-select' });
            paySel.appendChild(el('option', { value: 'VNPAY' }, ['VNPay']));
            payWrap.appendChild(paySel);
            summary.appendChild(payWrap);

            var actions = el('div', { class: 'fnb-cart-actions' });
            var backBtn = el('button', {
                class: 'btn ghost fnb-back-button',
                type: 'button'
            }, ['Tiếp tục chọn món']);
            backBtn.addEventListener('click', function () { router.go('fnb-catalog'); });
            actions.appendChild(backBtn);

            var checkoutBtn = el('button', { class: 'btn fnb-checkout-button', type: 'button' }, ['Đặt đơn & thanh toán']);
            checkoutBtn.addEventListener('click', function () {
                checkout(branchSel, branchError, paySel.value, pointsInput.value);
            });
            checkoutBtn.disabled = branches.length === 0;
            actions.appendChild(checkoutBtn);
            summary.appendChild(actions);
            layout.appendChild(summary);
            content.appendChild(layout);
        }

        function setQty(id, qty) {
            var cart = readCart();
            if (!cart[id]) return;
            var q = parseInt(qty, 10);
            if (isNaN(q) || q <= 0) delete cart[id];
            else cart[id].qty = Math.min(999, q);
            writeCart(cart);
            render();
        }

        function changeQty(id, delta) {
            var cart = readCart();
            if (!cart[id]) return;
            cart[id].qty += delta;
            if (cart[id].qty <= 0) delete cart[id];
            writeCart(cart);
            render();
        }

        function removeItem(id, productName) {
            confirmCartAction(
                'Xóa món khỏi giỏ?',
                'Món "' + (productName || 'sản phẩm này') + '" sẽ bị xóa khỏi giỏ hàng.',
                'Xóa món'
            ).then(function (confirmed) {
                if (!confirmed) return;
                var cart = readCart();
                delete cart[id];
                writeCart(cart);
                render();
            });
        }

        function checkout(branchSelect, branchError, payMethod, pointsValue) {
            var branchId = Number(branchSelect.value);
            var branchExists = Number.isSafeInteger(branchId) && branchId > 0
                && branches.some(function (branch) {
                    return Number(branch.id) === branchId;
                });
            if (!branchExists) {
                branchError.textContent = branches.length
                    ? 'Vui lòng chọn một chi nhánh đang hoạt động để nhận hàng.'
                    : 'Hiện không có chi nhánh đang hoạt động để nhận đơn.';
                branchError.hidden = false;
                branchSelect.classList.add('is-invalid');
                branchSelect.setAttribute('aria-invalid', 'true');
                branchSelect.focus();
                hub.notify(branchError.textContent, 'warn');
                return;
            }
            if (payMethod !== 'VNPAY') {
                hub.notify('Vui lòng chọn phương thức thanh toán hợp lệ.', 'err');
                return;
            }
            var pointsUsed = pointsValue === '' ? 0 : Number(pointsValue);
            var maxPoints = maxRedeemablePoints();
            if (!Number.isSafeInteger(pointsUsed) || pointsUsed < 0 || pointsUsed > maxPoints) {
                hub.notify('Số điểm phải là số nguyên từ 0 đến '
                    + maxPoints.toLocaleString('vi-VN') + '.', 'err');
                return;
            }
            var cart = readCart();
            var ids = Object.keys(cart);
            if (!ids.length) return;

            var lines = ids.map(function (id) {
                return { productId: Number(id), qty: Number(cart[id].qty) };
            });

            var overStockLine = lines.find(function (l) {
                var p = products.find(function (x) { return Number(x.id) === l.productId; });
                return p && typeof p.stock === 'number' && l.qty > p.stock;
            });
            if (overStockLine) {
                var pObj = products.find(function (x) { return Number(x.id) === overStockLine.productId; });
                hub.notify('S\u1EA3n ph\u1EA9m "' + (pObj ? pObj.name : '') + '" kh\u00F4ng \u0111\u1EE7 t\u1ED3n kho t\u1EA1i chi nh\u00E1nh (hi\u1EC7n c\u00F2n ' + (pObj ? pObj.stock : 0) + ', b\u1EA1n \u0111ang \u0111\u1EB7t ' + overStockLine.qty + '). Vui l\u00F2ng gi\u1EA3m s\u1ED1 l\u01B0\u1EE3ng!', 'err');
                return;
            }

            // Backend nhận lines dạng "productId:qty,productId:qty"
            var linesStr = lines.map(function (l) { return l.productId + ':' + l.qty; }).join(',');

            hub.api('/concession/order', {
                method: 'POST',
                body: {
                    branchId: branchId,
                    lines: linesStr,
                    payMethod: payMethod,
                    pointsUsed: pointsUsed
                }
            }).then(function (order) {
                writeCart({});
                if (order && Number(order.pointsUsed) > 0) {
                    hub.notify('Đã áp dụng ' + order.pointsUsed + ' điểm, giảm '
                        + fmtMoney(order.pointsDiscount) + '.', 'ok');
                }
                var orderId = order && (order.id || order.orderId);
                if (!orderId) {
                    hub.notify('Đã tạo đơn nhưng không lấy được mã.', 'warn');
                    router.go('fnb-orders');
                    return;
                }

                var amountDue = Math.max(0,
                    Number(order.totalAmount || 0) - Number(order.pointsDiscount || 0));
                if (amountDue === 0) {
                    return hub.api('/concession/order/pay', {
                        method: 'POST',
                        body: { orderId: orderId, payMethod: 'POINTS' }
                    }).then(function () {
                        hub.notify('Đơn hàng đã được thanh toán bằng điểm.', 'ok');
                        router.go('fnb-orders');
                    });
                }

                if (payMethod === 'VNPAY') {
                    return requestVnPaySession(orderId).then(function (pay) {
                        if (pay && pay.redirectUrl) {
                            window.location.href = pay.redirectUrl;
                        } else {
                            hub.notify('Không lấy được URL thanh toán VNPay.', 'err');
                            router.go('fnb-orders');
                        }
                    });
                }
            }).catch(function (err) {
                hub.notify(err.message || 'Đặt đơn thất bại', 'err');
            });
        }
    }

    // ================================================================
    //  FNB CHECKOUT — chờ thanh toán VNPay
    // ================================================================
    function fnbCheckout() {
        if (typeof hub.setWorkspaceHeader === 'function') hub.setWorkspaceHeader('Đang thanh toán');
        var root = viewRoot();
        root.replaceChildren();

        var params = new URLSearchParams(window.location.search);
        var orderId = params.get('orderId');
        var paymentReason = params.get('reason');

        var box = card('Đang xử lý thanh toán', 'Đơn hàng đang được xác nhận với cổng thanh toán. Vui lòng không đóng trang này.');
        root.appendChild(box);

        if (!orderId) {
            emptyState(box, 'Thiếu mã đơn',
                'Không xác định được đơn F&B cần kiểm tra. Hãy quay lại danh sách đơn.',
                'Về đơn của tôi', null, function () { router.go('fnb-orders'); });
            return;
        }

        var statusBox = el('div', { style: 'text-align:center;padding:24px' });
        statusBox.appendChild(el('div', { class: 'loading-state' }, [
            el('span', { class: 'spinner', 'aria-hidden': 'true' }),
            el('span', {}, ['Đang chờ cổng thanh toán xác nhận…'])
        ]));
        box.appendChild(statusBox);

        function poll() {
            hub.api('/concession/order/' + encodeURIComponent(orderId) + '/payment-status')
                .then(function (data) {
                    var ps = data && data.paymentStatus;
                    if (ps === 'SUCCESS') {
                        statusBox.replaceChildren(el('div', { style: 'color:var(--ok);font-weight:700;font-size:1.1rem' },
                            ['Thanh toán thành công! Đang chuyển về danh sách đơn…']));
                        setTimeout(function () { router.go('fnb-orders'); }, 1200);
                    } else if (ps === 'FAILED') {
                        statusBox.replaceChildren(el('div', { style: 'color:var(--err);font-weight:700' },
                            [paymentReason
                                ? 'Thanh toán chưa hoàn tất: ' + paymentReason
                                : 'Giao dịch đã hết hạn hoặc chưa hoàn tất. Bạn có thể thử thanh toán lại.']));
                        var actions = el('div', { class: 'actions', style: 'justify-content:center;margin-top:16px' });
                        var retry = el('button', { class: 'btn', type: 'button' }, ['Thử thanh toán lại']);
                        retry.addEventListener('click', function () {
                            retry.disabled = true;
                            retry.textContent = 'Đang tạo giao dịch…';
                            requestVnPaySession(orderId).then(function (payment) {
                                if (!payment || !payment.redirectUrl) {
                                    throw new Error('Không nhận được liên kết thanh toán VNPay. Vui lòng thử lại.');
                                }
                                window.location.assign(payment.redirectUrl);
                            }).catch(function (err) {
                                statusBox.appendChild(errorBox(err));
                                retry.disabled = false;
                                retry.textContent = 'Thử thanh toán lại';
                            });
                        });
                        var back = el('button', { class: 'btn ghost', type: 'button' }, ['Về danh sách đơn']);
                        back.addEventListener('click', function () { router.go('fnb-orders'); });
                        actions.append(retry, back);
                        statusBox.appendChild(actions);
                    } else {
                        // PENDING → tiếp tục poll
                        setTimeout(poll, 2000);
                    }
                })
                .catch(function (err) {
                    statusBox.replaceChildren(errorBox(err));
                });
        }
        poll();
    }

    // ================================================================
    //  FNB ORDERS — đơn của tôi
    // ================================================================
    function openFnbRefundRequest(order) {
        var backdrop = el('div', { class: 'modal-backdrop refund-modal-backdrop' });
        var modal = el('div', {
            class: 'modal-card refund-modal',
            role: 'dialog',
            'aria-modal': 'true',
            'aria-labelledby': 'fnb-refund-title'
        });
        var form = el('form', { class: 'ws-form refund-form' });
        var close = function () { backdrop.remove(); };
        modal.appendChild(el('div', { class: 'refund-modal-header' }, [
            el('div', {}, [
                el('h2', { id: 'fnb-refund-title' }, ['Yêu cầu hoàn tiền F&B']),
                el('p', { class: 'muted' }, ['Yêu cầu hoàn 100% giá trị thanh toán; không giới hạn thời gian.'])
            ]),
            el('button', { type: 'button', class: 'refund-modal-close', 'aria-label': 'Đóng' }, ['×'])
        ]));
        modal.querySelector('.refund-modal-close').addEventListener('click', close);
        modal.appendChild(el('p', { class: 'refund-summary' }, [
            'Đơn ', el('strong', {}, [order.orderCode || ('#' + order.id)]),
            ' · Tiền hoàn ', el('strong', {}, [fmtMoney(order.amountAfterPointsDiscount || 0)]),
            order.pointsUsed > 0 ? ' và ' + order.pointsUsed + ' điểm sẽ được hoàn lại.' : '.'
        ]));

        function field(id, label, type, required, maxLength) {
            var wrap = el('div', { class: 'ws-field' });
            wrap.appendChild(el('label', { for: id }, [label]));
            var input = el('input', {
                id: id, name: id, type: type || 'text',
                class: 'ws-input', maxlength: String(maxLength || 120)
            });
            if (required) input.required = true;
            wrap.appendChild(input);
            form.appendChild(wrap);
            return input;
        }
        var bankInput = field('fnb-refund-bank', 'Ngân hàng', 'text', true, 120);
        field('fnb-refund-account-name', 'Tên chủ tài khoản', 'text', true, 120);
        field('fnb-refund-account-number', 'Số tài khoản', 'text', true, 40);
        var reasonWrap = el('div', { class: 'ws-field' });
        reasonWrap.appendChild(el('label', { for: 'fnb-refund-reason' }, ['Lý do (không bắt buộc)']));
        var reason = el('textarea', {
            id: 'fnb-refund-reason', name: 'reason', rows: '3',
            class: 'ws-input', maxlength: '500'
        });
        reasonWrap.appendChild(reason);
        form.appendChild(reasonWrap);
        var actions = el('div', { class: 'ws-form-actions' });
        var cancel = el('button', { class: 'ws-btn secondary', type: 'button' }, ['Để sau']);
        var submit = el('button', { class: 'ws-btn primary', type: 'submit' }, ['Gửi yêu cầu']);
        cancel.addEventListener('click', close);
        actions.append(cancel, submit);
        form.appendChild(actions);
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            submit.disabled = true;
            hub.api('/concession/refunds', {
                method: 'POST',
                body: {
                    orderId: order.id,
                    bankName: form.elements['fnb-refund-bank'].value.trim(),
                    bankAccountName: form.elements['fnb-refund-account-name'].value.trim(),
                    bankAccountNumber: form.elements['fnb-refund-account-number'].value.trim(),
                    reason: reason.value.trim()
                }
            }).then(function () {
                close();
                hub.notify('Đã gửi yêu cầu hoàn 100%. Quản lý sẽ xử lý và chuyển tiền về tài khoản.', 'ok');
                router.go('fnb-orders', 'view', order.id);
            }).catch(function (error) {
                hub.notify(error.message || 'Không thể gửi yêu cầu hoàn tiền.', 'err');
                submit.disabled = false;
            });
        });
        modal.appendChild(form);
        backdrop.addEventListener('click', function (event) {
            if (event.target === backdrop) close();
        });
        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);
        bankInput.focus();
    }

    function fnbOrders() {
        if (typeof hub.setWorkspaceHeader === 'function') hub.setWorkspaceHeader('Đơn F&B của tôi');
        var root = viewRoot();
        root.replaceChildren();

        var box = card('Đơn F&B của tôi',
            'Danh sách đơn bắp nước đã đặt. Mang mã đơn đến quầy để nhận hàng.');
        box.classList.add('fnb-orders-page');
        root.appendChild(box);

        var list = el('div');
        box.appendChild(list);

        hub.api('/concession/orders/mine')
            .then(function (data) {
                var orders = Array.isArray(data) ? data : [];
                list.replaceChildren();
                if (!orders.length) {
                    emptyState(list, 'Chưa có đơn nào',
                        'Bạn chưa đặt đơn F&B nào. Hãy khám phá thực đơn.',
                        'Đến thực đơn', null, function () { router.go('fnb-catalog'); });
                    return;
                }
                orders.forEach(function (o) {
                    var row = el('a', {
                        class: 'fnb-order-row',
                        href: hub.ctx + '/console?module=fnb-orders&action=view&id='
                            + encodeURIComponent(o.id)
                    });
                    row.innerHTML =
                        '<div class="fnb-order-row-main">' +
                        '<b class="fnb-order-code">' + escapeHtml(o.orderCode || ('#' + o.id)) + '</b>' +
                        '<span class="fnb-order-meta">' + escapeHtml(hub.fmtNotificationDateTime(o.createdAt)) +
                        (o.branchName ? ' · ' + escapeHtml(o.branchName) : '') +
                        '</span></div>';
                    row.appendChild(statusBadge(o.status));
                    row.appendChild(el('strong', { class: 'fnb-order-price' },
                        [fmtMoney(o.totalAmount || 0)]));
                    list.appendChild(row);
                });
            })
            .catch(function (err) {
                list.replaceChildren(errorBox(err));
            });
    }

    function fnbOrdersView(id) {
        if (typeof hub.setWorkspaceHeader === 'function') hub.setWorkspaceHeader('Chi tiết đơn F&B');
        var root = viewRoot();
        root.replaceChildren();

        var box = card('Chi tiết đơn F&B', 'Thông tin đơn hàng và trạng thái thanh toán.');
        box.classList.add('fnb-order-detail');
        root.appendChild(box);

        Promise.all([
            hub.api('/concession/orders/' + encodeURIComponent(id)),
            hub.api('/concession/refunds/mine')
        ]).then(function (results) {
                var order = results[0];
                var refunds = (results[1] && results[1].items) || [];
                var orderRefunds = refunds.filter(function (refund) {
                    return String(refund.orderId) === String(order.id);
                });
                var latestRefund = orderRefunds[0];
                var head = el('div', { class: 'fnb-order-row fnb-order-detail-header' });
                head.appendChild(el('div', { class: 'fnb-order-row-main' }, [
                    el('b', { class: 'fnb-order-code' }, [order.orderCode || ('#' + order.id)]),
                    el('span', { class: 'fnb-order-meta' }, [hub.fmtNotificationDateTime(order.createdAt) +
                        (order.branchName ? ' · ' + order.branchName : '')])])
                );
                head.appendChild(statusBadge(order.status));
                head.appendChild(el('strong', { class: 'fnb-order-price' },
                    [fmtMoney(order.totalAmount || 0)]));
                box.appendChild(head);

                // Lines
                if (order.lines && order.lines.length) {
                    var tbl = el('table', { class: 'data-table', style: 'width:100%;border-collapse:collapse;margin-bottom:14px' });
                    var thead = el('thead', {}, [el('tr', {}, [
                        el('th', { style: 'text-align:left;padding:8px;border-bottom:1px solid var(--line)' }, ['Sản phẩm']),
                        el('th', { style: 'text-align:right;padding:8px;border-bottom:1px solid var(--line)' }, ['SL']),
                        el('th', { style: 'text-align:right;padding:8px;border-bottom:1px solid var(--line)' }, ['Đơn giá']),
                        el('th', { style: 'text-align:right;padding:8px;border-bottom:1px solid var(--line)' }, ['Thành tiền'])
                    ])]);
                    tbl.appendChild(thead);
                    var tbody = el('tbody');
                    order.lines.forEach(function (ln) {
                        tbody.appendChild(el('tr', {}, [
                            el('td', { style: 'padding:8px;border-bottom:1px solid var(--line)' }, [ln.productName || ('#' + ln.productId)]),
                            el('td', { style: 'padding:8px;text-align:right;border-bottom:1px solid var(--line)' }, [String(ln.qty || 0)]),
                            el('td', { style: 'padding:8px;text-align:right;border-bottom:1px solid var(--line)' }, [fmtMoney(ln.unitPrice || 0)]),
                            el('td', { style: 'padding:8px;text-align:right;border-bottom:1px solid var(--line)' }, [fmtMoney(ln.subtotal || 0)])
                        ]));
                    });
                    tbl.appendChild(tbody);
                    box.appendChild(tbl);
                }

                // Pickup code (nếu đã sẵn sàng)
                if (order.pickupCode) {
                    box.appendChild(el('div', { class: 'fnb-order-pickup' }, [
                        el('strong', {}, ['Mã nhận hàng (pickup code):']),
                        el('div', { class: 'fnb-order-pickup-code' },
                            [order.pickupCode]),
                        el('canvas', {
                            class: 'ticket-barcode',
                            width: '320',
                            height: '64',
                            'aria-label': 'Mã vạch nhận hàng'
                        })
                    ]));
                    hub.renderBarcode(box.querySelector('.ticket-barcode'), order.pickupCode);
                }

                if (latestRefund) {
                    box.appendChild(el('div', { class: 'refund-summary' }, [
                        'Yêu cầu hoàn tiền: ', statusBadge(latestRefund.status),
                        ' · Số tiền hoàn: ', fmtMoney(latestRefund.refundAmount || 0),
                        latestRefund.refundPoints > 0 ? ' · ' + latestRefund.refundPoints + ' điểm' : ''
                    ]));
                    if (latestRefund.managerNote) {
                        box.appendChild(el('p', { class: 'muted' }, ['Ghi chú: ' + latestRefund.managerNote]));
                    }
                }
                var hasOpenOrApprovedRefund = orderRefunds.some(function (refund) {
                    return refund.status === 'PENDING' || refund.status === 'APPROVED';
                });
                if (!hasOpenOrApprovedRefund
                        && (order.status === 'READY_FOR_PICKUP' || order.status === 'FULFILLED')) {
                    var refundButton = el('button', { class: 'btn', type: 'button' }, ['Yêu cầu hoàn 100%']);
                    refundButton.addEventListener('click', function () { openFnbRefundRequest(order); });
                    box.appendChild(refundButton);
                }

                // Action back
                var back = el('button', { class: 'btn ghost', type: 'button' }, ['Về danh sách đơn']);
                back.addEventListener('click', function () { router.go('fnb-orders'); });
                box.appendChild(back);
            })
            .catch(function (err) {
                box.appendChild(errorBox(err));
            });
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
        });
    }

    // ===== Đăng ký router =====
    router.register('fnb-catalog', { list: fnbCatalog });
    router.register('fnb-cart', { list: fnbCart });
    router.register('fnb-checkout', { list: fnbCheckout });
    router.register('fnb-orders', { list: fnbOrders, view: fnbOrdersView });

    // Cập nhật badge giỏ hàng khi load
    updateCartBadge();
    document.addEventListener('DOMContentLoaded', updateCartBadge);
})();
