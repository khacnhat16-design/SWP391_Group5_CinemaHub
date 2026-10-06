package com.cinema.wallet;

import com.cinema.common.ServiceException;
import com.cinema.payment.MockGatewayProvider;
import dal.DBContext;
import com.cinema.util.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Service ví khách hàng và ledger số dư (Req 18.1-18.8).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Top-up: bội số 10.000đ, không vượt hạn mức mỗi lần; tạo giao dịch PENDING
 *       chờ xác nhận qua phương thức mock (Req 18.1).</li>
 *   <li>Top-up success → cộng số dư + ghi ledger; failure/hết hạn → số dư không đổi
 *       (Req 18.2).</li>
 *   <li>Spend: guarded UPDATE balance >= amount nguyên tử với xác nhận đơn (Req 18.3);
 *       không đủ số dư → 422 kèm số dư hiện tại (Req 18.4).</li>
 *   <li>Refund: cộng lại ví khi vé/đơn thanh toán bằng ví bị hủy (Req 18.5).</li>
 *   <li>Hai spend đồng thời số dư chỉ đủ một → đúng một thắng nhờ guarded update,
 *       số dư không bao giờ âm (Req 18.6).</li>
 *   <li>Lịch sử ví hiển thị số dư sau mỗi giao dịch (Req 18.7).</li>
 *   <li>Tài khoản LOCKED/INACTIVE → chặn nạp/tiêu (Req 18.8).</li>
 * </ul>
 *
 * <p>Mọi luồng ghi (spend/refund/confirm top-up) nhận Connection từ caller khi cần
 * nguyên tử với nghiệp vụ khác (booking/concession), hoặc tự mở transaction qua
 * {@link TransactionTemplate} khi chạy độc lập.
 */
public class WalletService {
    private static final Logger logger = Logger.getLogger(WalletService.class.getName());

    /** Req 18.1 — số tiền nạp phải là bội số của 10.000đ. */
    public static final long TOPUP_MULTIPLE = 10_000L;
    /** Hạn mức nạp tối đa mỗi lần (đồng). */
    public static final long TOPUP_MAX_PER_REQUEST = 50_000_000L;

    private final WalletDAO walletDao;
    private final TransactionTemplate tx = new TransactionTemplate();

    public WalletService(WalletDAO walletDao) {
        this.walletDao = walletDao;
    }

    /** Số dư + phiên bản ví hiện tại (Req 18.7); tạo ví 0 đồng nếu chưa có. */
    public Wallet getWallet(long userId) throws Exception {
        requireAccountUsable(userId);
        var wallet = walletDao.findByUser(userId);
        if (wallet.isPresent()) return wallet.get();

        walletDao.ensureWallet(userId);
        return walletDao.findByUser(userId)
                .orElseThrow(() -> new ServiceException.NotFound("Ví khách hàng không tồn tại"));
    }

    /** Lịch sử giao dịch ví (Req 18.7) — chỉ của chính tài khoản (IDOR chặn ở controller). */
    public List<WalletTx> history(long userId) throws Exception {
        return walletDao.findTxHistory(userId);
    }

    /**
     * Yêu cầu nạp tiền (Req 18.1, 18.2): validate bội số 10.000 + hạn mức, tạo giao
     * dịch top-up PENDING (bảng payment, method WALLET) chờ xác nhận thanh toán.
     * Số dư CHƯA thay đổi ở bước này.
     *
     * @return idempotencyKey của giao dịch pending — dùng cho bước confirm/fail
     */
    public String requestTopUp(long userId, long amount) throws Exception {
        requireAccountUsable(userId);
        if (amount <= 0 || amount % TOPUP_MULTIPLE != 0) {
            throw new ServiceException.Validation("Số tiền nạp phải là bội số của 10.000đ");
        }
        if (amount > TOPUP_MAX_PER_REQUEST) {
            throw new ServiceException.Validation("Số tiền nạp vượt hạn mức tối đa mỗi lần ("
                    + TOPUP_MAX_PER_REQUEST + "đ)");
        }
        walletDao.ensureWallet(userId);

        String idempotencyKey = "TOPUP-" + userId + "-" + UUID.randomUUID();
        String sql = """
            INSERT INTO dbo.payment (ticket_id, concession_order_id, method, amount, status,
                                     idempotency_key)
            VALUES (NULL, NULL, 'WALLET', ?, 'PENDING', ?)
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setString(2, idempotencyKey);
            ps.executeUpdate();
        }

        logger.info("Pending wallet top-up for user " + userId + " amount " + amount
                + " key " + idempotencyKey);
        return idempotencyKey;
    }

    /**
     * Xác nhận top-up thành công (Req 18.2): payment PENDING → SUCCESS, cộng số dư,
     * ghi ledger. Idempotent qua UNIQUE(idempotency_key) của wallet_tx — callback trùng
     * không cộng tiền hai lần.
     */
    public long confirmTopUp(String idempotencyKey) throws Exception {
        return tx.execute(conn -> {
            long[] payment = lockPendingPayment(conn, idempotencyKey);
            long paymentId = payment[0];
            long amount = payment[1];
            long userId = parseUserIdFromKey(idempotencyKey);

            markPayment(conn, paymentId, "SUCCESS");
            long balanceAfter = walletDao.credit(conn, userId, amount);
            if (balanceAfter < 0) {
                throw new ServiceException.NotFound("Ví khách hàng không tồn tại");
            }
            boolean recorded = walletDao.insertTx(conn, userId, WalletTx.TYPE_TOPUP, amount,
                    balanceAfter, "TOPUP_PAYMENT", paymentId, idempotencyKey);
            if (!recorded) {
                throw new ServiceException.Conflict("Giao dịch nạp đã được xử lý trước đó");
            }
            logger.info("Wallet top-up confirmed: user " + userId + " +" + amount
                    + " → balance " + balanceAfter);
            return balanceAfter;
        });
    }

    /**
     * Top-up thất bại/hết hạn chờ (Req 18.2): payment PENDING → FAILED, số dư không đổi.
     */
    public void failTopUp(String idempotencyKey) throws Exception {
        tx.executeVoid(conn -> {
            long[] payment = lockPendingPayment(conn, idempotencyKey);
            markPayment(conn, payment[0], "FAILED");
            logger.info("Wallet top-up failed: " + idempotencyKey);
        });
    }

    /**
     * Xử lý callback từ mock payment sandbox cho top-up.
     * HMAC được kiểm tra trước khi payment chuyển trạng thái; callback lặp lại
     * không được cộng tiền lần hai.
     */
    public long handleTopUpCallback(String idempotencyKey, String hmac, boolean success,
                                    MockGatewayProvider gateway) throws Exception {
        return tx.execute(conn -> {
            long[] payment = lockPendingPayment(conn, idempotencyKey);
            long paymentId = payment[0];
            long amount = payment[1];
            if (!gateway.verifyCallback(paymentId, amount, hmac)) {
                throw new ServiceException.Forbidden("HMAC callback không hợp lệ");
            }
            long userId = parseUserIdFromKey(idempotencyKey);
            if (!success) {
                markPayment(conn, paymentId, "FAILED");
                return walletDao.findByUser(userId).map(Wallet::balance).orElse(0L);
            }
            markPayment(conn, paymentId, "SUCCESS");
            long balanceAfter = walletDao.credit(conn, userId, amount);
            if (balanceAfter < 0) {
                throw new ServiceException.NotFound("Ví khách hàng không tồn tại");
            }
            if (!walletDao.insertTx(conn, userId, WalletTx.TYPE_TOPUP, amount,
                    balanceAfter, "TOPUP_PAYMENT", paymentId, idempotencyKey)) {
                throw new ServiceException.Conflict("Giao dịch nạp đã được xử lý trước đó");
            }
            return balanceAfter;
        });
    }

    /**
     * Ghi nhận top-up thành công từ callback VNPay trong transaction của payment.
     * Payment đã được khóa và chuyển trạng thái bởi PaymentService; phương thức này
     * chỉ ghi ledger + cộng ví nên callback vẫn nguyên tử và idempotent.
     */
    public long creditTopUpFromVnPay(Connection conn, String idempotencyKey, long paymentId,
                                     long amount, String providerReference, String hmac)
            throws Exception {
        long userId = parseUserIdFromKey(idempotencyKey);
        requireAccountUsable(userId);
        long balanceAfter = walletDao.credit(conn, userId, amount);
        if (balanceAfter < 0) {
            throw new ServiceException.NotFound("Ví khách hàng không tồn tại");
        }
        if (!walletDao.insertTx(conn, userId, WalletTx.TYPE_TOPUP, amount,
                balanceAfter, "TOPUP_PAYMENT", paymentId, idempotencyKey)) {
            throw new ServiceException.Conflict("Giao dịch nạp đã được ghi nhận trước đó");
        }
        logger.info("VNPay wallet top-up confirmed: user " + userId + " +" + amount
                + " -> balance " + balanceAfter + " providerRef " + providerReference);
        return balanceAfter;
    }

    /**
     * Tiêu ví guarded (Req 18.3, 18.4, 18.6) — chạy TRONG transaction của caller
     * (nguyên tử với xác nhận đơn vé/bắp nước). Hai spend đồng thời: guarded
     * UPDATE balance >= amount đảm bảo đúng một thành công, số dư không âm.
     *
     * @return số dư sau khi tiêu
     */
    public long spend(Connection conn, long userId, long amount,
                      String refType, Long refId, String idempotencyKey) throws Exception {
        requireAccountUsable(userId);
        if (amount <= 0) {
            throw new ServiceException.Validation("Số tiền tiêu phải là số dương");
        }
        long balanceAfter = walletDao.trySpend(conn, userId, amount);
        if (balanceAfter < 0) {
            long current = walletDao.findByUser(userId)
                    .map(Wallet::balance)
                    .orElseThrow(() -> new ServiceException.NotFound("Ví khách hàng không tồn tại"));
            throw new ServiceException.BusinessRule("WALLET_INSUFFICIENT_BALANCE",
                    "Số dư ví không đủ (hiện tại " + current + "đ, cần " + amount
                            + "đ) — vui lòng nạp thêm hoặc chọn phương thức khác");
        }
        boolean recorded = walletDao.insertTx(conn, userId, WalletTx.TYPE_SPEND, amount,
                balanceAfter, refType, refId, idempotencyKey);
        if (!recorded) {
            throw new ServiceException.Conflict("Giao dịch tiêu ví đã được ghi nhận trước đó");
        }
        return balanceAfter;
    }

    /**
     * Hoàn tiền vào ví (Req 18.5) — chạy trong transaction hủy vé/đơn của caller.
     * Cộng số dư + ghi ledger REFUND.
     *
     * @return số dư sau hoàn
     */
    public long refund(Connection conn, long userId, long amount,
                       String refType, Long refId, String idempotencyKey) throws Exception {
        if (amount <= 0) return walletDao.findByUser(userId).map(Wallet::balance).orElse(0L);
        walletDao.ensureWallet(userId);
        long balanceAfter = walletDao.credit(conn, userId, amount);
        if (balanceAfter < 0) {
            throw new ServiceException.NotFound("Ví khách hàng không tồn tại");
        }
        boolean recorded = walletDao.insertTx(conn, userId, WalletTx.TYPE_REFUND, amount,
                balanceAfter, refType, refId, idempotencyKey);
        if (!recorded) {
            // Refund đã ghi trước đó (retry) — idempotent, không cộng hai lần
            logger.info("Duplicate wallet refund ignored: " + idempotencyKey);
        }
        return balanceAfter;
    }

    // ---- helpers ----

    /** Req 18.8 — tài khoản LOCKED/INACTIVE bị chặn mọi giao dịch nạp/tiêu ví. */
    private void requireAccountUsable(long userId) throws SQLException {
        String sql = "SELECT status FROM dbo.user_account WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound("Tài khoản không tồn tại");
                }
                String status = rs.getString("status");
                if (!"ACTIVE".equals(status)) {
                    throw new ServiceException.BusinessRule("ACCOUNT_LOCKED",
                            "Tài khoản đang bị khóa — vui lòng liên hệ bộ phận hỗ trợ");
                }
            }
        }
    }

    /** Khóa + đọc giao dịch payment PENDING theo idempotency key (chống xử lý trùng). */
    private long[] lockPendingPayment(Connection conn, String idempotencyKey) throws SQLException {
        String sql = """
            SELECT id, amount, status FROM dbo.payment WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
            WHERE idempotency_key = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound("Giao dịch nạp tiền không tồn tại");
                }
                String status = rs.getString("status");
                if (!"PENDING".equals(status)) {
                    throw new ServiceException.Conflict(
                            "Giao dịch nạp đã được xử lý (" + status + ")");
                }
                return new long[] { rs.getLong("id"), rs.getLong("amount") };
            }
        }
    }

    private void markPayment(Connection conn, long paymentId, String status) throws SQLException {
        String sql = "UPDATE dbo.payment SET status = ?, confirmed_at = SYSUTCDATETIME() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, paymentId);
            ps.executeUpdate();
        }
    }

    /** Key định dạng TOPUP-{userId}-{uuid} → tách userId (top-up do hệ thống tự sinh key). */
    private long parseUserIdFromKey(String idempotencyKey) {
        String[] parts = idempotencyKey.split("-");
        if (parts.length < 2 || !"TOPUP".equals(parts[0])) {
            throw new ServiceException.Validation("Khóa giao dịch nạp không hợp lệ");
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new ServiceException.Validation("Khóa giao dịch nạp không hợp lệ");
        }
    }
}
