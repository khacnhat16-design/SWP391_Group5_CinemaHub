package com.cinema.payment;

import com.cinema.booking.SeatHoldDAO;
import com.cinema.booking.Ticket;
import com.cinema.booking.TicketDAO;
import com.cinema.common.ServiceException;
import dal.DBContext;
import com.cinema.util.TransactionTemplate;
import com.cinema.wallet.WalletService;

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
import com.cinema.payment.VnPayProvider;
import com.cinema.payment.PaymentProvider;
import com.cinema.payment.VnPayUtil;

/**
 * Payment orchestration for core booking flow.
 * Simplified to VNPAY and WALLET methods only.
 */
public class PaymentService {
    private static final Logger logger = Logger.getLogger(PaymentService.class.getName());
    public static final String ALREADY_PROCESSED = "ALREADY_PROCESSED";

    private final PaymentDAO paymentDao;
    private final TicketDAO ticketDao = new TicketDAO();
    private final SeatHoldDAO seatHoldDao = new SeatHoldDAO();
    private final WalletService walletService;
    private final Map<String, PaymentProvider> providers = new HashMap<>();
    private final TransactionTemplate tx = new TransactionTemplate();

    public PaymentService(PaymentDAO paymentDao, WalletService walletService,
                          MockGatewayProvider mockGateway, String bankAccountInfo) {
        this(paymentDao, walletService, mockGateway, bankAccountInfo, null);
    }

    public PaymentService(PaymentDAO paymentDao, WalletService walletService,
                          MockGatewayProvider mockGateway, String bankAccountInfo,
                          VnPayProvider vnPay) {
        this.paymentDao = paymentDao;
        this.walletService = walletService;
        register(new CashProvider());
        register(mockGateway);
        register(new WalletProvider());
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
                    "Phương thức thanh toán phải là CASH, MOCK_GATEWAY, BANK_TRANSFER hoặc WALLET");
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
     * Thanh toán ví (Req 18.3): trừ ví guarded + payment SUCCESS + vé CONFIRMED
     * nguyên tử trong MỘT transaction.
     */
    public PaymentOutcome payByWallet(long ticketId, long amount, long userId) throws Exception {
        requireTicketPending(ticketId);
        return tx.execute(conn -> {
            Payment payment = insertPayment(conn, ticketId, Payment.METHOD_WALLET, amount);

            // walletService.spend() thực hiện UPDATE với WHERE balance >= amount
            // (guarded) — nếu số dư không đủ sẽ trả về 0 row affected và ném
            // WALLET_INSUFFICIENT_BALANCE; toàn bộ transaction (insert payment +
            // insert ticket) sẽ rollback để tránh charge nhưng không phát hành vé.
            walletService.spend(conn, userId, amount, "TICKET", ticketId,
                    payment.idempotencyKey());

            if (!confirmTicket(conn, ticketId, amount)) {
                throw new ServiceException.Conflict(
                        "Suất giữ ghế đã hết hạn — giao dịch ví chưa được ghi nhận");
            }
            markPayment(conn, payment.id(), Payment.STATUS_SUCCESS,
                    "WALLET-" + payment.id(), null);

            payment.setStatus(Payment.STATUS_SUCCESS);
            logger.info("Wallet payment " + payment.id() + " succeeded for ticket " + ticketId);
            return new PaymentOutcome(payment, null);
        });
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
            boolean walletTopUp = Payment.METHOD_WALLET.equals(payment.method())
                    && txnRef.startsWith("TOPUP-");
            if (!VnPayProvider.METHOD.equals(payment.method()) && !walletTopUp) {
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
                boolean reviewRequired = success && !walletTopUp && payment.ticketId() != null
                        && !confirmTicket(conn, payment.ticketId(), payment.amount());
                String resultStatus = reviewRequired ? Payment.STATUS_REVIEW_REQUIRED
                        : success ? Payment.STATUS_SUCCESS : Payment.STATUS_FAILED;
                boolean transitioned = markPayment(conn, payment.id(), resultStatus,
                        vnpTransactionNo == null ? ("VNPAY-" + payment.id()) : vnpTransactionNo,
                        vnpParams.get("vnp_SecureHash"));
                if (transitioned) {
                    if (success && walletTopUp) {
                        walletService.creditTopUpFromVnPay(conn, txnRef, payment.id(),
                                payment.amount(), vnpTransactionNo, vnpParams.get("vnp_SecureHash"));
                    }
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

        if (!ticketDao.confirmGuarded(conn, ticketId, paidAmount)) {
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
}

