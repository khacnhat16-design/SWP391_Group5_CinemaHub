package com.cinema.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Public booking entry point that explains authentication requirements to guests. */
public final class BookingEntryServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            String ctx = request.getContextPath();
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().printf("""
                <!doctype html><html lang="vi"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <link rel="stylesheet" href="%s/assets/css/cinema.css">
                <title>Đặt vé - CinemaHub</title></head><body>
                <main class="container section"><div class="auth-card">
                <span class="eyebrow">BOOKING</span><h1>Bạn không có quyền đặt vé</h1>
                <p class="muted">Vui lòng đăng nhập để tiếp tục đặt vé và chọn ghế.</p>
                <div class="actions"><a class="btn" href="%s/login">Đăng nhập</a>
                <a class="btn ghost" href="%s/register">Đăng ký</a>
                <a class="btn ghost" href="%s/">Quay lại</a></div>
                </div></main></body></html>
                """, ctx, ctx, ctx, ctx);
            return;
        }
        Object role = session.getAttribute("role");
        if (!"CUSTOMER".equals(role)) {
            response.sendRedirect(request.getContextPath() + "/console");
            return;
        }
        renderBookingPage(request, response);
    }

    private void renderBookingPage(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String ctx = request.getContextPath();
        String name = String.valueOf(request.getSession(false).getAttribute("username"));
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        String html = """
            <!doctype html><html lang="vi"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="ctx" content="__CTX__">
            <meta name="user-role" content="CUSTOMER">
            <link rel="stylesheet" href="__CTX__/assets/css/cinema.css?v=20261003-booking-showtime-cards-1">
            <script src="__CTX__/assets/js/app.js?v=20261003-notification-time-2"></script>
            <title>Đặt vé - CinemaHub</title></head><body>
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="__CTX__/">Cinema<span class="accent">Hub</span></a>
            <nav><span class="account-summary"><strong>__NAME__</strong><small>Khách hàng</small></span>
            <span class="nav-notif-wrap"><button id="notificationBell" class="nav-bell" type="button" aria-label="Thong bao" aria-expanded="false" title="Thong bao">
              <span aria-hidden="true">&#128276;</span><span id="notifCount" class="nav-badge" hidden>0</span>
            </button><div id="notificationPopover" class="notification-popover" hidden></div></span>
            <a href="__CTX__/console?module=profile">Hồ sơ</a>
            <a class="btn" href="__CTX__/logout">Đăng xuất</a></nav></div></header>
            <main class="container booking-page">
              <div class="page-heading"><div><span class="eyebrow">CINEMAHUB BOOKING</span>
              <h1>Chọn suất chiếu và ghế</h1>
              <p class="muted">Chọn lịch chiếu, giữ ghế trong 10 phút và thanh toán bằng ví CinemaHub.</p></div></div>
              <div id="message"></div>
              <div class="booking-layout">
                <section class="panel"><h2>Suất chiếu đang mở bán</h2>
                  <div id="showtimes" class="showtimes"><div class="loading-state">Đang tải suất chiếu...</div></div>
                  <div id="seatArea" hidden>
                    <h2>Chọn ghế</h2><div id="showInfo" class="muted"></div>
                    <div class="screen-label">MÀN HÌNH</div><div id="seats" class="seat-map"></div>
                    <div class="legend"><span><i class="seat-dot"></i>Ghế thường</span>
                    <span><i class="seat-dot vip"></i>Ghế VIP</span>
                    <span><i class="seat-dot couple"></i>Ghế đôi</span>
                    <span><i class="seat-dot selected"></i>Đang chọn</span>
                    <span><i class="seat-dot sold"></i>Đã bán</span></div>
                  </div>
                </section>
                <aside class="panel order-summary"><h2>Thanh toán</h2>
                  <div id="summary"><p class="muted">Chưa chọn ghế.</p></div>
                  <button id="holdButton" class="btn full" type="button" disabled>Giữ ghế</button>
                  <div id="paymentArea" hidden>
                    <p class="tiny">Ghế được giữ trong 10 phút. Chọn phương thức thanh toán để tiếp tục.</p>
                    <div class="payment-methods" role="radiogroup" aria-label="Phương thức thanh toán">
                      <label class="payment-method selected"><input type="radio" name="paymentMethod" value="WALLET" checked>
                        <span><strong>Ví CinemaHub</strong><small>Thanh toán ngay bằng số dư ví</small></span></label>
                      <label class="payment-method"><input type="radio" name="paymentMethod" value="VNPAY">
                        <span><strong>VNPay Sandbox</strong><small>Thanh toán trực tiếp qua cổng thử nghiệm VNPay</small></span></label>
                    </div>
                    <button id="payButton" class="btn full" type="button">Thanh toán bằng ví</button>
                  </div>
                  <div class="wallet-quick panel-light">
                    <div class="panel-title"><h3>Ví CinemaHub</h3><a href="__CTX__/console?module=wallet">Quản lý ví</a></div>
                    <div id="walletBalance" class="wallet-quick-balance">Đang tải số dư...</div>
                    <div class="wallet-topup-form">
                      <input id="topupAmount" type="number" min="10000" step="10000" placeholder="Số tiền nạp">
                      <button id="topupButton" class="btn secondary" type="button">Nạp tiền</button>
                    </div>
                    <div id="walletStatus" class="tiny" role="status"></div>
                  </div>
                  <div id="successTicket" class="ticket-success" hidden></div>
                </aside>
              </div>
            </main>
            <script>
            (() => {
              const ctx = '__CTX__';
              const bookingParams = new URLSearchParams(location.search);
              const selectedFromUrl = bookingParams.get('showtimeId');
              const selectedMovieId = bookingParams.get('movieId');
              const showtimesNode = document.getElementById('showtimes');
              const seatArea = document.getElementById('seatArea');
              const seatsNode = document.getElementById('seats');
              const showInfo = document.getElementById('showInfo');
              const summary = document.getElementById('summary');
              const message = document.getElementById('message');
              const holdButton = document.getElementById('holdButton');
              const paymentArea = document.getElementById('paymentArea');
              const payButton = document.getElementById('payButton');
              const paymentMethods = document.querySelectorAll('input[name="paymentMethod"]');
              const walletBalance = document.getElementById('walletBalance');
              const topupAmount = document.getElementById('topupAmount');
              const topupButton = document.getElementById('topupButton');
              const walletStatus = document.getElementById('walletStatus');
              // Back/Forward cache can restore stale seat buttons after a completed booking.
              // Bắt buộc reload khi page được restore từ bfcache — browser có thể
              // cache HTML cũ với seat status lỗi thời; reload để render đúng SOLD/HOLD.
              window.addEventListener('pageshow', event => {
                if (event.persisted) window.location.reload();
              });
              let csrf = '';
              let userId = null;
              let currentShow = null;
              let selectedSeats = [];
              let holdId = null;
              let quoteRevision = 0;

              function node(tag, attrs, text) {
                const item = document.createElement(tag);
                Object.entries(attrs || {}).forEach(([key, value]) => item.setAttribute(key, value));
                if (text !== undefined) item.textContent = text;
                return item;
              }
              function notify(text, kind) {
                message.replaceChildren();
                message.appendChild(node('div', { class: 'flash flash-' + (kind || 'err'), role: 'alert' }, text));
              }
              function selectedPaymentMethod() {
                const selected = document.querySelector('input[name="paymentMethod"]:checked');
                return selected ? selected.value : 'WALLET';
              }
              function updatePaymentButton() {
                payButton.textContent = selectedPaymentMethod() === 'VNPAY'
                  ? 'Thanh toán qua VNPay Sandbox' : 'Thanh toán bằng ví';
                document.querySelectorAll('.payment-method').forEach(item => {
                  const radio = item.querySelector('input');
                  item.classList.toggle('selected', radio && radio.checked);
                });
              }
              async function refreshWallet() {
                try {
                  const data = await api('/wallet');
                  walletBalance.textContent = 'Số dư hiện tại: '
                    + money(data && data.wallet ? data.wallet.balance : 0);
                } catch (error) {
                  walletBalance.textContent = 'Không tải được số dư ví.';
                }
              }
              function money(value) {
                return Number(value || 0).toLocaleString('vi-VN') + 'đ';
              }
              function holdOwnerId(seat) {
                return seat.holdUserId ?? seat.hold_user_id ?? seat.userId ?? seat.user_id;
              }
              function seatStatus(seat) {
                const status = String(seat.status || '').toUpperCase();
                // ACTIVE belongs to seat_hold; when it is attached to a seat,
                // it represents an active HOLD for compatibility with old API payloads.
                return status === 'ACTIVE' && (seat.holdId || seat.hold_id) ? 'HOLD' : status;
              }
              function belongsToCurrentUser(seat) {
                if (seat.ownedByCurrentUser === true) return true;
                if (seatStatus(seat) !== 'HOLD') return false;
                const ownerId = holdOwnerId(seat);
                if (ownerId == null || ownerId === '') return false;
                if (userId == null || userId === '') return false;
                return String(ownerId) === String(userId);
              }
              async function api(path, options) {
                const request = Object.assign({ credentials: 'same-origin' }, options || {});
                request.headers = Object.assign({}, request.headers || {});
                if (request.method && request.method !== 'GET') {
                  request.headers['X-CSRF-Token'] = csrf;
                  request.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
                }
                if (request.body && typeof request.body === 'object') {
                  request.body = new URLSearchParams(request.body).toString();
                }
                const response = await fetch(ctx + path, request);
                const body = await response.text();
                let data = null;
                if (body) {
                  try {
                    data = JSON.parse(body);
                  } catch (parseError) {
                    console.error('[CinemaHub booking] Invalid JSON response', {
                      path: path, status: response.status, body: body
                    });
                    data = { message: response.ok
                      ? 'Máy chủ trả về dữ liệu không hợp lệ.'
                      : 'Máy chủ đang gặp sự cố. Vui lòng thử lại.' };
                  }
                }
                if (path.startsWith('/showtime/')) {
                  console.groupCollapsed('[CinemaHub booking] GET ' + path);
                  console.log('HTTP:', response.status);
                  console.log('Payload:', data);
                  console.groupEnd();
                }
                if (!response.ok) {
                  throw new Error(data && data.message
                    ? data.message : 'Không thể xử lý yêu cầu (HTTP ' + response.status + ').');
                }
                return data;
              }
              function renderSummary() {
                const revision = ++quoteRevision;
                if (!currentShow || !selectedSeats.length) {
                  summary.innerHTML = '<p class="muted">Chưa chọn ghế.</p>';
                  holdButton.disabled = true;
                  holdButton.textContent = 'Giữ ghế';
                  paymentArea.hidden = true;
                  return;
                }
                const seats = selectedSeats.slice();
                const seatIds = seats.map(seat => seat.seatId);
                summary.replaceChildren(
                  node('div', { class: 'summary-line' }, currentShow.movieTitle || 'Suất chiếu'),
                  node('div', { class: 'summary-line' }, 'Đang chọn: ' + seats.length + ' ghế'),
                  node('div', { class: 'summary-line' }, 'Ghế: ' + seats.map(seat => seat.rowLabel + seat.colNo).join(', ')),
                  node('div', { class: 'summary-line total' }, 'Đang tính giá…')
                );
                holdButton.disabled = true;
                holdButton.textContent = holdId ? 'Hủy giữ ghế' : 'Giữ ghế';
                paymentArea.hidden = !holdId;
                api('/booking/quote', {
                  method: 'POST',
                  body: { showtimeId: currentShow.id, seatIds: seatIds.join(',') }
                }).then(order => {
                  if (revision !== quoteRevision) return;
                  const lines = [
                    node('div', { class: 'summary-line' }, currentShow.movieTitle || 'Suất chiếu'),
                    node('div', { class: 'summary-line' }, 'Đang chọn: ' + seats.length + ' ghế'),
                    node('div', { class: 'summary-line' }, 'Ghế: ' + seats.map(seat => seat.rowLabel + seat.colNo).join(', ')),
                    node('div', { class: 'summary-line' }, 'Giá vé trước ưu đãi: ' + money(order.baseTotal))
                  ];
                  if (order.tierDiscount > 0) {
                    lines.push(node('div', { class: 'summary-line' },
                      'Ưu đãi hạng thành viên: -' + money(order.tierDiscount)));
                  }
                  if (order.voucherDiscount > 0) {
                    lines.push(node('div', { class: 'summary-line' },
                      'Ưu đãi mã voucher: -' + money(order.voucherDiscount)));
                  }
                  lines.push(node('div', { class: 'summary-line total' },
                    'Cần thanh toán: ' + money(order.payable)));
                  summary.replaceChildren(...lines);
                  holdButton.disabled = false;
                }).catch(error => {
                  if (revision !== quoteRevision) return;
                  summary.replaceChildren(
                    node('div', { class: 'summary-line' }, currentShow.movieTitle || 'Suất chiếu'),
                    node('div', { class: 'summary-line error' }, 'Không thể tính giá vé.')
                  );
                  holdButton.disabled = true;
                  notify(error.message);
                });
              }
              function renderSeats(seats, rowCount, colCount) {
                seatsNode.replaceChildren();
                const seatsByPosition = new Map(
                  seats.map(seat => [String(seat.rowLabel) + ':' + seat.colNo, seat]));
                const seatRows = Number(rowCount) || Math.max(0, ...seats.map(seat =>
                  String(seat.rowLabel || '').charCodeAt(0) - 64));
                const seatColumns = Number(colCount) || Math.max(0, ...seats.map(seat =>
                  Number(seat.colNo) || 0));
                for (let rowIndex = 0; rowIndex < seatRows; rowIndex++) {
                  const rowLabel = String.fromCharCode(65 + rowIndex);
                  const row = node('div', { class: 'seat-row' });
                  row.appendChild(node('small', {}, rowLabel));
                  for (let colNo = 1; colNo <= seatColumns; colNo++) {
                    const seat = seatsByPosition.get(rowLabel + ':' + colNo);
                    if (!seat) {
                      row.appendChild(node('span', {
                        class: 'seat-empty',
                        'aria-hidden': 'true'
                      }));
                      continue;
                    }
                    const status = seatStatus(seat);
                    const seatType = String(seat.seatType || 'STANDARD').toUpperCase();
                    const typeClass = seatType === 'VIP' ? 'vip' : seatType === 'COUPLE' ? 'couple' : '';
                    const statusClass = status === 'SOLD' ? 'sold' : status === 'HOLD' ? 'hold' : '';
                    const button = node('button', {
                      class: ['seat', typeClass, statusClass].filter(Boolean).join(' '),
                      type: 'button'
                    }, String(seat.colNo));
                    button.title = rowLabel + seat.colNo + ' - ' + seat.seatType;
                    const typeLabel = seatType === 'VIP' ? 'VIP'
                      : seatType === 'COUPLE' ? 'đôi' : 'thường';
                    const statusLabel = status === 'SOLD' ? 'đã bán'
                      : status === 'HOLD' ? 'đang được giữ' : 'còn trống';
                    button.setAttribute('aria-label',
                      'Ghế ' + rowLabel + seat.colNo + ', loại ' + typeLabel + ', ' + statusLabel);
                    const isOwnHold = belongsToCurrentUser(seat);
                    const isSelected = isOwnHold || selectedSeats.some(item => item.seatId === seat.seatId);
                    if (isSelected) button.classList.add('selected');
                    const isSelectable = isOwnHold || (status === 'AVAILABLE' && !holdId);
                    if (!isSelectable) button.disabled = true;
                    button.addEventListener('click', async () => {
                      if (!isSelectable) return;
                      if (isOwnHold) {
                        await releaseCurrentHold().catch(error => notify(error.message));
                        return;
                      }
                      const index = selectedSeats.findIndex(item => item.seatId === seat.seatId);
                      if (index >= 0) {
                        selectedSeats.splice(index, 1);
                        button.classList.remove('selected');
                      } else {
                        selectedSeats.push(seat);
                        button.classList.add('selected');
                      }
                      renderSummary();
                    });
                    row.appendChild(button);
                  }
                  seatsNode.appendChild(row);
                }
              }
              async function selectShow(show) {
                currentShow = show;
                selectedSeats = [];
                holdId = null;
                paymentArea.hidden = true;
                const detail = await api('/showtime/' + show.id);
                const data = detail.showtime ? detail : { showtime: detail, seats: [] };
                console.groupCollapsed('[CinemaHub booking] selectShow ' + show.id);
                console.log('Session userId:', userId);
                console.log('Showtime detail:', data.showtime);
                console.table((data.seats || []).map(seat => ({
                  seatId: seat.seatId,
                  position: String(seat.rowLabel || '') + String(seat.colNo || ''),
                  status: seat.status,
                  holdId: seat.holdId ?? seat.hold_id,
                  holdUserId: seat.holdUserId ?? seat.hold_user_id
                })));
                console.groupEnd();
                // Khôi phục hold: chỉ giữ lại các ghế HOLD thuộc về user hiện tại.
                // Hold của khách khác vẫn hiển thị disabled — tránh tình trạng user
                // A refresh trang sau khi hold hết hạn thì thấy ghế user B giữ.
                const ownHeldSeats = (data.seats || []).filter(seat =>
                  seatStatus(seat) === 'HOLD' && belongsToCurrentUser(seat));
                selectedSeats = ownHeldSeats;
                holdId = ownHeldSeats.length
                  ? (ownHeldSeats[0].holdId ?? ownHeldSeats[0].hold_id) : null;
                console.log('[CinemaHub booking] selectedSeats:', selectedSeats,
                  'count=', selectedSeats.length, 'holdId=', holdId);
                showInfo.textContent = (show.branchName || '') + ' · '
                  + (show.screenName || 'Phòng chiếu') + ' · '
                  + String(show.startTime || '').replace('T', ' ');
                renderSeats(data.seats || [], data.screenRowCount, data.screenColCount);
                seatArea.hidden = false;
                renderSummary();
                document.querySelectorAll('.showtime').forEach(item => {
                  const isActive = item.dataset.id === String(show.id);
                  item.classList.toggle('active', isActive);
                  item.setAttribute('aria-pressed', String(isActive));
                });
              }
              async function releaseCurrentHold() {
                if (!holdId || !selectedSeats.length || !currentShow) return;
                holdButton.disabled = true;
                await api('/booking/release', {
                  method: 'POST',
                  body: {
                    showtimeId: currentShow.id,
                    seatIds: selectedSeats.map(seat => seat.seatId).join(',')
                  }
                });
                holdId = null;
                selectedSeats = [];
                paymentArea.hidden = true;
                notify('Đã hủy giữ ghế.', 'ok');
                await selectShow(currentShow);
              }
              async function load() {
                const session = await fetch(ctx + '/api/session', { credentials: 'same-origin' });
                const sessionData = await session.json();
                csrf = sessionData.csrfToken || '';
                userId = sessionData.userId;
                console.log('[CinemaHub booking] session:', sessionData);
                const query = selectedMovieId
                  ? '?movieId=' + encodeURIComponent(selectedMovieId) : '';
                const list = await api('/showtime/discovery' + query);
                showtimesNode.replaceChildren();
                if (!list.length) {
                  showtimesNode.appendChild(node('p', { class: 'muted' },
                    selectedMovieId
                      ? 'Phim này hiện chưa có suất chiếu mở bán.'
                      : 'Hiện chưa có suất chiếu mở bán.'));
                  return;
                }
                list.forEach(show => {
                  const item = node('button', { class: 'showtime', type: 'button' });
                  item.dataset.id = show.id;
                  item.setAttribute('aria-pressed', 'false');
                  item.appendChild(node('b', {}, show.movieTitle || 'Phim'));
                  item.appendChild(node('small', { class: 'showtime-location' },
                    (show.branchName || 'Chi nhánh') + ' · ' + (show.screenName || 'Phòng chiếu')));
                  item.appendChild(node('span', { class: 'showtime-time' },
                    String(show.startTime || '').replace('T', ' ')));
                  item.addEventListener('click', () => selectShow(show).catch(error => notify(error.message)));
                  showtimesNode.appendChild(item);
                });
                const initial = list.find(show => String(show.id) === selectedFromUrl);
                if (initial) await selectShow(initial);
              }
              holdButton.addEventListener('click', async () => {
                try {
                  if (holdId) {
                    await releaseCurrentHold();
                    return;
                  }
                  const seatIds = selectedSeats.map(seat => seat.seatId);
                  console.log('[CinemaHub booking] HOLD click:', {
                    showtimeId: currentShow && currentShow.id,
                    seatIds: seatIds,
                    count: seatIds.length,
                    statuses: selectedSeats.map(seat => seatStatus(seat))
                  });
                  if (!currentShow || !seatIds.length) {
                    throw new Error('Vui lòng chọn ít nhất một ghế.');
                  }
                  holdButton.disabled = true;
                  const result = await api('/booking/hold', { method: 'POST', body: {
                    showtimeId: currentShow.id, seatIds: seatIds.join(',')
                  }});
                  if (!result.success) throw new Error(result.message || 'Không giữ được ghế.');
                  holdId = result.holdId;
                  paymentArea.hidden = false;
                  notify('Đã giữ ghế. Bạn có 10 phút để thanh toán.', 'ok');
                  await selectShow(currentShow);
                } catch (error) {
                  holdButton.disabled = false;
                  notify(error.message);
                }
              });
              payButton.addEventListener('click', async () => {
                try {
                  payButton.disabled = true;
                  const result = await api('/booking/confirm', {
                    method: 'POST',
                    body: { holdId, paymentMethod: selectedPaymentMethod() }
                  });
                  if (result.redirectUrl) {
                    window.location.href = result.redirectUrl;
                    return;
                  }
                  const ticket = result.ticket || result;
                  notify('Thanh toán thành công. Mã vé: ' + (ticket.ticketCode || ticket.id), 'ok');
                  await refreshWallet();
                  if (window.CinemaHub && window.CinemaHub.refreshNotifications) {
                    await window.CinemaHub.refreshNotifications();
                    setTimeout(function () { window.CinemaHub.refreshNotifications(); }, 300);
                  }
                  payButton.textContent = 'Đã thanh toán';
                  holdButton.disabled = true;
                  // Payment has consumed the hold. Clear the client selection first,
                  // then reload the authoritative seat state so the paid seats render
                  // as SOLD instead of remaining red/selected.
                  holdId = null;
                  selectedSeats = [];
                  paymentArea.hidden = true;
                  const code = ticket.ticketCode || String(ticket.id);
                  const success = document.getElementById('successTicket');
                  success.hidden = false;
                  success.replaceChildren(
                    node('h3', {}, 'Vé đã sẵn sàng'),
                    node('p', { class: 'muted' }, 'Đưa barcode này cho nhân viên khi vào rạp.'),
                    node('canvas', { class: 'ticket-barcode', width: '220', height: '76' }),
                    node('strong', {}, code)
                  );
                  if (window.CinemaHub && window.CinemaHub.renderBarcode) {
                    window.CinemaHub.renderBarcode(success.querySelector('canvas'), code);
                  }
                  renderSummary();
                  await selectShow(currentShow);
                } catch (error) {
                  payButton.disabled = false;
                  notify(error.message);
                }
              });
              paymentMethods.forEach(radio => radio.addEventListener('change', updatePaymentButton));
              topupButton.addEventListener('click', async () => {
                try {
                  const amount = Number(topupAmount.value);
                  if (!amount || amount < 10000 || amount % 10000 !== 0) {
                    throw new Error('Số tiền nạp phải là bội số của 10.000đ.');
                  }
                  topupButton.disabled = true;
                  walletStatus.textContent = 'Đang tạo giao dịch nạp tiền...';
                  const result = await api('/wallet/top-up', {
                    method: 'POST', body: { amount: amount }
                  });
                  window.location.href = result.redirectUrl;
                } catch (error) {
                  walletStatus.textContent = error.message;
                  topupButton.disabled = false;
                }
              });
              updatePaymentButton();
              load().then(refreshWallet).catch(error => {
                showtimesNode.replaceChildren(node('p', { class: 'muted' }, 'Không thể tải suất chiếu: ' + error.message));
              });

              // VNPay / callback: nếu URL có ?payment=success&ticketCode=... thì hiện popup barcode
              // ngay khi trang load xong, sau đó dọn query string để F5 không hiện lại.
              const __urlParams = new URLSearchParams(location.search);
              const __paymentFlag = __urlParams.get('payment');
              if (__paymentFlag) {
                const __code = __urlParams.get('ticketCode') || '';
                const __reason = __urlParams.get('reason') || '';
                if (__paymentFlag === 'success' && __code) {
                  if (window.CinemaHub && window.CinemaHub.refreshNotifications) {
                    window.CinemaHub.refreshNotifications();
                  }
                  const modal = document.createElement('div');
                  modal.className = 'modal';
                  modal.innerHTML = `
                    <div class="modal-backdrop"></div>
                    <div class="modal-panel" role="dialog" aria-modal="true">
                      <div class="modal-head">
                        <h2>Thanh toán thành công</h2>
                        <button type="button" class="btn secondary modal-x" aria-label="Đóng">×</button>
                      </div>
                      <div class="modal-body ticket-result-modal">
                        <div class="payment-result-icon ok">OK</div>
                        <h3>Vé của bạn đã sẵn sàng</h3>
                        <p class="muted">Đưa mã barcode này cho nhân viên tại rạp để vào suất chiếu.</p>
                        <strong class="ticket-result-code"></strong>
                        <canvas class="ticket-barcode" width="240" height="82"></canvas>
                      </div>
                      <div class="modal-foot">
                        <a class="btn secondary" href="${ctx}/console/booking/list">Xem vé của tôi</a>
                        <button type="button" class="btn" id="__closeAndReload">Đóng</button>
                      </div>
                    </div>`;
                  document.body.appendChild(modal);
                  modal.querySelector('.ticket-result-code').textContent = __code;
                  if (window.CinemaHub && window.CinemaHub.renderBarcode) {
                    window.CinemaHub.renderBarcode(modal.querySelector('canvas'), __code);
                  }
                  const __dismiss = function () {
                    modal.remove();
                    const __clean = new URL(location.href);
                    __clean.searchParams.delete('payment');
                    __clean.searchParams.delete('ticketCode');
                    __clean.searchParams.delete('reason');
                    window.history.replaceState(null, '', __clean.pathname + __clean.search);
                    // Sau IPN, reload để frontend đọc lại DB mới nhất — tránh trường hợp
                    // trang cache hiển thị status "PENDING" dù IPN đã set "CONFIRMED".
                    window.location.reload();
                  };
                  modal.querySelector('.modal-backdrop').addEventListener('click', __dismiss);
                  modal.querySelector('.modal-x').addEventListener('click', __dismiss);
                  modal.querySelector('#__closeAndReload').addEventListener('click', __dismiss);
                } else {
                  // payment=failed: thông báo thân thiện, dọn query
                  const clean = new URL(location.href);
                  clean.searchParams.delete('payment');
                  clean.searchParams.delete('ticketCode');
                  clean.searchParams.delete('reason');
                  window.history.replaceState(null, '', clean.pathname + clean.search);
                  notify(__reason ? ('Thanh toán không thành công: ' + __reason) : 'Thanh toán không thành công.');
                }
              }
            })();
            </script></body></html>
            """;
        html = html.replace("__CTX__", escape(ctx)).replace("__NAME__", escape(name));
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(html);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
