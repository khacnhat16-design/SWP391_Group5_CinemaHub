// POS_TICKET_FOR_STAFF
(function () {
    'use strict';
    if (window.__CinemaHubPosStaffLoaded) return;
    window.__CinemaHubPosStaffLoaded = true;

    // ---------- Local helpers ----------
    function todayIso() {
        var d = new Date();
        var m = String(d.getMonth() + 1);
        if (m.length < 2) m = '0' + m;
        var dd = String(d.getDate());
        if (dd.length < 2) dd = '0' + dd;
        return d.getFullYear() + '-' + m + '-' + dd;
    }

    function el(tag, attrs, children) {
        return window.CinemaHub ? window.CinemaHub.el(tag, attrs, children) : domEl(tag, attrs, children);
    }
    function domEl(tag, attrs, children) {
        var n = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (k) {
            if (k === 'class') n.className = attrs[k];
            else if (k === 'html') n.innerHTML = attrs[k];
            else if (k === 'style') n.setAttribute('style', attrs[k]);
            else if (k.indexOf('on') === 0 && typeof attrs[k] === 'function') n.addEventListener(k.slice(2), attrs[k]);
            else if (attrs[k] != null) n.setAttribute(k, attrs[k]);
        });
        (children || []).forEach(function (c) { n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
        return n;
    }
    function escapeHtml(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
    function fmtMoney(v) { try { return window.CinemaHub.fmtMoney(v); } catch (e) { return Number(v || 0).toLocaleString('vi-VN') + ' đ'; } }
    function fmtTime(s) { try { return window.CinemaHub.fmtDateTime(s); } catch (e) { return String(s || '').replace('T', ' ').slice(0, 16); } }
    function setOrderTitle(card, title) {
        var h = card.querySelector('h3'); if (h) h.textContent = title;
    }
    function wrapContent(title, content) {
        return el('section', { class: 'ws-card' }, [
            el('h3', { class: 'ws-card-title' }, [title]),
            content
        ]);
    }
    function wrap(title, content, klass) {
        var card = el('section', { class: 'ws-card ' + (klass || '') }, [el('h3', { class: 'ws-card-title' }, [title])]);
        var body = el('div', { class: 'pos-card-body' });
        if (content) body.appendChild(content); else body.appendChild(el('div', { class: 'ws-empty' }, [el('p', {}, ['Chưa có nội dung.'])]));
        card.appendChild(body);
        return card;
    }
    function show(node) { if (node) node.classList.remove('hidden-card'); }

    function modulePosTicket() {
        var hub = window.CinemaHub;
        var viewRoot = document.getElementById('viewRoot');
        if (!hub || !viewRoot) return Promise.resolve();

        var heading = el('div', { class: 'ws-page-heading' }, [
            el('span', { class: 'eyebrow' }, ['Counter sale']),
            el('h1', {}, ['POS - Bán vé tại quầy']),
            el('p', {}, ['Chọn suất chiếu, ghế và xác nhận bán vé tại quầy.'])
        ]);

        var toolbar = el('div', { class: 'ws-toolbar' });
        var branchSel = el('select', { class: 'pos-input' }, [el('option', { value: '' }, ['-- Chọn chi nhánh --'])]);
        var dateInput = el('input', { type: 'date', value: todayIso(), class: 'pos-input' });
        var movieInp = el('input', { type: 'text', placeholder: 'Lọc phim (tùy chọn)', class: 'pos-input' });
        var reloadBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Tải suất chiếu']);
        toolbar.appendChild(el('label', { class: 'pos-field' }, [el('span', {}, ['Chi nhánh']), branchSel]));
        toolbar.appendChild(el('label', { class: 'pos-field' }, [el('span', {}, ['Ngày']), dateInput]));
        toolbar.appendChild(el('label', { class: 'pos-field' }, [el('span', {}, ['Phim']), movieInp]));
        toolbar.appendChild(reloadBtn);

        var showtimeList = el('div', { class: 'pos-showtime-list' });
        var seatCard = wrap('Sơ đồ ghế', null, 'pos-seat-card hidden-card');
        var orderCard = wrap('Phiếu bán vé', null, 'pos-order-card hidden-card');

        showtimeList.appendChild(el('div', { class: 'ws-empty' }, [
            el('p', {}, ['Chọn chi nhánh / ngày rồi bấm "Tải suất chiếu".'])
        ]));

        var root = el('div', {}, [heading, toolbar, showtimeList, seatCard, orderCard]);
        viewRoot.replaceChildren(root);

        var currentShowtimes = [];

        hub.api('/api/branch?status=ACTIVE').then(function (res) {
            var items = res && Array.isArray(res.items) ? res.items : (Array.isArray(res) ? res : []);
            items.forEach(function (b) {
                branchSel.appendChild(el('option', { value: String(b.id) }, [b.name || ('Branch #' + b.id)]));
            });
            if (items.length === 1) branchSel.value = String(items[0].id);
        }).catch(function () { /* ignore */ });

        reloadBtn.addEventListener('click', function () { loadShowtimes(); });
        movieInp.addEventListener('input', function () { if (currentShowtimes.length) renderShowtimes(currentShowtimes); });

        function loadShowtimes() {
            if (!branchSel.value) {
                hub.notify('Hãy chọn chi nhánh trước', 'warn');
                return;
            }
            showtimeList.replaceChildren(el('div', { class: 'ws-empty' }, [el('p', {}, ['Đang tải...'])]));
            var qs = new URLSearchParams();
            qs.set('branchId', branchSel.value);
            qs.set('date', dateInput.value);
            hub.api('/showtime/discovery?' + qs.toString()).then(function (data) {
                currentShowtimes = Array.isArray(data) ? data : [];
                renderShowtimes(currentShowtimes);
            }).catch(function (e) {
                showtimeList.replaceChildren(el('div', { class: 'ws-empty' }, [
                    el('h3', {}, ['Lỗi tải suất chiếu']),
                    el('p', {}, [e.message || ''])
                ]));
            });
        }

        function renderShowtimes(list) {
            var q = (movieInp.value || '').trim().toLowerCase();
            var rows = q ? list.filter(function (s) { return (s.movieTitle || '').toLowerCase().indexOf(q) >= 0; }) : list;
            showtimeList.replaceChildren();
            if (!rows.length) {
                showtimeList.appendChild(el('div', { class: 'ws-empty' }, [
                    el('h3', {}, ['Không có suất chiếu']),
                    el('p', {}, ['Thử đổi ngày hoặc chi nhánh khác.'])
                ]));
                return;
            }
            var table = el('table', { class: 'ws-table' });
            table.innerHTML = '<thead><tr><th>Phim</th><th>Phòng</th><th>Giờ</th><th>Ghế trống</th><th>Giá</th><th></th></tr></thead>';
            var tbody = el('tbody');
            rows.forEach(function (s) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td><strong>' + escapeHtml(s.movieTitle || '-') + '</strong></td>'
                    + '<td>' + escapeHtml(s.screenName || '-') + '</td>'
                    + '<td>' + escapeHtml((s.startTime || '').replace('T', ' ').slice(0, 16)) + '</td>'
                    + '<td>' + (s.availableSeats != null ? Number(s.availableSeats) : '-') + '</td>'
                    + '<td>' + fmtMoney(s.standardPrice) + '</td>'
                    + '<td></td>';
                var btn = el('button', { class: 'ws-btn small primary', type: 'button' }, ['Chọn ghế']);
                var td = tr.lastChild;
                td.appendChild(btn);
                if (s.showtimePassed) {
                    tr.classList.add('row-disabled');
                    btn.disabled = true; btn.textContent = 'Đã chiếu';
                } else {
                    btn.addEventListener('click', function () { selectShowtime(s); });
                }
                tbody.appendChild(tr);
            });
            table.appendChild(tbody);
            showtimeList.appendChild(wrapContent('Suất chiếu khả dụng', table));
        }

        function selectShowtime(st) {
            show(seatCard); show(orderCard);
            var body = seatCard.querySelector('.pos-card-body');
            body.replaceChildren(el('div', { class: 'ws-empty' }, [el('p', {}, ['Đang tải sơ đồ ghế...'])]));
            setOrderTitle(seatCard, 'Ghế - ' + (st.movieTitle || '') + ' - ' + fmtTime(st.startTime));

            hub.api('/showtime/' + st.id).then(function (detail) {
                var seats = (detail && detail.seats) || [];
                var showtimeInfo = Object.assign({}, st, {
                    seatPrices: (detail && detail.seatPrices) || st.seatPrices || {}
                });
                renderSeatMap(body, seats, st, showtimeInfo);
            }).catch(function (e) {
                body.replaceChildren(el('div', { class: 'flash flash-err' }, [e.message || 'Lỗi tải sơ đồ']));
            });
        }

        function renderSeatMap(host, seats, st, showtimeInfo) {
            host.replaceChildren();
            var area = el('div', { class: 'pos-seat-area' });
            var grid = el('div', { class: 'pos-seat-grid' });
            grid.appendChild(el('div', { class: 'pos-screen' }, ['-- MÀN HÌNH --']));
            var rows = {};
            seats.forEach(function (s) {
                var r = s.rowLabel || 'A';
                if (!rows[r]) rows[r] = [];
                rows[r].push(s);
            });
            var selected = [];
            function rerenderOrder() {
                renderOrder(showtimeInfo, selected, loadShowtimes);
            }

            Object.keys(rows).sort().forEach(function (r) {
                var rowEl = el('div', { class: 'pos-seat-row' });
                rowEl.appendChild(el('span', { class: 'pos-row-label' }, [r]));
                var container = el('div', { class: 'pos-seats' });
                rows[r].slice().sort(function (a, b) { return (a.colNo || 0) - (b.colNo || 0); }).forEach(function (seat) {
                    var cls = 'pos-seat ' + (
                        seat.status === 'AVAILABLE' ? 'available'
                        : seat.status === 'HOLD' ? 'hold'
                        : 'sold'
                    );
                    if (seat.seatType === 'VIP') cls += ' vip';
                    if (seat.seatType === 'COUPLE') cls += ' couple';
                    var btn = el('button', { class: cls, type: 'button' }, [String(seat.rowLabel) + seat.colNo]);
                    if (seat.status !== 'AVAILABLE') btn.disabled = true;
                    btn.addEventListener('click', function () {
                        var idx = selected.findIndex(function (x) { return x.seatId === seat.seatId; });
                        if (idx >= 0) {
                            selected.splice(idx, 1);
                            btn.classList.remove('selected');
                        } else {
                            if (selected.length >= 8) { hub.notify('Tối đa 8 ghế/giao dịch', 'warn'); return; }
                            selected.push(seat);
                            btn.classList.add('selected');
                        }
                        rerenderOrder();
                    });
                    container.appendChild(btn);
                });
                rowEl.appendChild(container);
                grid.appendChild(rowEl);
            });
            area.appendChild(grid);
            area.appendChild(el('div', { class: 'pos-seat-legend' }, [
                el('span', {}, [el('span', { class: 'pos-seat available', style: 'pointer-events:none;display:inline-block;width:18px;height:18px' }), ' Trống']),
                el('span', {}, [el('span', { class: 'pos-seat hold', style: 'pointer-events:none;display:inline-block;width:18px;height:18px' }), ' Đang giữ']),
                el('span', {}, [el('span', { class: 'pos-seat sold', style: 'pointer-events:none;display:inline-block;width:18px;height:18px' }), ' Đã bán']),
                el('span', {}, [el('span', { class: 'pos-seat selected', style: 'pointer-events:none;display:inline-block;width:18px;height:18px' }), ' Đã chọn'])
            ]));
            host.appendChild(area);
            renderOrder(showtimeInfo, selected, loadShowtimes);
        }

        function renderOrder(st, selected, onSuccess) {
            orderCard.classList.remove('hidden-card');
            var body = orderCard.querySelector('.pos-card-body');
            body.replaceChildren();

            var summary = el('div', { class: 'pos-order-summary' });
            summary.innerHTML = '<strong>' + escapeHtml(st.movieTitle || '-') + '</strong>'
                + '<div style="color:#6b7280;font-size:.84rem;margin-top:4px">'
                + escapeHtml(st.branchName || '') + ' - ' + escapeHtml(st.screenName || '') + ' - '
                + fmtTime(st.startTime) + '</div>';
            body.appendChild(summary);

            // ----- Bảng ghế -----
            var table = el('table', { class: 'ws-table' });
            table.innerHTML = '<thead><tr><th>Ghế</th><th>Loại</th><th>Đơn giá</th></tr></thead>';
            var tbody = el('tbody');
            var seatTotal = 0;
            if (!selected.length) {
                tbody.appendChild(el('tr', {}, [el('td', { colspan: '3', class: 'muted' }, ['Chưa chọn ghế nào.'])]));
            } else {
                selected.forEach(function (seat) {
                    var configuredPrice = st.seatPrices && st.seatPrices[seat.seatType];
                    var base = Number(st.standardPrice || 0);
                    var price = configuredPrice != null ? Number(configuredPrice)
                        : seat.seatType === 'VIP' ? Math.round(base * 1.3)
                        : seat.seatType === 'COUPLE' ? Math.round(base * 1.6)
                        : base;
                    seatTotal += price;
                    tbody.appendChild(el('tr', {}, [
                        el('td', {}, [String(seat.rowLabel) + seat.colNo]),
                        el('td', {}, [seat.seatType || 'STANDARD']),
                        el('td', {}, [fmtMoney(price)])
                    ]));
                });
            }
            table.appendChild(tbody);
            body.appendChild(table);

            var summaryDiv = el('div', { class: 'pos-order-totals' });
            summaryDiv.appendChild(el('div', { class: 'pos-order-total' }, [
                el('span', {}, ['Tổng tiền vé']),
                el('strong', {}, [fmtMoney(seatTotal)])
            ]));
            body.appendChild(summaryDiv);
            var sellBtn = el('button', { class: 'ws-btn primary', type: 'button' }, ['Xác nhận bán vé']);
            sellBtn.disabled = !selected.length;
            sellBtn.addEventListener('click', function () {
                if (!selected.length) return;
                sellBtn.disabled = true; sellBtn.textContent = 'Đang xử lý...';
                var payload = {
                    showtimeId: String(st.id),
                    seatIds: selected.map(function (s) { return s.seatId; }).join(',')
                };
                hub.api('/booking/counter-sale', { method: 'POST', body: payload }).then(function (ticket) {
                    hub.notify('Bán vé thành công: ' + (ticket.ticketCode || ('#' + ticket.id))
                        + ' - Tổng: ' + fmtMoney(ticket.totalAmount), 'ok');
                    if (typeof onSuccess === 'function') onSuccess();
                }).catch(function (e) {
                    hub.notify(e.message || 'Không thể bán vé', 'err');
                }).then(function () {
                    sellBtn.disabled = false; sellBtn.textContent = 'Xác nhận bán vé';
                });
            });
            body.appendChild(el('div', { style: 'text-align:right;margin-top:16px' }, [sellBtn]));
        }

        return Promise.resolve();
    }

    // ============================================================
    window.modulePosTicket = modulePosTicket;
})();
