package com.cinema.service.booking;

import com.cinema.dao.booking.SeatHoldDAO;
import com.cinema.dao.booking.TicketDAO;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.listener.ExpiryScheduler;
import com.cinema.model.booking.HoldResult;
import com.cinema.model.booking.Ticket;
import com.cinema.model.branch.Branch;
import com.cinema.model.concession.ConcessionOrder;
import com.cinema.model.notification.Notification;
import com.cinema.service.audit.AuditService;
import com.cinema.service.concession.ConcessionService;
import com.cinema.service.notification.NotificationService;

import com.cinema.common.ServiceException;
import com.cinema.service.loyalty.LoyaltyService;
import com.cinema.model.payment.Payment;
import com.cinema.dao.payment.PaymentDAO;
import com.cinema.service.payment.PaymentService;
import com.cinema.service.pricing.PricingService;
import com.cinema.model.pricing.TicketType;
import com.cinema.model.showtime.Showtime;
import com.cinema.service.showtime.ShowtimeService;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service đặt vé trọng tâm đồng thời (Req 7.1-7.7, 8.1-8.6, 11.1-11.7, 12.1-12.6).
 *
 * <p>Concurrency (design.md BookingService):
 * <ul>
 *   <li>holdSeats: khóa showtime_seat theo seat_id TĂNG DẦN bằng
 *       SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK) trong transaction → hai Customer
 *       giữ cùng ghế đồng thời thì đúng một thắng (Req 8.1-8.3).</li>
 *   <li>confirmBooking: khóa hold + vé, kiểm còn hiệu lực, áp voucher guarded,
 *       thanh toán, chuyển ghế SOLD — nguyên tử trong MỘT transaction (Req 8.2).</li>
 *   <li>Lazy expiry: hold hết hạn coi như AVAILABLE khi đọc; sweep dọn hold/ticket
 *       PENDING quá hạn (Req 7.4, 8.4; scheduler 30s ở task 11.1 gọi cùng hàm).</li>
 *   <li>cancelTicket: bậc thang ≥24h hoàn 100%, 2–24h hoàn 50%, <2h không hoàn,
 *       USED chặn (Req 11.1); hai hủy đồng thời → đúng một thắng (Req 11.6).</li>
 *   <li>validateTicket: một lần soát tại đúng chi nhánh, showtime chưa kết thúc (Req 12).</li>
 * </ul>
 */
public class BookingService {
    private static final Logger logger = Logger.getLogger(BookingService.class.getName());
    private static final ZoneId BUSINESS_TZ = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SeatHoldDAO seatHoldDao;
    private final TicketDAO ticketDao;
    private final ShowtimeService showtimeService;
    private final PricingService pricingService;
    private final LoyaltyService loyaltyService;
    private final PaymentService paymentService;
    private final PaymentDAO paymentDao = new PaymentDAO();
    /** Optional — F&B pre-order gắn vé (Req 21.1, 21.6); null khi chưa cần tích hợp. */
    private final com.cinema.service.concession.ConcessionService concessionService;
    /** Optional — thông báo in-app (Req 23.1, 23.2); best-effort, không rollback luồng chính. */
    private final com.cinema.service.notification.NotificationService notificationService;
    /** Req 15.3/11.2 — audit mọi state transition của vé. */
    private final com.cinema.service.audit.AuditService audit = new com.cinema.service.audit.AuditService();
    private final TransactionTemplate tx = new TransactionTemplate();
    public BookingService(SeatHoldDAO seatHoldDao, TicketDAO ticketDao,
                          ShowtimeService showtimeService, PricingService pricingService,
                          LoyaltyService loyaltyService,
                          PaymentService paymentService) {
        this(seatHoldDao, ticketDao, showtimeService, pricingService,
                loyaltyService, paymentService, null, null);
    }

    public BookingService(SeatHoldDAO seatHoldDao, TicketDAO ticketDao,
                          ShowtimeService showtimeService, PricingService pricingService,
                          LoyaltyService loyaltyService,
                          PaymentService paymentService,
                          com.cinema.service.concession.ConcessionService concessionService) {
        this(seatHoldDao, ticketDao, showtimeService, pricingService,
                loyaltyService, paymentService, concessionService, null);
    }

    public BookingService(SeatHoldDAO seatHoldDao, TicketDAO ticketDao,
                          ShowtimeService showtimeService, PricingService pricingService,
                          LoyaltyService loyaltyService,
                          PaymentService paymentService,
                          com.cinema.service.concession.ConcessionService concessionService,
                          com.cinema.service.notification.NotificationService notificationService) {
        this.seatHoldDao = seatHoldDao;
        this.ticketDao = ticketDao;
        this.showtimeService = showtimeService;
        this.pricingService = pricingService;
        this.loyaltyService = loyaltyService;
        this.paymentService = paymentService;
        this.concessionService = concessionService;
        this.notificationService = notificationService;
    }

    /**
     * Tạo seat hold 10 phút (Req 7.1-7.3, 8.1-8.6): kiểm showtime mở bán, toàn bộ ghế
     * trống (AVAILABLE hoặc hold đã hết hạn — lazy), khóa theo seatId tăng dần rồi
     * chuyển HOLD + tạo hold + vé PENDING trong cùng transaction.
     */
    public HoldResult holdSeats(long showtimeId, List<Long> seatIds, Long userId) {
        return holdSeats(showtimeId, seatIds, userId, null);
    }

    /**
     * Hold seats + resolve giá theo {@code ticketType} (Req 9). Backend ignore
     * {@code unitPrice} client gửi — luôn resolve qua PricingService.
     */
    public HoldResult holdSeats(long showtimeId, List<Long> seatIds, Long userId, TicketType ticketType) {
        // Sweep các hold đã hết hạn trước khi check ghế AVAILABLE — nếu không,
        // user phải đợi scheduler 30s mới thấy ghế "stale" được giải phóng.
        expireElapsed();
        if (seatIds == null || seatIds.isEmpty()) {
            return HoldResult.rejected("Vui lòng chọn ít nhất một ghế");
        }
        // Chuẩn hóa: distinct + sắp TĂNG DẦN theo seatId (khóa đúng thứ tự tránh deadlock, Req 8.3)
        TreeSet<Long> ordered = new TreeSet<>(seatIds);
        Showtime showtime;
        try {
            showtime = showtimeService.requireBookable(showtimeId);
        } catch (Exception e) {
            return HoldResult.rejected(safeMessage(e, "Suất chiếu không khả dụng"));
        }

        try {
            return tx.execute(conn -> {
                // Khóa + kiểm từng ghế theo thứ tự tăng dần
                List<SeatHoldDAO.SeatRow> locked = new ArrayList<>();
                for (Long seatId : ordered) {
                    SeatHoldDAO.SeatRow row = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                    if (row == null) {
                        throw new ServiceException.Conflict(
                                "Ghế " + seatId + " không thuộc phòng chiếu của suất này");
                    }
                    boolean expiredHold = isHoldExpired(row);
                    if (!"AVAILABLE".equals(row.status) && !expiredHold) {
                        throw new ServiceException.Conflict("Ghế " + row.rowLabel + row.colNo
                                + " không còn trống (trạng thái: " + row.status + ")");
                    }
                    locked.add(row);
                }

                // Tạo hold + chuyển ghế HOLD
                long holdId = seatHoldDao.insertHold(conn, showtimeId, userId);
                LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(SeatHoldDAO.HOLD_MINUTES);
                for (SeatHoldDAO.SeatRow row : locked) {
                    seatHoldDao.markSeatHeld(conn, showtimeId, row.seatId, holdId, expiresAt);
                }

                // Tính giá snapshot cho từng ghế + tạo vé PENDING (Req 7.1, 8, 11)
                long total = 0;
                List<Ticket.TicketSeat> pricedSeats = new ArrayList<>();
                for (SeatHoldDAO.SeatRow row : locked) {
                    long price = pricingService.priceSeatFor(showtime, row.seatType, ticketType).price();
                    total += price;
                    pricedSeats.add(new Ticket.TicketSeat(row.seatId, row.rowLabel, row.colNo,
                            row.seatType, ticketType == null ? null : ticketType.code(), price));
                }
                Ticket ticket = new Ticket();
                ticket.setTicketCode(generateTicketCode());
                ticket.setShowtimeId(showtimeId);
                ticket.setBranchId(showtime.branchId());
                ticket.setUserId(userId);
                ticket.setStatus(Ticket.STATUS_PENDING);
                ticket.setTotalAmount(total);
                ticket.setHoldId(holdId);
                long ticketId = ticketDao.insert(conn, ticket);
                ticketDao.insertSeats(conn, ticketId, pricedSeats);

                // Req 15.3 — audit tạo hold + vé PENDING trong cùng transaction
                audit.record(conn, userId, "CREATE_HOLD", "TICKET", ticketId, null,
                        "{\"holdId\":" + holdId + ",\"showtimeId\":" + showtimeId
                                + ",\"seats\":" + ordered.size() + ",\"status\":\"PENDING\"}",
                        com.cinema.service.audit.AuditService.SUCCESS);

                logger.info("Hold " + holdId + " created for ticket " + ticketId
                        + " (" + locked.size() + " seats, showtime " + showtimeId + ")");
                return HoldResult.accepted(String.valueOf(holdId));
            });
        } catch (ServiceException e) {
            return HoldResult.rejected(e.getMessage());
        } catch (Exception e) {
            logger.log(java.util.logging.Level.WARNING, "holdSeats failed", e);
            return HoldResult.rejected("Không giữ được ghế — vui lòng thử lại");
        }
    }

    /** Atomically adds newly selected seats to an existing customer hold. */
    public HoldResult addSeatsToHold(long showtimeId, List<Long> requestedSeatIds,
                                     Long userId, long holdId, TicketType ticketType) {
        expireElapsed();
        if (requestedSeatIds == null || requestedSeatIds.isEmpty()) {
            return HoldResult.rejected("Vui lòng chọn ít nhất một ghế");
        }
        TreeSet<Long> ordered = new TreeSet<>(requestedSeatIds);
        try {
            return tx.execute(conn -> {
                SeatHoldDAO.HoldRow hold = seatHoldDao.lockHold(conn, holdId);
                if (hold == null) {
                    throw new ServiceException.NotFound("Không tìm thấy lượt giữ ghế");
                }
                if (userId == null || !userId.equals(hold.userId)) {
                    throw new ServiceException.Forbidden("Bạn không có quyền sửa lượt giữ ghế này");
                }
                if (hold.showtimeId != showtimeId) {
                    throw new ServiceException.Conflict("Lượt giữ ghế thuộc suất chiếu khác");
                }
                if (!"ACTIVE".equals(hold.status) || hold.expiresAt == null
                        || LocalDateTime.now(ZoneOffset.UTC).isAfter(hold.expiresAt)) {
                    throw new ServiceException.Conflict(
                            "Giữ ghế đã hết hạn — vui lòng chọn lại ghế");
                }

                long ticketId = ticketIdOfHold(conn, holdId);
                Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                        .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy vé đang giữ"));
                if (!Ticket.STATUS_PENDING.equals(ticket.status()) || !userId.equals(ticket.userId())) {
                    throw new ServiceException.Conflict("Vé này không còn có thể thay đổi ghế");
                }

                List<Long> existingIds = seatHoldDao.findSeatIdsOfHold(conn, holdId);
                if (!ordered.containsAll(existingIds)) {
                    throw new ServiceException.Conflict(
                            "Danh sách ghế không khớp với lượt giữ hiện tại. Vui lòng tải lại.");
                }
                java.util.Set<Long> existingIdSet = new java.util.HashSet<>(existingIds);
                List<SeatHoldDAO.SeatRow> additions = new ArrayList<>();
                for (Long seatId : ordered) {
                    SeatHoldDAO.SeatRow row = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                    if (row == null) {
                        throw new ServiceException.Conflict(
                                "Ghế " + seatId + " không thuộc phòng chiếu của suất này");
                    }
                    if (existingIdSet.contains(seatId)) {
                        if (!"HOLD".equals(row.status) || !Long.valueOf(holdId).equals(row.holdId)) {
                            throw new ServiceException.Conflict(
                                    "Một ghế đang giữ không còn hợp lệ. Vui lòng tải lại.");
                        }
                    } else if ("AVAILABLE".equals(row.status)) {
                        additions.add(row);
                    } else {
                        throw new ServiceException.Conflict("Ghế " + row.rowLabel + row.colNo
                                + " không còn trống");
                    }
                }

                if (!additions.isEmpty()) {
                    Showtime showtime = showtimeService.requireBookable(showtimeId);
                    long addedTotal = 0;
                    List<Ticket.TicketSeat> pricedSeats = new ArrayList<>();
                    for (SeatHoldDAO.SeatRow row : additions) {
                        long price = pricingService.priceSeatFor(showtime, row.seatType, ticketType).price();
                        addedTotal += price;
                        pricedSeats.add(new Ticket.TicketSeat(row.seatId, row.rowLabel, row.colNo,
                                row.seatType, ticketType == null ? null : ticketType.code(), price));
                    }
                    if (!ticketDao.updatePendingTotalGuarded(conn, ticketId,
                            ticket.totalAmount() + addedTotal)) {
                        throw new ServiceException.Conflict("Vé không còn ở trạng thái chờ thanh toán");
                    }
                    ticketDao.insertSeats(conn, ticketId, pricedSeats);
                    for (SeatHoldDAO.SeatRow row : additions) {
                        seatHoldDao.markSeatHeld(conn, showtimeId, row.seatId, holdId, hold.expiresAt);
                    }
                }
                return HoldResult.accepted(String.valueOf(holdId));
            });
        } catch (ServiceException e) {
            return HoldResult.rejected(e.getMessage());
        } catch (Exception e) {
            logger.log(java.util.logging.Level.WARNING, "addSeatsToHold failed", e);
            return HoldResult.rejected("Không thể thêm ghế vào lượt giữ — vui lòng thử lại");
        }
    }

    /**
     * Xác nhận booking trong thời hạn hold (Req 7.5, 7.6, 8.2, 10.3, 10.4, 17.2):
     * hold còn hiệu lực → voucher guarded → thanh toán → vé CONFIRMED + ghế SOLD
     * + cộng điểm, nguyên tử. Confirm sau expiry bị từ chối (Req 7.6).
     *
     * @param paymentMethod CASH/MOCK_GATEWAY/BANK_TRANSFER (null = CASH quầy)
     */
    public Ticket confirmBooking(long holdId, String paymentMethod, String voucherCode,
                                 Long actorUserId, Long actorBranchId) throws Exception {
        return confirmBooking(holdId, paymentMethod, voucherCode, actorUserId, actorBranchId, false);
    }

    public Ticket confirmBooking(long holdId, String paymentMethod, String voucherCode,
                                 Long actorUserId, Long actorBranchId, boolean usePoints) throws Exception {
        return confirmBooking(holdId, paymentMethod, voucherCode, actorUserId, actorBranchId,
                usePoints ? -1 : 0);
    }

    public Ticket confirmBooking(long holdId, String paymentMethod, String voucherCode,
                                 Long actorUserId, Long actorBranchId, int pointsUsed) throws Exception {
        expireElapsed();
        return tx.execute(conn -> {
            SeatHoldDAO.HoldRow hold = seatHoldDao.lockHold(conn, holdId);
            if (hold == null) {
                throw new ServiceException.NotFound("Không tìm thấy bản ghi giữ ghế");
            }
            if (actorUserId == null || hold.userId == null || !actorUserId.equals(hold.userId)) {
                throw new ServiceException.Forbidden(
                        "Bạn không có quyền xác nhận booking này");
            }
            // Req 7.6 — hold đã hết hạn → từ chối
            if (!"ACTIVE".equals(hold.status)) {
                throw new ServiceException.Conflict(
                        "Giữ ghế đã hết hạn — vui lòng chọn lại ghế");
            }
            if (hold.expiresAt != null && LocalDateTime.now(ZoneOffset.UTC).isAfter(hold.expiresAt)) {
                throw new ServiceException.Conflict(
                        "Giữ ghế đã hết hạn — vui lòng chọn lại ghế");
            }
            List<Long> seatIds = seatHoldDao.findSeatIdsOfHold(conn, holdId);
            if (seatIds.isEmpty()) {
                throw new ServiceException.Conflict("Giữ ghế không còn ghế hợp lệ");
            }
            Showtime showtime = showtimeService.requireBookable(hold.showtimeId);

            Ticket ticket = ticketDao.findByIdLocked(conn, ticketIdOfHold(conn, holdId))
                    .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));
            if (!actorUserId.equals(ticket.userId())) {
                throw new ServiceException.Forbidden(
                        "Vé không thuộc tài khoản đang đăng nhập");
            }

            // Tính lại tổng theo giá snapshot + tier discount + voucher (Req 10.3, 17.7)
            long baseTotal = ticket.totalAmount();
            Long buyerId = ticket.userId() != null ? ticket.userId() : actorUserId;
            long tierDiscount = loyaltyService.tierDiscountFor(buyerId, baseTotal);
            long afterTier = baseTotal - tierDiscount;
            // Voucher don gian: tru luot trong cung transaction booking
            String appliedVoucher = null;
            long voucherDiscount = new com.cinema.service.voucher.VoucherService()
                    .applyInTx(conn, voucherCode, afterTier);
            if (voucherDiscount > 0) {
                appliedVoucher = voucherCode == null ? null : voucherCode.trim().toUpperCase();
            }
            long payable = afterTier - voucherDiscount;
            payable = applyTicketPointRedemption(conn, ticket, buyerId, payable, pointsUsed);

            // Req 21.1 — gộp giá trị đơn bắp nước PENDING gắn vé vào tổng thanh toán
            long concessionTotal = 0;
            if (concessionService != null) {
                concessionTotal = concessionService.pendingTotalForTicket(conn, ticket.id());
            }
            long totalPayable = payable + concessionTotal;

            String method = paymentMethod == null || paymentMethod.isBlank()
                    ? Payment.METHOD_CASH : paymentMethod.trim().toUpperCase();
            if (totalPayable == 0) {
                paymentService.confirmZeroAmountTicket(conn, ticket.id(), totalPayable);
                String settlementMethod = ticket.pointsUsed() > 0 ? "POINTS" : "NO_CHARGE";
                audit.record(conn, buyerId, "CONFIRM_ZERO_AMOUNT_TICKET", "TICKET", ticket.id(),
                        "{\"status\":\"PENDING\"}",
                        "{\"method\":\"" + settlementMethod + "\",\"payable\":0}",
                        com.cinema.service.audit.AuditService.SUCCESS);
                logger.info("Confirmed zero-amount ticket " + ticket.id()
                        + " (method=" + settlementMethod + ")");
            } else {
                // Customer thanh toán online = tạo payment PENDING chờ IPN/confirm.
                paymentService.createSession(ticket.id(), method, totalPayable, buyerId);
                audit.record(conn, buyerId, "CREATE_PAYMENT_SESSION", "TICKET", ticket.id(),
                        "{\"status\":\"PENDING\"}",
                        "{\"method\":\"" + method + "\",\"payable\":" + totalPayable + "}",
                        com.cinema.service.audit.AuditService.SUCCESS);
                logger.info("Payment session created (" + method + ") for ticket "
                        + ticket.id() + ", payable " + totalPayable);
            }

            return reloadWithSeats(conn, ticket.id());
        });
    }

    /**
     * Bán vé tại quầy (Req 7.7): Branch Staff chọn ghế trống, xác nhận tiền mặt ngay,
     * bỏ qua hold 10 phút nhưng vẫn khóa ghế + ép branch scope.
     */
    public Ticket sellAtCounter(long showtimeId, List<Long> seatIds, String voucherCode,
                                Long customerId, long staffId, long staffBranchId) throws Exception {
        return sellAtCounter(showtimeId, seatIds, voucherCode, customerId, staffId, staffBranchId, null);
    }

    /** Sell + extended variant với ticketType (Req 9). */
    public Ticket sellAtCounter(long showtimeId, List<Long> seatIds, String voucherCode,
                                Long customerId, long staffId, long staffBranchId,
                                TicketType ticketType) throws Exception {
        return sellAtCounter(showtimeId, seatIds, voucherCode, customerId, staffId,
                staffBranchId, ticketType, 0);
    }

    /** Counter sale with optional customer point redemption. */
    public Ticket sellAtCounter(long showtimeId, List<Long> seatIds, String voucherCode,
                                Long customerId, long staffId, long staffBranchId,
                                TicketType ticketType, int requestedPoints) throws Exception {
        return sellAtCounter(showtimeId, seatIds, voucherCode, customerId, staffId,
                staffBranchId, ticketType, requestedPoints, Payment.METHOD_CASH);
    }

    /** Counter sale with explicit cash or bank-transfer payment confirmation. */
    public Ticket sellAtCounter(long showtimeId, List<Long> seatIds, String voucherCode,
                                Long customerId, long staffId, long staffBranchId,
                                TicketType ticketType, int requestedPoints,
                                String paymentMethod) throws Exception {
        return sellAtCounter(showtimeId, seatIds, voucherCode, customerId, staffId,
                staffBranchId, ticketType, requestedPoints, paymentMethod, null);
    }

    public Ticket sellAtCounter(long showtimeId, List<Long> seatIds, String voucherCode,
                                Long customerId, long staffId, long staffBranchId,
                                TicketType ticketType, int requestedPoints,
                                String paymentMethod, String paymentReference) throws Exception {
        String method = normalizeCounterPaymentMethod(paymentMethod);
        String reference = requireCounterPaymentReference(method, paymentReference);
        if (staffBranchId <= 0) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        if (requestedPoints < 0) {
            throw new ServiceException.Validation("Số điểm sử dụng không hợp lệ");
        }
        if (requestedPoints > 0 && customerId == null) {
            throw new ServiceException.Validation("Cần chọn khách hàng để sử dụng điểm");
        }
        expireElapsed();
        TreeSet<Long> ordered = new TreeSet<>(seatIds == null ? List.of() : seatIds);
        Showtime showtime = showtimeService.requireBookable(showtimeId);
        // Req 7.7 — staff chỉ bán vé cho suất thuộc chi nhánh mình
        if (showtime.branchId() != staffBranchId) {
            throw new ServiceException.Forbidden(
                    "Vi phạm phạm vi chi nhánh: suất chiếu thuộc chi nhánh khác");
        }

        return tx.execute(conn -> {
            List<SeatHoldDAO.SeatRow> locked = new ArrayList<>();
            for (Long seatId : ordered) {
                SeatHoldDAO.SeatRow row = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                if (row == null) {
                    throw new ServiceException.Conflict(
                            "Ghế " + seatId + " không thuộc phòng chiếu của suất này");
                }
                if (!"AVAILABLE".equals(row.status) && !isHoldExpired(row)) {
                    throw new ServiceException.Conflict("Ghế " + row.rowLabel + row.colNo
                            + " không còn trống");
                }
                locked.add(row);
            }

            long total = 0;
            List<Ticket.TicketSeat> pricedSeats = new ArrayList<>();
            for (SeatHoldDAO.SeatRow row : locked) {
                long price = pricingService.priceSeatFor(showtime, row.seatType, ticketType).price();
                total += price;
                pricedSeats.add(new Ticket.TicketSeat(row.seatId, row.rowLabel, row.colNo,
                        row.seatType, ticketType == null ? null : ticketType.code(), price));
            }

            Long buyerId = customerId;
            long tierDiscount = loyaltyService.tierDiscountFor(buyerId, total);
            long afterTier = total - tierDiscount;
            String appliedVoucher = null;
            long voucherDiscount = new com.cinema.service.voucher.VoucherService()
                    .applyInTx(conn, voucherCode, afterTier);
            if (voucherDiscount > 0) {
                appliedVoucher = voucherCode == null ? null : voucherCode.trim().toUpperCase();
            }
            long payable = afterTier - voucherDiscount;

            Ticket ticket = new Ticket();
            ticket.setTicketCode(generateTicketCode());
            ticket.setShowtimeId(showtimeId);
            ticket.setBranchId(showtime.branchId());
            ticket.setUserId(buyerId);
            ticket.setStatus(Ticket.STATUS_PENDING);
            ticket.setTotalAmount(payable);
            ticket.setVoucherCode(appliedVoucher);
            long ticketId = ticketDao.insert(conn, ticket);
            ticketDao.insertSeats(conn, ticketId, pricedSeats);
            payable = applyTicketPointRedemption(conn, ticket, buyerId, payable, requestedPoints);

            // Bán tại quầy: tiền mặt xác nhận ngay → CONFIRMED + ghế SOLD (Req 7.7)
            int points = buyerId != null ? earnPoints(conn, buyerId, payable, ticket.id()) : 0;
            if (!ticketDao.confirmGuarded(conn, ticketId, payable, appliedVoucher, points)) {
                throw new ServiceException.Conflict("Không xác nhận được vé tại quầy");
            }
            recordCounterPayment(conn, ticketId, method, payable, reference);
            for (Long seatId : ordered) {
                seatHoldDao.markSeatSold(conn, showtimeId, seatId);
            }

            logger.info("Counter sale: ticket " + ticketId + " by staff " + staffId
                    + " (" + locked.size() + " seats, " + payable + "đ)");
            // Req 15.3 — audit bán vé tại quầy (actor = staff)
            audit.record(conn, staffId, "SELL_AT_COUNTER", "TICKET", ticketId, null,
                    "{\"showtimeId\":" + showtimeId + ",\"seats\":" + locked.size()
                            + ",\"payable\":" + payable + ",\"method\":\"" + method
                            + "\",\"status\":\"CONFIRMED\"}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return reloadWithSeats(conn, ticketId);
        });
    }

    /**
     * Kết quả counter-sale: vé chính + đơn F&B đi kèm (nếu có). Dùng cho luồng
     * POS bán vé + combo bắp nước tại quầy (counter-sale bundle).
     */
    public record CounterSaleResult(Ticket ticket, ConcessionOrder concessionOrder) {
    }

    /**
     * Bán vé tại quầy + (tuỳ chọn) tạo đơn F&B offline cùng transaction
     * (POS counter-sale bundle). Tổng thanh toán = vé (sau tier/voucher) + đơn F&B.
     * Trừ kho F&B thất bại → rollback toàn bộ (vé cũng không được tạo).
     *
     * @param concessionLines danh sách (productId, qty); null/rỗng = chỉ bán vé
     * @return {@link CounterSaleResult} — concessionOrder = null nếu không có F&B
     */
    public CounterSaleResult sellAtCounterWithConcession(long showtimeId,
                                                         List<Long> seatIds,
                                                         String voucherCode,
                                                         Long customerId,
                                                         long staffId,
                                                         long staffBranchId,
                                                         TicketType ticketType,
                                                         List<com.cinema.service.concession.ConcessionService.OrderLineInput> concessionLines)
            throws Exception {
        return sellAtCounterWithConcession(showtimeId, seatIds, voucherCode, customerId,
                staffId, staffBranchId, ticketType, concessionLines, 0);
    }

    /** Counter-sale bundle with optional point redemption on ticket subtotal. */
    public CounterSaleResult sellAtCounterWithConcession(long showtimeId,
                                                         List<Long> seatIds,
                                                         String voucherCode,
                                                         Long customerId,
                                                         long staffId,
                                                         long staffBranchId,
                                                         TicketType ticketType,
                                                         List<com.cinema.service.concession.ConcessionService.OrderLineInput> concessionLines,
                                                         int requestedPoints)
            throws Exception {
        return sellAtCounterWithConcession(showtimeId, seatIds, voucherCode, customerId,
                staffId, staffBranchId, ticketType, concessionLines, requestedPoints,
                Payment.METHOD_CASH);
    }

    /** Counter-sale ticket + concession bundle with an explicit payment method. */
    public CounterSaleResult sellAtCounterWithConcession(long showtimeId,
                                                         List<Long> seatIds,
                                                         String voucherCode,
                                                         Long customerId,
                                                         long staffId,
                                                         long staffBranchId,
                                                         TicketType ticketType,
                                                         List<com.cinema.service.concession.ConcessionService.OrderLineInput> concessionLines,
                                                         int requestedPoints,
                                                         String paymentMethod)
            throws Exception {
        return sellAtCounterWithConcession(showtimeId, seatIds, voucherCode, customerId,
                staffId, staffBranchId, ticketType, concessionLines, requestedPoints,
                paymentMethod, null);
    }

    public CounterSaleResult sellAtCounterWithConcession(long showtimeId,
                                                         List<Long> seatIds,
                                                         String voucherCode,
                                                         Long customerId,
                                                         long staffId,
                                                         long staffBranchId,
                                                         TicketType ticketType,
                                                         List<com.cinema.service.concession.ConcessionService.OrderLineInput> concessionLines,
                                                         int requestedPoints,
                                                         String paymentMethod,
                                                         String paymentReference)
            throws Exception {
        String method = normalizeCounterPaymentMethod(paymentMethod);
        String reference = requireCounterPaymentReference(method, paymentReference);
        if (staffBranchId <= 0) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        if (requestedPoints < 0) {
            throw new ServiceException.Validation("Số điểm sử dụng không hợp lệ");
        }
        if (requestedPoints > 0 && customerId == null) {
            throw new ServiceException.Validation("Cần chọn khách hàng để sử dụng điểm");
        }
        if (concessionLines != null && !concessionLines.isEmpty() && concessionService == null) {
            throw new ServiceException.BusinessRule("CONCESSION_DISABLED",
                    "Chức năng bắp nước chưa được kích hoạt cho tenant này");
        }
        expireElapsed();
        TreeSet<Long> ordered = new TreeSet<>(seatIds == null ? List.of() : seatIds);
        Showtime showtime = showtimeService.requireBookable(showtimeId);
        if (showtime.branchId() != staffBranchId) {
            throw new ServiceException.Forbidden(
                    "Vi phạm phạm vi chi nhánh: suất chiếu thuộc chi nhánh khác");
        }

        return tx.execute(conn -> {
            // 1) Khóa + check ghế trống (giống sellAtCounter)
            List<SeatHoldDAO.SeatRow> locked = new ArrayList<>();
            for (Long seatId : ordered) {
                SeatHoldDAO.SeatRow row = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                if (row == null) {
                    throw new ServiceException.Conflict(
                            "Ghế " + seatId + " không thuộc phòng chiếu của suất này");
                }
                if (!"AVAILABLE".equals(row.status) && !isHoldExpired(row)) {
                    throw new ServiceException.Conflict("Ghế " + row.rowLabel + row.colNo
                            + " không còn trống");
                }
                locked.add(row);
            }

            // 2) Tính giá snapshot cho từng ghế
            long seatTotal = 0;
            List<Ticket.TicketSeat> pricedSeats = new ArrayList<>();
            for (SeatHoldDAO.SeatRow row : locked) {
                long price = pricingService.priceSeatFor(showtime, row.seatType, ticketType).price();
                seatTotal += price;
                pricedSeats.add(new Ticket.TicketSeat(row.seatId, row.rowLabel, row.colNo,
                        row.seatType, ticketType == null ? null : ticketType.code(), price));
            }

            // 3) Loyalty tier + voucher don gian
            Long buyerId = customerId;
            long tierDiscount = loyaltyService.tierDiscountFor(buyerId, seatTotal);
            long afterTier = seatTotal - tierDiscount;
            String appliedVoucher = null;
            long voucherDiscount = new com.cinema.service.voucher.VoucherService()
                    .applyInTx(conn, voucherCode, afterTier);
            if (voucherDiscount > 0) {
                appliedVoucher = voucherCode == null ? null : voucherCode.trim().toUpperCase();
            }
            long ticketPayable = afterTier - voucherDiscount;

            // 4) Tạo vé PENDING
            Ticket ticket = new Ticket();
            ticket.setTicketCode(generateTicketCode());
            ticket.setShowtimeId(showtimeId);
            ticket.setBranchId(showtime.branchId());
            ticket.setUserId(buyerId);
            ticket.setStatus(Ticket.STATUS_PENDING);
            ticket.setTotalAmount(ticketPayable);
            ticket.setVoucherCode(appliedVoucher);
            long ticketId = ticketDao.insert(conn, ticket);
            ticketDao.insertSeats(conn, ticketId, pricedSeats);
            ticketPayable = applyTicketPointRedemption(
                    conn, ticket, buyerId, ticketPayable, requestedPoints);

            // 5) Bundle F&B (nếu có) — tạo + confirm trong cùng tx
            ConcessionOrder order = null;
            long concessionTotal = 0;
            if (concessionLines != null && !concessionLines.isEmpty()) {
                order = concessionService.createAndConfirmOfflineInTx(
                        conn, staffBranchId, concessionLines, customerId, ticketId,
                        staffId, method, reference);
                concessionTotal = order.totalAmount();
            }

            // 6) Total payable = vé (sau giảm) + F&B; cộng điểm trên tổng
            long finalPayable = ticketPayable + concessionTotal;
            int points = buyerId != null ? earnPoints(conn, buyerId, finalPayable, ticket.id()) : 0;

            // 7) Confirm vé
            if (!ticketDao.confirmGuarded(conn, ticketId, finalPayable, appliedVoucher, points)) {
                throw new ServiceException.Conflict("Không xác nhận được vé tại quầy");
            }
            recordCounterPayment(conn, ticketId, method, ticketPayable, reference);
            for (Long seatId : ordered) {
                seatHoldDao.markSeatSold(conn, showtimeId, seatId);
            }

            // 8) Audit
            audit.record(conn, staffId, "SELL_AT_COUNTER", "TICKET", ticketId, null,
                    "{\"showtimeId\":" + showtimeId + ",\"seats\":" + locked.size()
                            + ",\"ticketPayable\":" + ticketPayable
                            + ",\"concessionTotal\":" + concessionTotal
                            + ",\"finalPayable\":" + finalPayable
                            + ",\"method\":\"" + method
                            + ",\"status\":\"CONFIRMED\"}",
                    com.cinema.service.audit.AuditService.SUCCESS);

            logger.info("Counter sale bundle: ticket " + ticketId
                    + (order != null ? " + concession order " + order.id() : "")
                    + " by staff " + staffId
                    + " (" + locked.size() + " seats, final " + finalPayable + "đ)");

            return new CounterSaleResult(reloadWithSeats(conn, ticketId), order);
        });
    }

    private String normalizeCounterPaymentMethod(String paymentMethod) {
        String method = paymentMethod == null || paymentMethod.isBlank()
                ? Payment.METHOD_CASH : paymentMethod.trim().toUpperCase();
        if (!Payment.METHOD_CASH.equals(method) && !Payment.METHOD_BANK_TRANSFER.equals(method)) {
            throw new ServiceException.Validation(
                    "Phương thức thanh toán tại quầy phải là CASH hoặc BANK_TRANSFER");
        }
        return method;
    }

    private String requireCounterPaymentReference(String method, String reference) {
        if (Payment.METHOD_BANK_TRANSFER.equals(method)
                && (reference == null || !reference.matches("(?i)[A-Z0-9]{1,25}"))) {
            throw new ServiceException.Validation(
                    "Cần mã tham chiếu QR hợp lệ cho thanh toán chuyển khoản");
        }
        return reference == null ? null : reference.toUpperCase(java.util.Locale.ROOT);
    }

    private void recordCounterPayment(Connection conn, long ticketId, String method,
                                      long amount, String reference) throws Exception {
        Payment payment = new Payment();
        payment.setTicketId(ticketId);
        payment.setMethod(method);
        payment.setAmount(amount);
        payment.setStatus(Payment.STATUS_SUCCESS);
        payment.setIdempotencyKey("POS-TICKET-" + ticketId + "-" + UUID.randomUUID());
        payment.setProviderReference(reference);
        paymentDao.insertConfirmed(conn, payment);
    }

    /**
     * Đọc lại vé vừa tạo sau khi transaction counter-sale bundle commit. Lấy vé
     * mới nhất tại chi nhánh thuộc suất này (ticketCode là duy nhất nhưng ta dùng
     * ticketId DESC là đủ — staff chỉ vừa tạo).
     */
    private Ticket reloadTicketFreshly(long showtimeId, long staffBranchId) throws SQLException {
        try (Connection conn = DBContext.getConnection()) {
            String sql = "SELECT TOP 1 id FROM dbo.ticket WHERE showtime_id = ? AND branch_id = ?"
                    + " ORDER BY id DESC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, showtimeId);
                ps.setLong(2, staffBranchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ServiceException.NotFound("Không tìm thấy vé vừa tạo");
                    }
                    long ticketId = rs.getLong("id");
                    return reloadWithSeats(conn, ticketId);
                }
            }
        }
    }

    /**
     * Hủy vé + hoàn tiền bậc thang (Req 11.1-11.7): ≥24h hoàn 100%, 2–24h hoàn 50%,
     * <2h không hoàn, USED chặn; hoàn voucher lượt/điểm/ví, giải phóng ghế.
     * Hai yêu cầu hủy đồng thời → đúng một thắng qua cancelGuarded (Req 11.6).
     *
     * @return vé CANCELLED kèm số tiền hoàn
     */
    public Ticket cancelTicket(long ticketId, Long actorCustomerId, Long actorStaffBranchId) throws Exception {
        expireElapsed();
        return tx.execute(conn -> {
            Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                    .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));

            // Quyền: Customer hủy vé của mình; Staff hủy tại quầy đúng chi nhánh (Req 11.3)
            if (actorStaffBranchId != null) {
                if (ticket.branchId() != actorStaffBranchId) {
                    throw new ServiceException.Forbidden(
                            "Vi phạm phạm vi chi nhánh: vé thuộc chi nhánh khác");
                }
            } else if (actorCustomerId != null) {
                if (!actorCustomerId.equals(ticket.userId())) {
                    throw new ServiceException.Forbidden("Chỉ chủ vé mới được hủy vé");
                }
            } else {
                throw new ServiceException.Forbidden("Không có quyền hủy vé");
            }

            // Req 11.2, 11.7 — PENDING/CANCELLED/USED không hủy được
            if (Ticket.STATUS_PENDING.equals(ticket.status())) {
                throw new ServiceException.BusinessRule("TICKET_NOT_CONFIRMED",
                        "Vé chưa thanh toán — không thể hủy theo chính sách hoàn tiền");
            }
            if (Ticket.STATUS_USED.equals(ticket.status())) {
                throw new ServiceException.BusinessRule("TICKET_USED",
                        "Vé đã được soát — không thể hủy hoặc hoàn tiền");
            }
            if (Ticket.STATUS_CANCELLED.equals(ticket.status())) {
                throw new ServiceException.Conflict("Vé đã bị hủy trước đó");
            }

            // Bậc thang theo thời gian còn lại tới giờ chiếu (Req 11.1)
            Showtime showtime = showtimeService.requireBookableForCancel(ticket.showtimeId());
            long refundRatePercent = refundRatePercent(Instant.now(), showtime.startTime());
            long refundAmount = ticket.totalAmount() * refundRatePercent / 100;
            List<Ticket.TicketSeat> ticketSeats = ticketDao.findSeats(conn, ticketId);
            if (ticketSeats.isEmpty()) {
                throw new ServiceException.Conflict("Vé không có ghế để giải phóng");
            }

            // Req 11.6 — đúng một hủy thắng; guarded update WHERE status='CONFIRMED'
            if (!ticketDao.cancelGuarded(conn, ticketId, refundAmount)) {
                throw new ServiceException.Conflict("Vé vừa được xử lý bởi một yêu cầu khác");
            }

            // Hoàn điểm (Req 17.4)
            // Refund tiền KHÔNG ghi ví nội bộ nữa — luồng hoàn tiền chính thức là
            // refund_request (khách cung cấp NH, manager duyệt, email xác nhận).
            // Staff hủy tại quầy sẽ hoàn tiền mặt trực tiếp cho khách (Req 18.5 deprecated).
            if (ticket.pointsEarned() > 0 && ticket.userId() != null) {
                loyaltyService.deductPointsForRefund(conn, ticket.userId(),
                        ticket.pointsEarned(), "TICKET_CANCEL", ticket.id());
            }
            if (ticket.pointsUsed() > 0 && ticket.userId() != null) {
                if (ticket.pointsRedemptionKey() == null) {
                    throw new ServiceException.Conflict("Thiếu mã đối soát điểm đã sử dụng");
                }
                int pointsToRefund = (int) (ticket.pointsUsed() * refundRatePercent / 100);
                loyaltyService.refundPoints(conn, ticket.userId(), pointsToRefund,
                        "TICKET_REDEMPTION_CANCEL", ticket.id(),
                        ticket.pointsRedemptionKey() + "-CANCEL");
            }
            // Đánh dấu payment liên quan là REFUNDED để đối soát.
            paymentDao.markTicketPaymentsRefunded(conn, ticket.id());

            // Req 11.5 — giải phóng ghế, đặt lại được ngay
            for (Ticket.TicketSeat seat : ticketSeats) {
                seatHoldDao.releaseSeat(conn, showtime.id(), seat.seatId());
            }

            // Req 21.6 — hủy pre-order bắp nước gắn vé: refund + hoàn kho, nguyên tử với hủy vé
            if (concessionService != null) {
                concessionService.cascadeTicketCancelInTx(conn, ticketId);
            }

            // Req 23.2 — thông báo hủy/hoàn tiền kèm lý do và số tiền hoàn (best-effort)
            if (notificationService != null) {
                String note = actorStaffBranchId != null
                        ? "Hủy tại quầy (hoàn " + refundRatePercent + "% theo chính sách bậc thang)"
                        : "Hủy theo yêu cầu duyệt hoàn (hoàn " + refundRatePercent + "% theo chính sách)";
                notificationService.notifyCancelledOrRefund(conn, ticket.userId(),
                        com.cinema.model.notification.Notification.TYPE_TICKET_CANCELLED,
                        ticket.ticketCode(),
                        note,
                        refundAmount);
            }

            logger.info("Cancelled ticket " + ticketId + " refund " + refundAmount
                    + " (" + refundRatePercent + "%)");
            // Req 15.3 — audit hủy vé + hoàn tiền bậc thang (actor = customer hoặc staff)
            Long cancelActor = actorStaffBranchId != null ? null : ticket.userId();
            audit.record(conn, cancelActor, "CANCEL_TICKET", "TICKET", ticketId,
                    "{\"status\":\"CONFIRMED\"}",
                    "{\"status\":\"CANCELLED\",\"refundAmount\":" + refundAmount
                            + ",\"refundRate\":" + refundRatePercent + "}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return reloadWithSeats(conn, ticketId);
        });
    }

    /** Hủy hold đang hoạt động của Customer và giải phóng toàn bộ ghế thuộc hold đó. */
    public void releaseHold(long showtimeId, List<Long> seatIds, long customerId) throws Exception {
        releaseHold(showtimeId, seatIds, customerId, null);
    }

    public void releaseHold(long showtimeId, List<Long> seatIds, long customerId,
                            Long requestedHoldId) throws Exception {
        expireElapsed();
        tx.execute(conn -> {
            Long holdId = requestedHoldId;
            if (holdId == null) {
                for (Long seatId : new TreeSet<>(seatIds)) {
                    SeatHoldDAO.SeatRow seat = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                    if (seat != null && "HOLD".equals(seat.status) && seat.holdId != null) {
                        holdId = seat.holdId;
                        break;
                    }
                }
            }
            if (holdId == null) {
                throw new ServiceException.Conflict("Ghế không còn được giữ");
            }
            SeatHoldDAO.HoldRow hold = seatHoldDao.lockHold(conn, holdId);
            if (hold == null || !Long.valueOf(customerId).equals(hold.userId)
                    || !"ACTIVE".equals(hold.status) || hold.showtimeId != showtimeId) {
                throw new ServiceException.Forbidden("Bạn không có quyền hủy chỗ này");
            }
            List<Long> heldSeatIds = seatHoldDao.findSeatIdsOfHold(conn, holdId);
            for (Long seatId : new TreeSet<>(seatIds)) {
                SeatHoldDAO.SeatRow seat = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                if (seat == null || !Long.valueOf(holdId).equals(seat.holdId)) {
                    throw new ServiceException.Conflict("Một ghế không còn thuộc lượt giữ này");
                }
            }
            for (Long seatId : heldSeatIds) {
                seatHoldDao.releaseSeat(conn, showtimeId, seatId);
            }
            seatHoldDao.updateHoldStatus(conn, holdId, "CANCELLED", "ACTIVE");
            return null;
        });
    }

    /**
     * Soát vé một lần tại đúng chi nhánh (Req 12.1-12.6): vé tồn tại, CONFIRMED,
     * đúng branch, showtime chưa kết thúc; chuyển USED một lần.
     */
    public Ticket validateTicket(String ticketCode, long validatorStaffId, long validatorBranchId) throws Exception {
        if (ticketCode == null || ticketCode.isBlank()) {
            throw new ServiceException.Validation("Mã vé là bắt buộc");
        }
        if (validatorBranchId <= 0) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        expireElapsed();

        return tx.execute(conn -> {
            // Req 12.4 — lock vé; hai lần soát đồng thời đúng một thắng
            Ticket ticket = ticketDao.findByCodeLocked(conn, ticketCode.trim().toUpperCase())
                    .orElseThrow(() -> new ServiceException.NotFound("Mã vé không tồn tại"));

            // Req 12.5 — sai chi nhánh
            if (ticket.branchId() != validatorBranchId) {
                throw new ServiceException.Forbidden(
                        "Vé thuộc chi nhánh khác — không thể soát tại chi nhánh này");
            }
            // Req 12.2 — pending/cancelled/used
            switch (ticket.status()) {
                case Ticket.STATUS_PENDING -> throw new ServiceException.BusinessRule(
                        "TICKET_PENDING", "Vé chưa thanh toán — không thể soát vé");
                case Ticket.STATUS_CANCELLED -> throw new ServiceException.BusinessRule(
                        "TICKET_CANCELLED", "Vé đã bị hủy — không thể soát vé");
                case Ticket.STATUS_USED -> throw new ServiceException.Conflict(
                        "Vé đã được sử dụng trước đó");
                default -> { /* CONFIRMED — hợp lệ */ }
            }
            // Req 12.6 — showtime đã kết thúc
            Showtime showtime = showtimeService.requireBookableForCancel(ticket.showtimeId());
            if (Showtime.STATUS_CANCELLED.equals(showtime.status())) {
                throw new ServiceException.BusinessRule("SHOWTIME_CANCELLED",
                        "Suất chiếu đã bị hủy — không thể soát vé");
            }
            LocalDateTime now = LocalDateTime.now(BUSINESS_TZ);
            if (showtime.endTime() != null && now.isAfter(showtime.endTime())) {
                throw new ServiceException.BusinessRule("SHOWTIME_ENDED",
                        "Suất chiếu đã kết thúc — không thể soát vé");
            }
            // Req 12.7 — chỉ soát trong cửa sổ ±30 phút quanh showtime.startTime.
            // Tránh nhân viên quét sớm quá (lỗi thao tác) và chặn scan sau khi đã hết giờ chiếu.
            if (showtime.startTime() != null && isTooEarlyForCheckIn(now, showtime.startTime())) {
                throw new ServiceException.BusinessRule("TOO_EARLY",
                        "Suất chiếu chưa bắt đầu — chỉ soát trước giờ chiếu tối đa 30 phút");
            }

            if (!ticketDao.useGuarded(conn, ticket.id(), validatorStaffId)) {
                throw new ServiceException.Conflict(
                        "Vé vừa được soát bởi một yêu cầu khác");
            }

            logger.info("Ticket " + ticket.ticketCode() + " validated by staff "
                    + validatorStaffId);
            // Req 15.3 — audit soát vé (actor = staff soát)
            audit.record(conn, validatorStaffId, "VALIDATE_TICKET", "TICKET", ticket.id(),
                    "{\"status\":\"CONFIRMED\"}", "{\"status\":\"USED\"}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return reloadWithSeats(conn, ticket.id());
        });
    }

    /**
     * Quét dọn hold/ticket PENDING hết hạn (Req 7.4, 8.6, 9.4) — idempotent,
     * lazy gọi ở đầu luồng booking; ExpiryScheduler (task 11.1) gọi định kỳ 30s.
     */
    public void expireElapsed() {
        try (Connection conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                seatHoldDao.expireElapsedHolds(conn);
                for (TicketDAO.PointsRedemption redemption : ticketDao.findExpiredPendingRedemptions(conn)) {
                    if (redemption.redemptionKey() == null) {
                        throw new ServiceException.Conflict("Thiếu mã đối soát điểm của vé hết hạn");
                    }
                    loyaltyService.refundPoints(conn, redemption.userId(), redemption.pointsUsed(),
                            "TICKET_REDEMPTION_EXPIRY", redemption.ticketId(),
                            redemption.redemptionKey() + "-EXPIRED");
                    if (!ticketDao.updatePointsRedemption(conn, redemption.ticketId(), 0, 0, null)) {
                        throw new ServiceException.Conflict("Không hoàn được điểm của vé hết hạn");
                    }
                }
                ticketDao.cancelExpiredPendingTickets(conn);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                logger.warning("expireElapsed failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            logger.warning("expireElapsed connection failed: " + e.getMessage());
        }
    }

    // ---- helpers ----

    /** Ghế HOLD nhưng hold_expires_at đã qua → coi như AVAILABLE (Req 8.4 lazy expiry). */
    private static boolean isHoldExpired(LocalDateTime holdExpiresAt) {
        return holdExpiresAt != null && LocalDateTime.now(ZoneOffset.UTC).isAfter(holdExpiresAt);
    }

    static boolean isTooEarlyForCheckIn(LocalDateTime now, LocalDateTime showtimeStart) {
        return now.isBefore(showtimeStart.minusMinutes(30));
    }

    private static boolean isHoldExpired(SeatHoldDAO.SeatRow row) {
        return "HOLD".equals(row.status) && isHoldExpired(row.holdExpiresAt);
    }

    /** Refund policy compares UTC instants with showtimes stored as Vietnam local time. */
    static long refundRatePercent(Instant now, LocalDateTime showtimeStart) {
        LocalDateTime businessNow = LocalDateTime.ofInstant(now, BUSINESS_TZ);
        if (showtimeStart == null) return 0;
        Duration until = Duration.between(businessNow, showtimeStart);
        if (until.isNegative()) return 0; // đã chiếu
        if (until.toHours() >= 24) return 100;
        if (until.toHours() >= 2) return 50;
        return 0;
    }

    /** Cộng điểm cho khách sau booking confirm (Req 17.2) — caller giữ transaction. */
    private int earnPoints(Connection conn, long userId, long amountPaid, long ticketId) throws Exception {
        return loyaltyService.addPointsForPurchase(conn, userId, amountPaid, "TICKET", ticketId);
    }

    private long applyTicketPointRedemption(Connection conn, Ticket ticket, Long userId,
                                             long payable, int requestedPoints) throws Exception {
        if (ticket.pointsUsed() > 0) {
            if (userId == null || ticket.pointsRedemptionKey() == null) {
                throw new ServiceException.Conflict("Không thể đối soát điểm đã giữ cho vé");
            }
            loyaltyService.refundPoints(conn, userId, ticket.pointsUsed(),
                    "TICKET_REDEMPTION_RELEASE", ticket.id(),
                    ticket.pointsRedemptionKey() + "-RELEASE");
            if (!ticketDao.updatePointsRedemption(conn, ticket.id(), 0, 0, null)) {
                throw new ServiceException.Conflict("Không thể giải phóng điểm đã giữ cho vé");
            }
            ticket.setPointsUsed(0);
            ticket.setPointsDiscount(0);
            ticket.setPointsRedemptionKey(null);
        }
        if (requestedPoints < -1) {
            throw new ServiceException.Validation("Số điểm sử dụng không hợp lệ");
        }
        if (requestedPoints == 0) return payable;
        if (userId == null) {
            if (requestedPoints == -1) return payable;
            throw new ServiceException.Forbidden("Cần đăng nhập để sử dụng điểm");
        }
        int redemptionRate = loyaltyService.getRedemptionRate();
        int maxRedeemablePoints = loyaltyService.calculateMaxRedeemablePoints(conn, userId, payable);
        int pointsUsed = requestedPoints == -1 ? maxRedeemablePoints : requestedPoints;
        if (pointsUsed > maxRedeemablePoints) {
            throw new ServiceException.Validation("Số điểm sử dụng vượt quá số dư hiện có");
        }
        long pointsDiscount = (long) pointsUsed * redemptionRate;
        if (pointsUsed <= 0 || pointsDiscount <= 0) return payable;

        String redemptionKey = "TICKET-" + ticket.id() + "-" + UUID.randomUUID();
        loyaltyService.redeemPoints(conn, userId, pointsUsed, "TICKET", ticket.id(), redemptionKey);
        if (!ticketDao.updatePointsRedemption(
                conn, ticket.id(), pointsUsed, pointsDiscount, redemptionKey)) {
            throw new ServiceException.Conflict("Không thể ghi nhận điểm đã sử dụng cho vé");
        }
        ticket.setPointsUsed(pointsUsed);
        ticket.setPointsDiscount(pointsDiscount);
        ticket.setPointsRedemptionKey(redemptionKey);
        return Math.max(0, payable - pointsDiscount);
    }

    /** Ticket id gắn với hold — tra qua cột ticket.hold_id (vé tạo cùng lúc hold, 1:1). */
    private long ticketIdOfHold(Connection conn, long holdId) throws SQLException {
        String sql = "SELECT id FROM dbo.ticket WHERE hold_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound("Không tìm thấy vé của bản ghi giữ ghế");
                }
                return rs.getLong("id");
            }
        }
    }

    /**
     * Đọc lại vé ngay trong transaction hiện tại.
     *
     * <p>Không mở connection mới ở đây: các bước booking đang giữ row lock
     * trên vé và ghế. Connection mới sẽ chờ chính transaction đang chờ nó
     * đọc dữ liệu, làm request bị treo.</p>
     */
    private Ticket reloadWithSeats(Connection conn, long ticketId) throws SQLException {
        Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));
        ticket.setSeats(ticketDao.findSeats(conn, ticketId));
        return ticket;
    }

    /** Mã vé duy nhất: TCK- + 8 ký tự hex ngẫu nhiên (UNIQUE constraint chốt chặn). */
    private static String generateTicketCode() {
        return "TCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** Thông báo lỗi an toàn cho luồng hold (không lộ chi tiết kỹ thuật). */
    private static String safeMessage(Exception e, String fallback) {
        return e instanceof ServiceException ? e.getMessage() : fallback;
    }
}
