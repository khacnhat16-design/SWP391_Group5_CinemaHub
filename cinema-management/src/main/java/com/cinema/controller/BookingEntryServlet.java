package com.cinema.controller;

import com.cinema.model.payment.Payment;
import com.cinema.model.showtime.Showtime;

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
            <link rel="stylesheet" href="__CTX__/assets/css/cinema.css?v=20261031-booking-checkout-3">
            <script src="__CTX__/assets/js/app.js?v=20261009-customer-header-1"></script>
            <title>Đặt vé - CinemaHub</title></head><body>
            <header class="nav-wrapper"><div class="nav container">
            <a class="logo" href="__CTX__/">Cinema<span class="accent">Hub</span></a>
            <nav><span class="account-summary"><strong>__NAME__</strong><small>Khách hàng</small></span>
            <span class="customer-nav-summary" aria-label="Điểm tài khoản">
              <span><small>Điểm</small><strong id="customerPoints">—</strong></span>
            </span>
            <span class="nav-notif-wrap"><button id="notificationBell" class="nav-bell" type="button" aria-label="Thong bao" aria-expanded="false" title="Thong bao">
              <span aria-hidden="true">&#128276;</span><span id="notifCount" class="nav-badge" hidden>0</span>
            </button><div id="notificationPopover" class="notification-popover" hidden></div></span>
            <a href="__CTX__/console?module=profile">Hồ sơ</a>
            <a class="btn" href="__CTX__/logout">Đăng xuất</a></nav></div></header>
            <main class="container booking-page">
              <div class="page-heading"><div><span class="eyebrow">CINEMAHUB BOOKING</span>
              <h1>Chọn suất chiếu và ghế</h1>
              <p class="muted">Chọn suất chiếu, giữ ghế trong 10 phút và thanh toán qua VNPay.</p></div></div>
              <div id="message"></div>
              <div class="booking-layout">
                <section class="panel"><h2>Suất chiếu đang mở bán</h2>
                  <div id="showtimes" class="showtimes"><div class="loading-state">Đang tải suất chiếu...</div></div>
                  <div id="seatArea" hidden>
                    <h2>Chọn ghế</h2><div id="showInfo" class="muted"></div>
                    <div class="screen-label">MÀN HÌNH</div>
                    <div class="seat-map-scroll"><div id="seats" class="seat-map"></div></div>
                    <div class="legend"><span><i class="seat-dot"></i>Ghế thường</span>
                    <span><i class="seat-dot vip"></i>Ghế VIP</span>
                    <span><i class="seat-dot couple"></i>Ghế đôi (chiếm 2 chỗ)</span>
                    <span><i class="seat-dot selected"></i>Đang chọn</span>
                    <span><i class="seat-dot sold"></i>Đã bán</span>
                    <span><i class="seat-dot maintenance"></i>Bảo trì</span></div>
                  </div>
                </section>
                <aside class="panel order-summary">
                  <div class="checkout-heading">
                    <span class="eyebrow">ĐẶT VÉ</span>
                    <h2>Chi tiết đơn hàng</h2>
                  </div>
                  <div id="summary"><p class="muted">Chưa chọn ghế.</p></div>
                  <div id="paymentArea" hidden>
                    <p class="checkout-hold-note">Ghế được giữ trong 10 phút. Hoàn tất thanh toán để xác nhận vé.</p>
                    <label class="checkout-points" for="usePointsInput">Số điểm muốn sử dụng</label>
                    <input id="usePointsInput" class="checkout-points-input" type="number" min="0" step="1" value="0" inputmode="numeric" />
                    <div id="pointsMsg" class="checkout-points-note"></div>
                    <div class="payment-method selected">
                      <span><strong>Thanh toán qua VNPay</strong><small>An toàn và nhanh chóng</small></span>
                    </div>
                    <button id="payButton" class="btn full checkout-pay-button" type="button">Thanh toán bằng VNPay</button>
                  </div>
                  <section class="voucher-box" aria-labelledby="voucherHeading">
                    <div class="voucher-heading">
                      <div>
                        <h3 id="voucherHeading">Ví voucher</h3>
                        <p>Các mã ưu đãi hiện có, chọn một mã để áp dụng cho đơn vé.</p>
                      </div>
                      <span id="availableVoucherCount" class="voucher-count" aria-live="polite"></span>
                    </div>
                    <div id="availableVouchers" class="voucher-list" aria-live="polite">Đang tải voucher...</div>
                    <div class="voucher-entry">
                      <label for="voucherInput">Hoặc nhập mã voucher</label>
                      <div class="voucher-entry-controls">
                        <input id="voucherInput" type="text" placeholder="Ví dụ: WELCOME10"
                          autocomplete="off" />
                        <button id="voucherBtn" class="btn voucher-apply-button" type="button">Áp dụng</button>
                        <button id="voucherClearBtn" class="btn ghost small voucher-clear-button"
                          type="button" hidden>Gỡ mã</button>
                      </div>
                    </div>
                    <div id="voucherMsg" class="voucher-message" role="status" aria-live="polite"></div>
                  </section>
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
              const paymentArea = document.getElementById('paymentArea');
              const payButton = document.getElementById('payButton');
              const voucherInput = document.getElementById('voucherInput');
              const voucherBtn = document.getElementById('voucherBtn');
              const voucherClearBtn = document.getElementById('voucherClearBtn');
              const voucherMsg = document.getElementById('voucherMsg');
              const availableVouchers = document.getElementById('availableVouchers');
              const availableVoucherCount = document.getElementById('availableVoucherCount');
              const usePointsInput = document.getElementById('usePointsInput');
              const pointsMsg = document.getElementById('pointsMsg');
              let voucherCode = '';
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
              let holdRequestInFlight = false;
              let quoteRevision = 0;
              let quoteContext = '';

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
              function money(value) {
                return Number(value || 0).toLocaleString('vi-VN') + 'đ';
              }
              function formatVoucherDate(value) {
                const date = new Date(value);
                return Number.isNaN(date.getTime())
                  ? String(value || '—')
                  : date.toLocaleDateString('vi-VN');
              }
              function updateVoucherCards() {
                const inputCode = (voucherInput.value || '').trim().toUpperCase();
                availableVouchers.querySelectorAll('.voucher-offer').forEach(offer => {
                  const isApplied = offer.dataset.code === voucherCode;
                  const isPending = !isApplied && offer.dataset.code === inputCode;
                  offer.classList.toggle('is-applied', isApplied);
                  offer.classList.toggle('is-pending', isPending);
                  const chooseButton = offer.querySelector('.voucher-select-button');
                  chooseButton.textContent = isApplied ? 'Đang áp dụng'
                    : isPending ? 'Đã chọn' : 'Chọn mã';
                  chooseButton.setAttribute('aria-pressed', String(isApplied || isPending));
                });
                voucherClearBtn.hidden = !voucherCode;
              }
              async function loadAvailableVouchers() {
                const response = await fetch(ctx + '/voucher/available', { credentials: 'same-origin' });
                const vouchers = await response.json();
                if (!response.ok) {
                  throw new Error((vouchers.error && vouchers.error.message) || 'Không thể tải voucher.');
                }
                if (!Array.isArray(vouchers)) {
                  throw new Error('Danh sách voucher trả về không hợp lệ.');
                }
                availableVouchers.replaceChildren();
                availableVoucherCount.textContent = vouchers.length + ' mã';
                if (vouchers.length === 0) {
                  availableVoucherCount.textContent = '';
                  availableVouchers.appendChild(node('p', { class: 'voucher-empty' },
                    'Hiện chưa có voucher khả dụng. Vui lòng quay lại sau nhé.'));
                  return;
                }
                vouchers.forEach(voucher => {
                  const offer = node('article', { class: 'voucher-offer' });
                  offer.dataset.code = String(voucher.code || '').toUpperCase();
                  const discount = String(voucher.discountType).toUpperCase() === 'PERCENT'
                    ? 'Giảm ' + voucher.discountValue + '%'
                    : 'Giảm ' + money(voucher.discountValue);
                  const details = node('div', { class: 'voucher-details' });
                  details.append(
                    node('span', {}, 'Đơn tối thiểu ' + money(voucher.minOrderAmount)),
                    node('span', {}, 'Hạn dùng đến ' + formatVoucherDate(voucher.validTo))
                  );
                  const info = node('div', { class: 'voucher-offer-info' });
                  info.append(
                    node('span', { class: 'voucher-code' }, voucher.code),
                    node('strong', { class: 'voucher-discount' }, discount),
                    details
                  );
                  const chooseButton = node('button', {
                    class: 'btn voucher-select-button',
                    type: 'button',
                    'aria-pressed': 'false'
                  }, 'Chọn mã');
                  offer.append(info, chooseButton);
                  chooseButton.addEventListener('click', () => {
                    voucherInput.value = voucher.code;
                    updateVoucherCards();
                    if (currentShow && selectedSeats.length) {
                      voucherBtn.click();
                    } else {
                      voucherMsg.textContent = 'Đã chọn mã. Chọn ghế rồi nhấn “Áp dụng”.';
                    }
                  });
                  availableVouchers.appendChild(offer);
                });
                updateVoucherCards();
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
                  paymentArea.hidden = true;
                  return;
                }
                const seats = selectedSeats.slice();
                const seatIds = seats.map(seat => seat.seatId);
                const nextQuoteContext = JSON.stringify([
                  currentShow.id, seatIds, voucherCode
                ]);
                const contextChanged = nextQuoteContext !== quoteContext;
                if (contextChanged) {
                  quoteContext = nextQuoteContext;
                  delete usePointsInput.dataset.maxRedeemablePoints;
                }
                // Ghế đôi (COUPLE) chiếm 2 slot/người ngồi → tổng slot = số ghế thường + 2 × số ghế đôi.
                const slotCount = seats.reduce((sum, seat) =>
                  sum + (String(seat.seatType || '').toUpperCase() === 'COUPLE' ? 2 : 1), 0);
                const slotLabel = slotCount !== seats.length
                  ? 'Đang chọn: ' + seats.length + ' ghế (' + slotCount + ' chỗ, ghế đôi tính 2 chỗ)'
                  : 'Đang chọn: ' + seats.length + ' ghế (' + slotCount + ' chỗ)';
                const seatLabel = seat => seat.rowLabel + seat.colNo
                  + (String(seat.seatType || '').toUpperCase() === 'COUPLE'
                    ? '-' + (Number(seat.colNo) + 1) : '');
                const selectedPoints = Number(usePointsInput.value || 0);
                if (!Number.isSafeInteger(selectedPoints) || selectedPoints < 0) {
                  pointsMsg.textContent = 'Số điểm phải là số nguyên không âm.';
                  pointsMsg.classList.add('is-error');
                  usePointsInput.setAttribute('aria-invalid', 'true');
                  usePointsInput.classList.add('is-invalid');
                  payButton.disabled = true;
                  summary.replaceChildren(
                    node('div', { class: 'summary-line error' }, 'Vui lòng nhập số điểm hợp lệ.')
                  );
                  return;
                }
                const knownMax = Number(usePointsInput.dataset.maxRedeemablePoints);
                const exceedsKnownMax = !contextChanged
                  && Number.isFinite(knownMax) && selectedPoints > knownMax;
                let pointsUsed = contextChanged || exceedsKnownMax ? 0 : selectedPoints;
                pointsMsg.classList.toggle('is-error', exceedsKnownMax);
                usePointsInput.classList.toggle('is-invalid', exceedsKnownMax);
                usePointsInput.setAttribute('aria-invalid', String(exceedsKnownMax));
                if (exceedsKnownMax) {
                  pointsMsg.textContent = 'Số điểm vượt quá số dư hiện có: '
                    + knownMax.toLocaleString('vi-VN')
                    + ' điểm.';
                } else if (Number.isFinite(knownMax)) {
                  pointsMsg.textContent = knownMax > 0
                    ? 'Có thể dùng tối đa ' + knownMax.toLocaleString('vi-VN')
                      + ' điểm theo số dư hiện có.'
                    : 'Số dư hiện tại không có điểm để sử dụng.';
                }
                payButton.disabled = true;
                summary.replaceChildren(
                  node('div', { class: 'summary-line' }, currentShow.movieTitle || 'Suất chiếu'),
                  node('div', { class: 'summary-line' }, slotLabel),
                  node('div', { class: 'summary-line' }, 'Ghế: ' + seats.map(seatLabel).join(', ')),
                  node('div', { class: 'summary-line total' }, 'Đang tính giá…')
                );
                paymentArea.hidden = !holdId;
                api('/booking/quote', {
                  method: 'POST',
                  body: {
                    showtimeId: currentShow.id,
                    seatIds: seatIds.join(','),
                    voucherCode: voucherCode,
                    pointsUsed: pointsUsed
                  }
                }).then(order => {
                  if (revision !== quoteRevision) return;
                  const maxRedeemablePoints = Number(order.maxRedeemablePoints || 0);
                  usePointsInput.dataset.maxRedeemablePoints = String(maxRedeemablePoints);
                  const exceedsMax = selectedPoints > maxRedeemablePoints;
                  usePointsInput.classList.toggle('is-invalid', exceedsMax);
                  usePointsInput.setAttribute('aria-invalid', String(exceedsMax));
                  pointsMsg.classList.toggle('is-error', exceedsMax);
                  if (exceedsMax) {
                    pointsMsg.textContent = 'Số điểm vượt quá số dư hiện có: '
                      + maxRedeemablePoints.toLocaleString('vi-VN')
                      + ' điểm.';
                  } else {
                    pointsMsg.textContent = maxRedeemablePoints > 0
                      ? 'Có thể dùng tối đa ' + maxRedeemablePoints.toLocaleString('vi-VN')
                        + ' điểm theo số dư hiện có.'
                      : 'Số dư hiện tại không có điểm để sử dụng.';
                  }
                  if (contextChanged && selectedPoints > 0 && !exceedsMax) {
                    renderSummary();
                    return;
                  }
                  payButton.disabled = exceedsMax;
                  const lines = [
                    node('div', { class: 'summary-line' }, currentShow.movieTitle || 'Suất chiếu'),
                    node('div', { class: 'summary-line' }, slotLabel),
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
                  if (order.pointsDiscount > 0) {
                    lines.push(node('div', { class: 'summary-line' },
                      'Giảm bằng điểm (' + order.pointsUsed + ' điểm): -' + money(order.pointsDiscount)));
                  }
                  lines.push(node('div', { class: 'summary-line total' },
                    'Cần thanh toán: ' + money(Math.max(0, order.payableAfterPoints))));
                  summary.replaceChildren(...lines);
                }).catch(error => {
                  if (revision !== quoteRevision) return;
                  const currentPoints = Number(usePointsInput.value || 0);
                  const latestMax = Number(usePointsInput.dataset.maxRedeemablePoints);
                  const invalidPoints = !Number.isSafeInteger(currentPoints)
                    || currentPoints < 0
                    || (Number.isFinite(latestMax) && currentPoints > latestMax);
                  payButton.disabled = invalidPoints;
                  if (invalidPoints && Number.isFinite(latestMax)
                      && currentPoints > latestMax) {
                    pointsMsg.textContent = 'Số điểm vượt quá số dư hiện có: '
                      + latestMax.toLocaleString('vi-VN')
                      + ' điểm.';
                    pointsMsg.classList.add('is-error');
                    usePointsInput.classList.add('is-invalid');
                    usePointsInput.setAttribute('aria-invalid', 'true');
                  }
                  summary.replaceChildren(
                    node('div', { class: 'summary-line' }, currentShow.movieTitle || 'Suất chiếu'),
                    node('div', { class: 'summary-line error' },
                      error.message || 'Không thể tính giá vé.')
                  );
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
                      const maintenanceSeat = node('button', {
                        class: 'seat maintenance',
                        type: 'button',
                        disabled: true,
                        title: 'Ghế ' + rowLabel + colNo + ' - đang bảo trì'
                      }, String(colNo));
                      maintenanceSeat.setAttribute('aria-label',
                        'Ghế ' + rowLabel + colNo + ', đang bảo trì');
                      row.appendChild(maintenanceSeat);
                      continue;
                    }
                    const status = seatStatus(seat);
                    const seatType = String(seat.seatType || 'STANDARD').toUpperCase();
                    const typeClass = seatType === 'VIP' ? 'vip' : seatType === 'COUPLE' ? 'couple' : '';
                    const statusClass = status === 'SOLD' ? 'sold'
                      : status === 'HOLD' ? 'hold'
                      : status === 'MAINTENANCE' ? 'maintenance' : '';
                    const button = node('button', {
                      class: ['seat', typeClass, statusClass].filter(Boolean).join(' '),
                      type: 'button'
                    }, seatType === 'COUPLE'
                      ? seat.colNo + '-' + (Number(seat.colNo) + 1) : String(seat.colNo));
                    const seatCode = rowLabel + seat.colNo
                      + (seatType === 'COUPLE' ? '-' + (Number(seat.colNo) + 1) : '');
                    button.title = seatCode + ' - ' + seat.seatType
                      + (seatType === 'COUPLE' ? ' (chiếm 2 chỗ)' : '');
                    const typeLabel = seatType === 'VIP' ? 'VIP'
                      : seatType === 'COUPLE' ? 'đôi (chiếm 2 chỗ)' : 'thường';
                    const statusLabel = status === 'SOLD' ? 'đã bán'
                      : status === 'HOLD' ? 'đang được giữ'
                      : status === 'MAINTENANCE' ? 'đang bảo trì' : 'còn trống';
                    button.setAttribute('aria-label',
                      'Ghế ' + seatCode + ', loại ' + typeLabel + ', ' + statusLabel);
                    const isOwnHold = belongsToCurrentUser(seat);
                    const isSelected = isOwnHold || selectedSeats.some(item => item.seatId === seat.seatId);
                    if (isSelected) button.classList.add('selected');
                    const isSelectable = isOwnHold || status === 'AVAILABLE';
                    if (!isSelectable) button.disabled = true;
                    button.addEventListener('click', async () => {
                      if (!isSelectable || holdRequestInFlight) return;
                      // Click = chọn + giữ luôn (không cần nút "Giữ ghế" riêng).
                      // Nếu 2 khách click cùng lúc, server trả 409 → revert visual + thông báo.
                      if (isOwnHold) {
                        await releaseCurrentHold().catch(error => notify(error.message));
                        return;
                      }
                      const wasSelected = selectedSeats.some(item => item.seatId === seat.seatId);
                      if (wasSelected) {
                        selectedSeats = selectedSeats.filter(item => item.seatId !== seat.seatId);
                        button.classList.remove('selected');
                        renderSummary();
                        return;
                      }
                      // optimistic add trước khi server xác nhận
                      selectedSeats.push(seat);
                      button.classList.add('selected');
                      renderSummary();
                      holdRequestInFlight = true;
                      try {
                        const seatIds = selectedSeats.map(item => item.seatId);
                        const result = await api('/booking/hold', { method: 'POST', body: {
                          showtimeId: currentShow.id,
                          seatIds: seatIds.join(','),
                          holdId: holdId || ''
                        }});
                        if (!result.success) throw new Error(result.message || 'Không giữ được ghế.');
                        holdId = result.holdId;
                        paymentArea.hidden = false;
                        notify('Đã giữ ghế. Bạn có 10 phút để thanh toán.', 'ok');
                        await selectShow(currentShow);
                      } catch (error) {
                        // Server từ chối (ghế bị người khác giữ trước / không khả dụng) → revert.
                        selectedSeats = selectedSeats.filter(item => item.seatId !== seat.seatId);
                        button.classList.remove('selected');
                        renderSummary();
                        notify(error.message || 'Ghế đã được chọn bởi người khác — vui lòng thử ghế khác.');
                        // Reload seat map để đồng bộ với server
                        if (currentShow) {
                          try { await selectShow(currentShow); } catch (ignored) { /* noop */ }
                        }
                      } finally {
                        holdRequestInFlight = false;
                      }
                    });
                    row.appendChild(button);
                    // Bản ghi ghế đôi đã chiếm cả cột hiện tại và cột kế tiếp.
                    if (seatType === 'COUPLE') colNo++;
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
                await api('/booking/release', {
                  method: 'POST',
                  body: {
                    showtimeId: currentShow.id,
                    seatIds: selectedSeats.map(seat => seat.seatId).join(','),
                    holdId: holdId
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
              payButton.addEventListener('click', async () => {
                try {
                  const selectedPoints = Number(usePointsInput.value || 0);
                  const maxRedeemablePoints = Number(usePointsInput.dataset.maxRedeemablePoints);
                  if (!Number.isSafeInteger(selectedPoints) || selectedPoints < 0) {
                    pointsMsg.textContent = 'Số điểm phải là số nguyên không âm.';
                    pointsMsg.classList.add('is-error');
                    usePointsInput.classList.add('is-invalid');
                    usePointsInput.setAttribute('aria-invalid', 'true');
                    usePointsInput.focus();
                    return;
                  }
                  if (Number.isFinite(maxRedeemablePoints) && selectedPoints > maxRedeemablePoints) {
                    pointsMsg.textContent = 'Số điểm vượt quá số dư hiện có: '
                      + maxRedeemablePoints.toLocaleString('vi-VN')
                      + ' điểm.';
                    pointsMsg.classList.add('is-error');
                    usePointsInput.classList.add('is-invalid');
                    usePointsInput.setAttribute('aria-invalid', 'true');
                    usePointsInput.focus();
                    return;
                  }
                  payButton.disabled = true;
                  const result = await api('/booking/confirm', {
                    method: 'POST',
                    body: {
                      holdId,
                      paymentMethod: 'VNPAY',
                      voucherCode: voucherCode,
                      pointsUsed: selectedPoints
                    }
                  });
                  if (result.redirectUrl) {
                    window.location.href = result.redirectUrl;
                    return;
                  }
                  const ticket = result.ticket || result;
                  notify('Thanh toán thành công. Mã vé: ' + (ticket.ticketCode || ticket.id), 'ok');
                  if (window.CinemaHub && window.CinemaHub.refreshNotifications) {
                    await window.CinemaHub.refreshNotifications();
                    setTimeout(function () { window.CinemaHub.refreshNotifications(); }, 300);
                  }
                  payButton.textContent = 'Đã thanh toán';
                  // Payment has consumed the hold. Clear the client selection first,
                  // then reload the authoritative seat state so the paid seats render
                  // as SOLD instead of remaining red/selected.
                  holdId = null;
                  selectedSeats = [];
                  paymentArea.hidden = true;
                  const code = ticket.ticketCode || String(ticket.id);
                  const success = document.getElementById('successTicket');
                  success.hidden = false;
                  const successCode = node('div', { class: 'ticket-success-code' });
                  successCode.append(node('small', {}, 'Mã vé'), node('strong', {}, code));
                  success.replaceChildren(
                    node('h3', {}, 'Vé đã sẵn sàng'),
                    node('p', { class: 'muted' }, 'Lưu mã vé này và xuất trình cho nhân viên tại rạp.'),
                    successCode,
                    node('canvas', { class: 'ticket-barcode', width: '220', height: '76' })
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
              payButton.textContent = 'Thanh toán bằng VNPay';
              // Voucher don gian: nhap ma -> goi /voucher/lookup de xem giam bao nhieu -> tinh lai gia
              if (voucherBtn) {
                voucherBtn.addEventListener('click', async () => {
                  const requestedCode = (voucherInput.value || '').trim().toUpperCase();
                  voucherInput.value = requestedCode;
                  voucherMsg.textContent = '';
                  if (!requestedCode) {
                    voucherMsg.textContent = 'Nhập mã voucher trước khi áp dụng.';
                    return;
                  }
                  if (!currentShow || !selectedSeats.length) {
                    voucherMsg.textContent = 'Chọn ghế trước rồi mới áp mã.';
                    return;
                  }
                  try {
                    voucherMsg.textContent = 'Đang kiểm tra mã...';
                    const res = await fetch(ctx + '/voucher/lookup?code=' + encodeURIComponent(requestedCode)
                      + '&orderTotal=999999999', { credentials: 'same-origin' });
                    const data = await res.json();
                    if (!res.ok || !data.success) throw new Error((data.error && data.error.message) || 'Mã không hợp lệ');
                    voucherCode = requestedCode;
                    updateVoucherCards();
                    voucherMsg.textContent = 'Đã áp dụng ' + voucherCode + '. Đang cập nhật giá vé.';
                    renderSummary();
                  } catch (e) {
                    voucherCode = '';
                    updateVoucherCards();
                    voucherMsg.textContent = e.message || 'Mã không hợp lệ.';
                    renderSummary();
                  }
                });
              }
              voucherInput.addEventListener('input', updateVoucherCards);
              voucherClearBtn.addEventListener('click', () => {
                voucherCode = '';
                voucherInput.value = '';
                voucherMsg.textContent = 'Đã gỡ voucher khỏi đơn hàng.';
                updateVoucherCards();
                renderSummary();
              });
              usePointsInput.addEventListener('input', renderSummary);
              load().catch(error => {
                showtimesNode.replaceChildren(node('p', { class: 'muted' }, 'Không thể tải suất chiếu: ' + error.message));
              });
              loadAvailableVouchers().catch(error => {
                availableVouchers.textContent = 'Không thể tải voucher: ' + error.message;
                console.error('[CinemaHub booking] Failed to load available vouchers', error);
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
                  modal.className = 'booking-payment-modal';
                  modal.innerHTML = `
                    <div class="booking-payment-backdrop"></div>
                    <div class="booking-payment-panel" role="dialog" aria-modal="true" aria-labelledby="bookingPaymentTitle">
                      <div class="booking-payment-head">
                        <h2 id="bookingPaymentTitle">Thanh toán thành công</h2>
                        <button type="button" class="btn secondary booking-payment-close" aria-label="Đóng">×</button>
                      </div>
                      <div class="booking-payment-body ticket-result-modal">
                        <h3>Vé của bạn đã sẵn sàng</h3>
                        <p class="muted">Lưu mã vé này và xuất trình cho nhân viên tại rạp để vào suất chiếu.</p>
                        <div class="ticket-success-code"><small>Mã vé</small><strong class="ticket-result-code"></strong></div>
                        <canvas class="ticket-barcode" width="240" height="82"></canvas>
                      </div>
                      <div class="booking-payment-foot">
                        <a class="btn secondary" href="__CTX__/console?module=my-tickets">Xem vé của tôi</a>
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
                  modal.querySelector('.booking-payment-backdrop').addEventListener('click', __dismiss);
                  modal.querySelector('.booking-payment-close').addEventListener('click', __dismiss);
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
