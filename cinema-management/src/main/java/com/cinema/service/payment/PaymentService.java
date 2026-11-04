package com.cinema.service.payment;

import com.cinema.common.ServiceException;
import com.cinema.dao.booking.SeatHoldDAO;
import com.cinema.dao.booking.TicketDAO;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.concession.ConcessionOrderDAO;
import com.cinema.dao.concession.InventoryDAO;
import com.cinema.dao.concession.ProductDAO;
import com.cinema.dao.loyalty.LoyaltyDAO;
import com.cinema.dao.payment.PaymentDAO;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.notification.Notification;
import com.cinema.dao.notification.NotificationDAO;
import com.cinema.service.notification.NotificationService;
import com.cinema.model.booking.Ticket;
import com.cinema.model.branch.Branch;
import com.cinema.model.concession.ConcessionOrder;
import com.cinema.model.payment.Payment;
import com.cinema.model.payment.VnPayUtil;
import com.cinema.service.audit.AuditService;
import com.cinema.service.concession.ConcessionService;
import com.cinema.service.loyalty.LoyaltyService;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Điều phối thanh toán 3 phương thức với idempotency (Req 9.1-9.8).
 *
 * <p>Business rules:
 * <ul>
 *   <li>CASH: payment PENDING chờ Branch Staff thuộc chi nhánh của vé xác nhận thu
 *       tiền → SUCCESS (Req 9.1).</li>
 *   <li>MOCK_GATEWAY: tạo phiên + redirect; callback HMAC success → vé CONFIRMED,
 *       failure → payment FAILED giữ vé PENDING chờ hết hạn hold (Req 9.2-9.4).</li>
 *   <li>BANK_TRANSFER: PENDING chờ staff đối soát; approve → SUCCESS, reject → FAILED
 *       và vé CANCELLED (Req 9.5, 9.6).</li>
 *   <li>Callback/confirm lần hai cho cùng vé đã xử lý → 409 ALREADY_PROCESSED (Req 9.7, 9.8):
 *       row lock payment + guarded UPDATE WHERE status='PENDING' đảm bảo đúng một lần
 *       chuyển trạng thái khi hai yêu cầu đến đồng thời.</li>
 * </ul>
 */
public class PaymentService {
    private static final Logger logger = Logger.getLogger(PaymentService.class.getName());
    /** Mã lỗi idempotency (design.md Error Categories: 409 ALREADY_PROCESSED). */
    public static final String ALREADY_PROCESSED = "ALREADY_PROCESSED";
    /** Req 15.3/11.2 — audit chuyển trạng thái thanh toán. */
    private final com.cinema.service.audit.AuditService audit = new com.cinema.service.audit.AuditService();

    private final PaymentDAO paymentDao;
    private final TicketDAO ticketDao = new TicketDAO();
    private final SeatHoldDAO seatHoldDao = new SeatHoldDAO();
    private final ConcessionOrderDAO concessionOrderDao = new ConcessionOrderDAO();
    private final LoyaltyService loyaltyService;
    private final Map<String, PaymentProvider> providers = new HashMap<>();
    private final TransactionTemplate tx = new TransactionTemplate();
    private final NotificationService notificationService = new NotificationService(new NotificationDAO());
    private ConcessionService concessionService;

    public void setConcessionService(ConcessionService concessionService) {
        this.concessionService = concessionService;
    }

    private ConcessionService getConcessionService() {
        if (this.concessionService == null) {
            this.concessionService = new ConcessionService(
                    new ProductDAO(), new InventoryDAO(), concessionOrderDao,
                    new BranchDAO(), null, loyaltyService);
        }
        return this.concessionService;
    }

    public PaymentService(PaymentDAO paymentDao, MockGatewayProvider mockGateway, String bankAccountInfo) {
        this(paymentDao, mockGateway, bankAccountInfo, null);
    }

    public PaymentService(PaymentDAO paymentDao, MockGatewayProvider mockGateway, String bankAccountInfo,
                          VnPayProvider vnPay) {
        this(paymentDao, mockGateway, bankAccountInfo, vnPay,
                new LoyaltyService(new com.cinema.dao.loyalty.LoyaltyDAO()));
    }

    public PaymentService(PaymentDAO paymentDao, MockGatewayProvider mockGateway, String bankAccountInfo,
                          VnPayProvider vnPay, LoyaltyService loyaltyService) {
        this.paymentDao = paymentDao;
        this.loyaltyService = loyaltyService;
        register(new CashProvider());
        register(mockGateway);
        register(new BankTransferProvider(bankAccountInfo));
        if (vnPay != null) register(vnPay);
    }

    private void register(PaymentProvider provider) {
        providers.put(provider.method(), provider);
    }

    /**
     * Tạo phiên thanh toán cho một vé (Req 9.1, 9.2, 9.5): ghi payment PENDING theo
     * method, gọi provider charge và trả kết quả (redirect URL cho mock gateway,
     * thông tin chuyển khoản cho bank transfer).
     */
    public PaymentOutcome createSession(long ticketId, String method, long amount, Long userId) throws Exception {
        PaymentProvider provider = providers.get(normalizeMethod(method));
        if (provider == null) {
            throw new ServiceException.Validation(
                    "Phương thức thanh toán phải là CASH, MOCK_GATEWAY hoặc BANK_TRANSFER");
        }
        if (amount <= 0) {
            throw new ServiceException.Validation("Số tiền thanh toán phải là số dương");
        }
        requireTicketPending(ticketId);

        Payment payment = new Payment();
        payment.setTicketId(ticketId);
        payment.setMethod(provider.method());
        payment.setAmount(amount);
        payment.setStatus(Payment.STATUS_PENDING);
        payment.setIdempotencyKey("TICKET-" + ticketId + "-" + UUID.randomUUID());
        paymentDao.insert(payment);

        PaymentProvider.PaymentResult result = provider.charge(
                new PaymentProvider.PaymentSession(payment.id(), ticketId, provider.method(),
                        amount, payment.idempotencyKey(), userId));

        if (result.status().equals(Payment.STATUS_FAILED)) {
            throw new ServiceException.BusinessRule("PAYMENT_FAILED",
                    "Thanh toán thất bại ngay khi khởi tạo — vui lòng thử lại");
        }
        payment.setStatus(result.status());
        payment.setProviderReference(result.providerReference());

        logger.info("Created payment " + payment.id() + " (" + provider.method()
                + ", " + amount + "đ) for ticket " + ticketId);
        return new PaymentOutcome(payment, result.redirectUrl());
    }

    /**
     * Callback cổng mock (Req 9.3, 9.4, 9.7): verify HMAC → success: vé CONFIRMED;
     * failure: payment FAILED (vé giữ PENDING chờ hết hạn hold — scheduler task 11.1 hủy).
     * Callback trùng cho payment đã xử lý → 409 ALREADY_PROCESSED.
     */
    public void handleMockCallback(long paymentId, String hmac, boolean success,
                                   MockGatewayProvider gateway) throws Exception {        tx.executeVoid(conn -> {
            Payment payment = lockPayment(conn, paymentId);
            if (!Payment.METHOD_MOCK_GATEWAY.equals(payment.method())) {
                throw new ServiceException.Validation("Callback chỉ áp dụng cho thanh toán cổng mock");
            }
            if (!gateway.verifyCallback(paymentId, payment.amount(), hmac)) {
                throw new ServiceException.Forbidden("HMAC callback không hợp lệ");
            }
            boolean reviewRequired = success && payment.ticketId() != null
                    && !confirmTicket(conn, payment.ticketId(), payment.amount());
            String resultStatus = reviewRequired ? Payment.STATUS_REVIEW_REQUIRED
                    : success ? Payment.STATUS_SUCCESS : Payment.STATUS_FAILED;
            // Req 9.7 — lần xử lý thứ hai trượt guard status='PENDING'
            boolean transitioned = markPayment(conn, paymentId, resultStatus, hmac, hmac);
            if (!transitioned) {
                throw alreadyProcessed(payment);
            }
            if (reviewRequired) {
                logger.warning("Payment " + paymentId
                        + " succeeded after ticket hold expired; manual review required");
            }
            logger.info("Mock callback for payment " + paymentId + " → " + resultStatus);
            // Req 15.3 — audit callback cổng mock (actor null: hệ thống/gateway gọi)
            audit.record(conn, null, reviewRequired ? "PAYMENT_REVIEW_REQUIRED"
                            : success ? "MOCK_CALLBACK_SUCCESS" : "MOCK_CALLBACK_FAILED",
                    "PAYMENT", paymentId, "{\"status\":\"PENDING\"}",
                    "{\"status\":\"" + resultStatus
                            + "\",\"method\":\"MOCK_GATEWAY\",\"amount\":" + payment.amount() + "}",
                    com.cinema.service.audit.AuditService.SUCCESS);
        });
    }

    /**
     * Xử lý callback VNPay (dùng chung cho Return URL & IPN).
     *
     * <p>Quy tắc bảo mật:
     * <ol>
     *   <li>Verify checksum trước khi đọc responseCode.</li>
     *   <li>Tìm payment bằng {@code vnp_TxnRef} (chính là idempotency_key).</li>
     *   <li>So khớp {@code vnp_Amount} với amount DB (loại trừ giả mạo).</li>
     *   <li>Idempotent: nếu payment đã ở trạng thái cuối (SUCCESS/FAILED/REFUNDED) thì trả về
     *       kết quả đã biết, KHÔNG tạo ticket duplicate.</li>
     *   <li>Nếu checksum sai → throw 403, không cập nhật DB.</li>
     * </ol>
     *
     * @param vnpParams toàn bộ tham số vnp_* từ request (đã loại bỏ giá trị rỗng)
     * @param source "RETURN" hoặc "IPN" cho log/audit
     */
    public Payment handleVnPayCallback(Map<String, String> vnpParams, String source) throws Exception {
        return tx.execute(conn -> {
            String txnRef = vnpParams.get("vnp_TxnRef");
            String responseCode = vnpParams.get("vnp_ResponseCode");
            String vnpAmount = vnpParams.get("vnp_Amount");
            String vnpTransactionNo = vnpParams.get("vnp_TransactionNo");
            String vnpBankCode = vnpParams.get("vnp_BankCode");
            String vnpPayDate = vnpParams.get("vnp_PayDate");

            if (txnRef == null || txnRef.isBlank()) {
                throw new ServiceException.Validation("Thiếu vnp_TxnRef");
            }
            if (responseCode == null) {
                throw new ServiceException.Validation("Thiếu vnp_ResponseCode");
            }
            if (vnpAmount == null) {
                throw new ServiceException.Validation("Thiếu vnp_Amount");
            }

            Payment payment = paymentDao.findByIdempotencyKeyLocked(conn, txnRef)
                    .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy giao dịch VNPAY"));
            if (!VnPayProvider.METHOD.equals(payment.method())) {
                throw new ServiceException.Validation("Giao dịch không phải VNPAY");
            }

            // Idempotency — trả về trạng thái hiện tại, không xử lý lại.
            if (Payment.STATUS_PENDING.equals(payment.status())) {
                long amountFromVnp = VnPayUtil.fromVnpAmount(vnpAmount);
                if (amountFromVnp != payment.amount()) {
                    logger.warning("[VNPAY] " + source + " amount mismatch txnRef="
                            + txnRef + " db=" + payment.amount() + " vnp=" + amountFromVnp);
                    throw new ServiceException.Validation("Số tiền VNPay không khớp DB");
                }

                boolean success = "00".equals(responseCode);
                boolean reviewRequired = success && payment.ticketId() != null
                        && !confirmTicket(conn, payment.ticketId(), payment.amount());
                String resultStatus = reviewRequired ? Payment.STATUS_REVIEW_REQUIRED
                        : success ? Payment.STATUS_SUCCESS : Payment.STATUS_FAILED;
                boolean transitioned = markPayment(conn, payment.id(), resultStatus,
                        vnpTransactionNo == null ? ("VNPAY-" + payment.id()) : vnpTransactionNo,
                        vnpParams.get("vnp_SecureHash"));
                if (transitioned) {
                    payment.setStatus(resultStatus);
                    if (reviewRequired) {
                        logger.warning("[VNPAY] payment " + payment.id()
                                + " succeeded after ticket hold expired; manual review required");
                    }
                    logger.info("[VNPAY] " + source + " payment " + payment.id()
                            + " → " + resultStatus
                            + " txnRef=" + txnRef
                            + " vnpTxnNo=" + vnpTransactionNo
                            + " payDate=" + vnpPayDate
                            + " bank=" + vnpBankCode);
                    audit.record(conn, null, reviewRequired ? "PAYMENT_REVIEW_REQUIRED"
                                    : success ? "VNPAY_CALLBACK_SUCCESS" : "VNPAY_CALLBACK_FAILED",
                            "PAYMENT", payment.id(),
                            "{\"status\":\"PENDING\"}",
                            "{\"status\":\"" + resultStatus
                                    + "\",\"method\":\"VNPAY\",\"amount\":" + payment.amount()
                                    + ",\"txnRef\":\"" + txnRef + "\"}",
                            com.cinema.service.audit.AuditService.SUCCESS);

                    if (success && !reviewRequired && payment.ticketId() != null) {
                        try {
                            ticketDao.findById(payment.ticketId()).ifPresent(ticket -> {
                                TicketDAO.ConfirmationDetails details = null;
                                try {
                                    details = ticketDao.findConfirmationDetails(conn, ticket.id()).orElse(null);
                                } catch (Exception ignored) {}
                                if (ticket.userId() != null && ticket.userId() > 0) {
                                    notificationService.notifyTicketConfirmed(conn, ticket.userId(),
                                            ticket.ticketCode(), details, payment.amount());
                                }
                                notificationService.notifyBranchStaffAndManagers(ticket.branchId(),
                                        Notification.TYPE_TICKET_CONFIRMED,
                                        "Vé xem phim mới",
                                        "Mã vé " + ticket.ticketCode() + " · Tổng tiền "
                                                + String.format("%,d", payment.amount()).replace(',', '.') + " đ. Khách hàng đã thanh toán thành công.");
                            });
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Không gửi được thông báo vé confirmed cho txnRef " + txnRef, e);
                        }
                    }
                } else {
                    // guard đã chặn — trạng thái đã được xử lý bởi callback khác trước đó
                    payment.setStatus(payment.status());
                }
            } else {
                logger.info("[VNPAY] " + source + " payment " + payment.id()
                        + " already " + payment.status() + " txnRef=" + txnRef);
            }
            return payment;
        });
    }

    /** Throw nếu checksum không hợp lệ — dùng chung cho Return/IPN. */
    public void ensureChecksumValid(Map<String, String> vnpParams, VnPayProvider provider, String source) {
        if (!provider.verify(vnpParams)) {
            logger.warning("[VNPAY] " + source + " invalid checksum txnRef="
                    + vnpParams.get("vnp_TxnRef"));
            throw new ServiceException.Forbidden("Checksum VNPay không hợp lệ");
        }
    }

    /**
     * Branch Staff xác nhận thu tiền mặt (Req 9.1, 9.7, 9.8): PENDING → SUCCESS,
     * vé CONFIRMED. Xác nhận lần hai → 409 ALREADY_PROCESSED.
     *
     * @param staffBranchId chi nhánh của staff — phải khớp chi nhánh của vé (Req 9.1)
     */
    public Payment confirmCash(long ticketId, long staffId, long staffBranchId) throws Exception {
        return tx.execute(conn -> {
            requireTicketBranch(conn, ticketId, staffBranchId);
            Payment payment = lockPendingPaymentForTicket(conn, ticketId, Payment.METHOD_CASH);

            boolean ticketConfirmed = confirmTicket(conn, ticketId, payment.amount());
            String resultStatus = ticketConfirmed ? Payment.STATUS_SUCCESS
                    : Payment.STATUS_REVIEW_REQUIRED;
            boolean transitioned = markPayment(conn, payment.id(), resultStatus,
                    "CASH-STAFF-" + staffId, null);
            if (!transitioned) {
                throw alreadyProcessed(payment);
            }
            // Req 22.2 — gán giao dịch tiền mặt vào ca đang mở của staff để đối soát
            attachToOpenShift(conn, payment.id(), staffId);

            payment.setStatus(resultStatus);
            if (!ticketConfirmed) {
                logger.warning("Cash payment " + payment.id()
                        + " collected after ticket hold expired; manual review required");
            }
            logger.info("Cash payment " + payment.id() + " confirmed by staff " + staffId
                    + " → " + resultStatus);
            audit.record(conn, staffId, ticketConfirmed ? "CONFIRM_CASH" : "PAYMENT_REVIEW_REQUIRED",
                    "PAYMENT", payment.id(),
                    "{\"status\":\"PENDING\"}",
                    "{\"status\":\"" + resultStatus + "\",\"method\":\"CASH\",\"amount\":" + payment.amount()
                            + ",\"ticketId\":" + ticketId + "}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return payment;
        });
    }

    /**
     * Branch Staff đối soát chuyển khoản (Req 9.5, 9.6, 9.7):
     * approved → SUCCESS + vé CONFIRMED; rejected → FAILED + vé CANCELLED.
     * Lần xác nhận thứ hai → 409 ALREADY_PROCESSED.
     */
    public Payment confirmBankTransfer(long ticketId, long staffId, long staffBranchId,
                                       boolean approved) throws Exception {
        return tx.execute(conn -> {
            requireTicketBranch(conn, ticketId, staffBranchId);
            Payment payment = lockPendingPaymentForTicket(conn, ticketId, Payment.METHOD_BANK_TRANSFER);

            boolean ticketConfirmed = approved && confirmTicket(conn, ticketId, payment.amount());
            String resultStatus = !approved ? Payment.STATUS_FAILED
                    : ticketConfirmed ? Payment.STATUS_SUCCESS : Payment.STATUS_REVIEW_REQUIRED;
            boolean transitioned = markPayment(conn, payment.id(), resultStatus,
                    "BANK-STAFF-" + staffId, null);
            if (!transitioned) {
                throw alreadyProcessed(payment);
            }
            if (!approved) {
                cancelTicket(conn, ticketId); // Req 9.6 — reject thì hủy vé, ghế giải phóng
            }

            payment.setStatus(resultStatus);
            if (approved && !ticketConfirmed) {
                logger.warning("Bank transfer payment " + payment.id()
                        + " approved after ticket hold expired; manual review required");
            }
            logger.info("Bank transfer payment " + payment.id() + " "
                    + (approved ? "approved" : "rejected") + " by staff " + staffId
                    + " → " + resultStatus);
            audit.record(conn, staffId, approved && !ticketConfirmed ? "PAYMENT_REVIEW_REQUIRED"
                            : approved ? "APPROVE_BANK_TRANSFER" : "REJECT_BANK_TRANSFER",
                    "PAYMENT", payment.id(), "{\"status\":\"PENDING\"}",
                    "{\"status\":\"" + resultStatus
                            + "\",\"method\":\"BANK_TRANSFER\",\"amount\":" + payment.amount()
                            + ",\"ticketId\":" + ticketId + "}",
                    com.cinema.service.audit.AuditService.SUCCESS);
            return payment;
        });
    }

    // ---- helpers (mọi thao tác lock/update đều trong transaction của caller) ----

    private Payment insertPayment(Connection conn, long ticketId, String method, long amount) throws Exception {
        String sql = """
            INSERT INTO dbo.payment (ticket_id, method, amount, status, idempotency_key)
            VALUES (?, ?, ?, 'PENDING', ?)
            """;
        String key = "TICKET-" + ticketId + "-" + UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ticketId);
            ps.setString(2, method);
            ps.setLong(3, amount);
            ps.setString(4, key);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new ServiceException.Conflict("Không tạo được giao dịch thanh toán");
                Payment payment = new Payment();
                payment.setId(rs.getLong(1));
                payment.setTicketId(ticketId);
                payment.setMethod(method);
                payment.setAmount(amount);
                payment.setStatus(Payment.STATUS_PENDING);
                payment.setIdempotencyKey(key);
                return payment;
            }
        }
    }

    private Payment lockPayment(Connection conn, long paymentId) {
        return paymentDaoFindLocked(conn, paymentId)
                .orElseThrow(() -> new ServiceException.NotFound("Giao dịch thanh toán không tồn tại"));
    }

    private java.util.Optional<Payment> paymentDaoFindLocked(Connection conn, long paymentId) {
        try {
            return paymentDao.findByIdLocked(conn, paymentId);
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được giao dịch thanh toán", e);
        }
    }

    private Payment lockPendingPaymentForTicket(Connection conn, long ticketId, String method) throws Exception {
        String sql = """
            SELECT TOP 1 id FROM dbo.payment WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
            WHERE ticket_id = ? AND method = ?
            ORDER BY id DESC
            """;
        long paymentId;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            ps.setString(2, method);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound(
                            "Chưa có giao dịch thanh toán " + method + " cho vé này");
                }
                paymentId = rs.getLong("id");
            }
        }
        return lockPayment(conn, paymentId);
    }

    /** Guarded update — chỉ chuyển khi còn PENDING (Req 9.7, 9.8). */
    private boolean markPayment(Connection conn, long paymentId, String status,
                                String providerReference, String hmac) throws Exception {
        return paymentDao.updateStatusGuarded(conn, paymentId, status, providerReference, hmac);
    }

    /**
     * Req 22.2 — gán giao dịch tiền mặt vào ca đang mở của staff để tổng hợp đối soát.
     * Staff không có ca mở → shift_id giữ NULL, giao dịch không vào ca nào.
     */
    /** Confirm only while this payment's hold and every reserved seat are still active. */
    private boolean confirmTicket(Connection conn, long ticketId, long paidAmount) throws Exception {
        Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));
        if (!Ticket.STATUS_PENDING.equals(ticket.status()) || ticket.holdId() == null
                || ticket.userId() == null) {
            return false;
        }

        SeatHoldDAO.HoldRow hold = seatHoldDao.lockHold(conn, ticket.holdId());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (hold == null || !"ACTIVE".equals(hold.status)
                || hold.showtimeId != ticket.showtimeId()
                || !ticket.userId().equals(hold.userId)
                || hold.expiresAt == null || !now.isBefore(hold.expiresAt)) {
            return false;
        }

        java.util.List<Ticket.TicketSeat> seats = ticketDao.findSeats(conn, ticketId).stream()
                .sorted(java.util.Comparator.comparingLong(Ticket.TicketSeat::seatId))
                .toList();
        if (seats.isEmpty()) {
            return false;
        }
        for (Ticket.TicketSeat seat : seats) {
            SeatHoldDAO.SeatRow heldSeat = seatHoldDao.lockSeat(
                    conn, ticket.showtimeId(), seat.seatId());
            if (heldSeat == null || !"HOLD".equals(heldSeat.status)
                    || !ticket.holdId().equals(heldSeat.holdId)
                    || heldSeat.holdExpiresAt == null || !now.isBefore(heldSeat.holdExpiresAt)) {
                return false;
            }
        }

        if (!ticketDao.confirmGuarded(conn, ticketId, paidAmount,
                ticket.voucherCode(), ticket.pointsEarned())) {
            return false;
        }
        for (Ticket.TicketSeat seat : seats) {
            seatHoldDao.markSeatSold(conn, ticket.showtimeId(), seat.seatId());
        }
        if (!seatHoldDao.updateHoldStatus(conn, ticket.holdId(), "CONFIRMED", "ACTIVE")) {
            throw new ServiceException.Conflict("Giữ ghế vừa được xử lý bởi một yêu cầu khác");
        }
        return true;
    }

    /** Confirms a zero-balance ticket atomically without creating a payment session. */
    public void confirmZeroAmountTicket(Connection conn, long ticketId, long amount) throws Exception {
        if (amount != 0) {
            throw new ServiceException.Validation("Chỉ xác nhận trực tiếp khi số tiền còn lại bằng 0");
        }
        if (!confirmTicket(conn, ticketId, 0)) {
            throw new ServiceException.Conflict("Không thể xác nhận vé có số tiền thanh toán bằng 0");
        }
    }

    private void attachToOpenShift(Connection conn, long paymentId, long staffId) throws Exception {
        String sql = """
            UPDATE dbo.payment
            SET shift_id = (SELECT TOP 1 id FROM dbo.shift WHERE staff_id = ? AND status = 'OPEN'),
                staff_id = ?
            WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, staffId);
            ps.setLong(2, staffId);
            ps.setLong(3, paymentId);
            ps.executeUpdate();
        }
    }

    /** Vé PENDING → CANCELLED khi bank transfer bị reject (Req 9.6). */
    private void cancelTicket(Connection conn, long ticketId) throws Exception {
        Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));
        if (ticket.pointsUsed() > 0 && ticket.userId() != null) {
            if (ticket.pointsRedemptionKey() == null) {
                throw new ServiceException.Conflict("Thiếu mã đối soát điểm đã sử dụng");
            }
            loyaltyService.refundPoints(conn, ticket.userId(), ticket.pointsUsed(),
                    "TICKET_REDEMPTION_REJECT", ticket.id(),
                    ticket.pointsRedemptionKey() + "-REJECTED");
            if (!ticketDao.updatePointsRedemption(conn, ticketId, 0, 0, null)) {
                throw new ServiceException.Conflict("Không hoàn được điểm của vé bị từ chối");
            }
        }
        String sql = "UPDATE dbo.ticket SET status = 'CANCELLED', version = version + 1 WHERE id = ? AND status = 'PENDING'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            ps.executeUpdate();
        }
    }

    private void requireTicketPending(long ticketId) throws Exception {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM dbo.ticket WHERE id = ?")) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound("Vé không tồn tại");
                }
                if (!"PENDING".equals(rs.getString("status"))) {
                    throw new ServiceException.Conflict(
                            "Vé không ở trạng thái chờ thanh toán (" + rs.getString("status") + ")");
                }
            }
        }
    }

    /** Req 9.1 — staff chỉ xác nhận thanh toán cho vé thuộc chi nhánh của mình. */
    private void requireTicketBranch(Connection conn, long ticketId, long staffBranchId) throws Exception {
        String sql = "SELECT branch_id FROM dbo.ticket WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound("Vé không tồn tại");
                }
                if (rs.getLong("branch_id") != staffBranchId) {
                    throw new ServiceException.Forbidden(
                            "Vi phạm phạm vi chi nhánh: vé thuộc chi nhánh khác");
                }
            }
        }
    }

    private ServiceException alreadyProcessed(Payment payment) {
        return new ServiceException(ALREADY_PROCESSED,
                "Giao dịch thanh toán đã được xử lý trước đó (" + payment.status() + ")", 409);
    }

    /**
     * Xử lý callback VNPay cho đơn F&B (Req 9.x + 21.x).
     *
     * <p>Quy tắc:
     * <ol>
     *   <li>Verify checksum trước khi đọc responseCode.</li>
     *   <li>Idempotent: nếu payment đã ở trạng thái cuối (SUCCESS/FAILED) thì trả về
     *       kết quả đã biết, KHÔNG tạo duplicate.</li>
     *   <li>SUCCESS: cập nhật Payment → SUCCESS, ConcessionOrder → READY_FOR_PICKUP,
     *       trừ pointsUsed nếu có, gửi notification.</li>
     *   <li>FAILED: chỉ cập nhật Payment → FAILED, đơn giữ PENDING (scheduler hủy).</li>
     *   <li>Nếu đơn đã ở trạng thái READY_FOR_PICKUP → return early.</li>
     * </ol>
     *
     * @param vnpParams toàn bộ tham số vnp_* từ request
     * @param source "RETURN" hoặc "IPN" cho log/audit
     * @return Payment đã cập nhật
     */
    public Payment handleFnbPaymentCallback(Map<String, String> vnpParams, String source)
            throws Exception {
        return tx.execute(conn -> {
            String txnRef = vnpParams.get("vnp_TxnRef");
            String responseCode = vnpParams.get("vnp_ResponseCode");
            String vnpAmount = vnpParams.get("vnp_Amount");
            String vnpTransactionNo = vnpParams.get("vnp_TransactionNo");
            String vnpBankCode = vnpParams.get("vnp_BankCode");
            String vnpPayDate = vnpParams.get("vnp_PayDate");

            if (txnRef == null || txnRef.isBlank()) {
                throw new ServiceException.Validation("Thiếu vnp_TxnRef");
            }
            if (responseCode == null) {
                throw new ServiceException.Validation("Thiếu vnp_ResponseCode");
            }
            if (vnpAmount == null) {
                throw new ServiceException.Validation("Thiếu vnp_Amount");
            }

            // Tìm payment bằng txnRef (idempotency_key)
            Payment payment = paymentDao.findByIdempotencyKeyLocked(conn, txnRef)
                    .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy giao dịch VNPAY"));

            // Xác nhận đây là payment F&B
            if (!VnPayProvider.METHOD.equals(payment.method())) {
                throw new ServiceException.Validation("Giao dịch không phải VNPAY cho F&B");
            }
            if (payment.concessionOrderId() == null) {
                throw new ServiceException.Validation("Giao dịch không thuộc đơn F&B");
            }
            long concessionOrderId = payment.concessionOrderId();

            // Idempotency — trả về trạng thái hiện tại, không xử lý lại
            if (!Payment.STATUS_PENDING.equals(payment.status())) {
                logger.info("[VNPAY-FNB] " + source + " payment " + payment.id()
                        + " already " + payment.status() + " txnRef=" + txnRef);
                return payment;
            }

            // VNPay trả về amount đã nhân 100 (vnp_*Amount format); so sánh với
            // payment.amount() (lưu nguyên VND) để phát hiện tampering.
            long amountFromVnp = VnPayUtil.fromVnpAmount(vnpAmount);
            if (amountFromVnp != payment.amount()) {
                logger.warning("[VNPAY-FNB] " + source + " amount mismatch txnRef="
                        + txnRef + " db=" + payment.amount() + " vnp=" + amountFromVnp);
                throw new ServiceException.Validation("Số tiền VNPay không khớp DB");
            }

            boolean success = "00".equals(responseCode);
            boolean transitioned = markPayment(conn, payment.id(),
                    success ? Payment.STATUS_SUCCESS : Payment.STATUS_FAILED,
                    vnpTransactionNo == null ? ("VNPAY-" + payment.id()) : vnpTransactionNo,
                    vnpParams.get("vnp_SecureHash"));

            if (transitioned) {
                if (success) {
                    // Load đơn kèm SELECT ... FOR UPDATE (locked) để chặn 2 callback
                    // VNPay cùng success cho cùng 1 đơn trong cùng thời điểm.
                    ConcessionOrder order = loadConcessionOrderLocked(conn, concessionOrderId);
                    if (order == null) {
                        throw new ServiceException.NotFound("Đơn F&B không tồn tại");
                    }

                    // Nếu đơn đã được mark READY_FOR_PICKUP ở IPN trước, bỏ qua —
                    // VNPay có thể retry IPN nếu response lần đầu timeout.
                    if (!ConcessionOrder.STATUS_PENDING.equals(order.status())) {
                        logger.info("[VNPAY-FNB] Order " + order.orderCode()
                                + " already " + order.status());
                        payment.setStatus(Payment.STATUS_SUCCESS);
                        return payment;
                    }

                    // Trừ tồn kho chi nhánh cho các sản phẩm trong đơn F&B (Req 20.2 / 21.8)
                    java.util.List<ConcessionOrder.OrderLine> lines = concessionOrderDao.findLines(conn, order.id());
                    getConcessionService().deductOrderInventory(conn, order.branchId(), lines);

                    // Cập nhật đơn → READY_FOR_PICKUP
                    if (!updateConcessionOrderToReady(conn, order)) {
                        throw new ServiceException.Conflict("Đơn vừa được xử lý bởi yêu cầu khác");
                    }

                    // Trừ điểm đã dùng (Phase 3: use redeemPoints with idempotency)
                    if (order.pointsUsed() > 0 && order.userId() != null) {
                        loyaltyService.redeemPoints(conn, order.userId(), order.pointsUsed(),
                                "FNB_ORDER", order.id());
                    }

                    // Cộng điểm tích lũy cho khách hàng khi mua F&B online
                    long amountPaid = payment.amount();
                    if (amountPaid > 0 && order.userId() != null) {
                        loyaltyService.addPointsForPurchase(conn, order.userId(), amountPaid,
                                "CONCESSION_ORDER", order.id());
                    }

                    payment.setStatus(Payment.STATUS_SUCCESS);
                    logger.info("[VNPAY-FNB] " + source + " order " + order.orderCode()
                            + " → READY_FOR_PICKUP"
                            + " txnRef=" + txnRef
                            + " vnpTxnNo=" + vnpTransactionNo
                            + " payDate=" + vnpPayDate
                            + " bank=" + vnpBankCode
                            + " pointsUsed=" + order.pointsUsed());

                    audit.record(conn, null, "VNPAY_CALLBACK_FNB_SUCCESS", "PAYMENT", payment.id(),
                            "{\"status\":\"PENDING\"}",
                            "{\"status\":\"SUCCESS\",\"method\":\"VNPAY\",\"amount\":"
                                    + payment.amount() + ",\"fnbOrderId\":" + concessionOrderId
                                    + ",\"pointsUsed\":" + order.pointsUsed() + "}",
                            com.cinema.service.audit.AuditService.SUCCESS);

                    // Gửi thông báo cho khách hàng và nhân viên chi nhánh (Req 21/23)
                    try {
                        String branchName = "";
                        try {
                            var b = new BranchDAO().findById(order.branchId());
                            if (b.isPresent()) branchName = b.get().name();
                        } catch (Exception ignored) {}

                        if (order.userId() != null && order.userId() > 0) {
                            notificationService.notifyFnbOrderConfirmed(order.userId(), order.id(),
                                    order.orderCode(), order.pickupCode(), payment.amount(), branchName);
                        }
                        notificationService.notifyBranchFnbOrderCreated(order.branchId(),
                                order.orderCode(), order.pickupCode(), payment.amount());
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Không gửi được thông báo F&B cho đơn " + order.orderCode(), e);
                    }
                } else {
                    payment.setStatus(Payment.STATUS_FAILED);
                    logger.info("[VNPAY-FNB] " + source + " payment " + payment.id()
                            + " → FAILED txnRef=" + txnRef);
                    audit.record(conn, null, "VNPAY_CALLBACK_FNB_FAILED", "PAYMENT", payment.id(),
                            "{\"status\":\"PENDING\"}",
                            "{\"status\":\"FAILED\",\"method\":\"VNPAY\",\"amount\":"
                                    + payment.amount() + ",\"fnbOrderId\":" + concessionOrderId + "}",
                            com.cinema.service.audit.AuditService.SUCCESS);
                }
            } else {
                // Guard đã chặn — trạng thái đã được xử lý bởi callback khác trước đó
                payment.setStatus(payment.status());
            }
            return payment;
        });
    }

    /** Backward-compatible entry point for callers without a request-specific return URL. */
    public PaymentOutcome createFnbPaymentSession(long concessionOrderId, long userId,
                                                   String successUrl, String cancelUrl) throws Exception {
        return createFnbPaymentSession(concessionOrderId, userId, null, successUrl, cancelUrl);
    }

    /** Tạo phiên VNPay cho đơn F&B, giữ callback quay về đúng host đang phục vụ ứng dụng. */
    public PaymentOutcome createFnbPaymentSession(long concessionOrderId, long userId,
                                                   String returnUrl, String successUrl,
                                                   String cancelUrl) throws Exception {
        // 1. Load và validate đơn F&B
        try (Connection conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
            ConcessionOrder order = loadConcessionOrder(null, concessionOrderId);
            if (order == null) {
                throw new ServiceException.NotFound("Đơn bắp nước không tồn tại");
            }
            if (order.userId() == null || !order.userId().equals(userId)) {
                throw new ServiceException.Forbidden("Chỉ chủ đơn mới được thanh toán đơn này");
            }
            if (!ConcessionOrder.STATUS_PENDING.equals(order.status())) {
                throw new ServiceException.Conflict("Đơn không ở trạng thái chờ thanh toán ("
                        + order.status() + ")");
            }

            // Số tiền charge qua VNPay phải là amount sau discount điểm (nếu có),
            // không phải totalAmount gốc — tránh thu tiền phần loyalty đã cover.
            long amountToCharge = order.amountAfterPointsDiscount();
            if (amountToCharge <= 0) {
                // Đơn được thanh toán hoàn toàn bằng điểm phải đi qua xác nhận /order/pay.
                throw new ServiceException.Validation("Số tiền thanh toán phải lớn hơn 0");
            }

            // 2. Tạo Payment record với method=VNPAY, status=PENDING
            Payment payment = new Payment();
            payment.setConcessionOrderId(concessionOrderId);
            payment.setMethod(VnPayProvider.METHOD);
            // Amount charge qua VNPay = totalAmount - pointsDiscount (không phải
            // totalAmount gốc), đã tính ở amountToCharge ở trên.
            payment.setAmount(amountToCharge);
            payment.setStatus(Payment.STATUS_PENDING);
            String idempotencyKey = "FNB-" + concessionOrderId + "-" + UUID.randomUUID();
            payment.setIdempotencyKey(idempotencyKey);
            paymentDao.insert(payment);

            // 3. Tạo redirect URL với VnPayProvider
            VnPayProvider vnPayProvider = (VnPayProvider) providers.get(VnPayProvider.METHOD);
            if (vnPayProvider == null) {
                throw new ServiceException.BusinessRule("VNPAY_NOT_CONFIGURED",
                        "Phương thức thanh toán VNPay chưa được cấu hình");
            }

            PaymentProvider.PaymentSession session = new PaymentProvider.PaymentSession(
                    payment.id(), null, VnPayProvider.METHOD,
                    amountToCharge, idempotencyKey, userId);
            // VNPay yêu cầu order info không dấu, không chứa ký tự đặc biệt.
            String safeOrderCode = order.orderCode().replaceAll("[^A-Za-z0-9]", "");
            String orderInfo = "Thanh toan don FNB " + safeOrderCode;
            if (order.pointsUsed() > 0) {
                orderInfo += " points " + order.pointsUsed();
            }

            Map<String, String> vnpParams = new LinkedHashMap<>();
            vnpParams.put("vnp_Version", "2.1.0");
            vnpParams.put("vnp_Command", "pay");
            vnpParams.put("vnp_TmnCode", vnPayProvider.config().tmnCode());
            vnpParams.put("vnp_Amount", VnPayUtil.toVnpAmount(amountToCharge));
            vnpParams.put("vnp_CurrCode", "VND");
            vnpParams.put("vnp_TxnRef", idempotencyKey);
            vnpParams.put("vnp_OrderInfo", orderInfo);
            vnpParams.put("vnp_OrderType", "other");
            vnpParams.put("vnp_Locale", "vn");
            vnpParams.put("vnp_ReturnUrl", returnUrl == null || returnUrl.isBlank()
                    ? vnPayProvider.config().returnUrl() : returnUrl);
            vnpParams.put("vnp_IpAddr", "127.0.0.1");
            vnpParams.putAll(VnPayUtil.paymentWindow(vnPayProvider.config().expireMinutes()));

            String redirectUrl = VnPayUtil.buildRedirectUrl(
                    vnPayProvider.config().payUrl(), vnpParams, vnPayProvider.config().hashSecret());

            logger.info("[VNPAY-FNB] Created payment session " + payment.id()
                    + " for order " + order.orderCode()
                    + " amount=" + amountToCharge
                    + " (pointsUsed=" + order.pointsUsed()
                    + ", pointsDiscount=" + order.pointsDiscount() + ")"
                    + " redirectUrl=" + maskVnpUrl(redirectUrl));

            return new PaymentOutcome(payment, redirectUrl);
        }
    }

    /** Lấy thông tin thanh toán VNPay của đơn F&B (Req 21.x). */
    public PaymentStatusResult getFnbPaymentStatus(long concessionOrderId) throws Exception {
        try (Connection conn = com.cinema.infrastructure.database.DBContext.getConnection()) {
            String sql = """
                SELECT p.id, p.status, p.method, p.amount, p.provider_reference, p.confirmed_at
                FROM dbo.payment p
                WHERE p.concession_order_id = ? AND p.method = 'VNPAY'
                ORDER BY p.id DESC
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, concessionOrderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PaymentStatusResult(
                                rs.getLong("id"),
                                rs.getString("status"),
                                rs.getString("method"),
                                rs.getLong("amount"),
                                rs.getString("provider_reference"),
                                rs.getTimestamp("confirmed_at") != null
                                        ? rs.getTimestamp("confirmed_at").toLocalDateTime() : null);
                    }
                }
            }
            return new PaymentStatusResult(null, "NOT_FOUND", null, 0, null, null);
        }
    }

    // ---- F&B helpers ----

    private ConcessionOrder loadConcessionOrder(Connection conn, long orderId) throws Exception {
        String sql = """
            SELECT id, order_code, branch_id, user_id, status, total_amount,
                   points_used, points_discount, pickup_code, pickup_deadline, created_at
            FROM dbo.concession_order WHERE id = ?
            """;
        if (conn != null) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapConcessionOrder(rs);
                }
            }
        } else {
            try (Connection c = com.cinema.infrastructure.database.DBContext.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapConcessionOrder(rs);
                }
            }
        }
        return null;
    }

    private ConcessionOrder loadConcessionOrderLocked(Connection conn, long orderId) throws Exception {
        String sql = """
            SELECT id, order_code, branch_id, user_id, status, total_amount,
                   points_used, points_discount, pickup_code, pickup_deadline, created_at
            FROM dbo.concession_order WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapConcessionOrder(rs);
            }
        }
        return null;
    }

    private ConcessionOrder mapConcessionOrder(ResultSet rs) throws Exception {
        ConcessionOrder order = new ConcessionOrder();
        order.setId(rs.getLong("id"));
        order.setOrderCode(rs.getString("order_code"));
        order.setBranchId(rs.getLong("branch_id"));
        long userId = rs.getLong("user_id");
        order.setUserId(rs.wasNull() ? null : userId);
        order.setStatus(rs.getString("status"));
        order.setTotalAmount(rs.getLong("total_amount"));
        order.setPointsUsed(rs.getInt("points_used"));
        order.setPointsDiscount(rs.getLong("points_discount"));
        order.setPickupCode(rs.getString("pickup_code"));
        if (rs.getTimestamp("pickup_deadline") != null) {
            order.setPickupDeadline(rs.getTimestamp("pickup_deadline").toLocalDateTime());
        }
        if (rs.getTimestamp("created_at") != null) {
            order.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        }
        return order;
    }

    private boolean updateConcessionOrderToReady(Connection conn, ConcessionOrder order) throws Exception {
        String sql = """
            UPDATE dbo.concession_order
            SET status = ?, version = version + 1
            WHERE id = ? AND status = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ConcessionOrder.STATUS_READY_FOR_PICKUP);
            ps.setLong(2, order.id());
            ps.setString(3, ConcessionOrder.STATUS_PENDING);
            return ps.executeUpdate() == 1;
        }
    }

    private String maskVnpUrl(String url) {
        int idx = url.indexOf('?');
        if (idx < 0) return url;
        return url.substring(0, idx) + "?"
                + url.substring(idx + 1).replaceAll("vnp_SecureHash=[^&]+", "vnp_SecureHash=***");
    }

    private String normalizeMethod(String method) {
        return method == null ? "" : method.trim().toUpperCase();
    }

    /** Kết quả tạo phiên: payment + redirect URL (mock gateway) nếu có. */
    public record PaymentOutcome(Payment payment, String redirectUrl) { }

    /** Kết quả kiểm tra trạng thái thanh toán F&B. */
    public record PaymentStatusResult(Long paymentId, String status, String method,
                                     long amount, String txnRef,
                                     java.time.LocalDateTime confirmedAt) { }
}
