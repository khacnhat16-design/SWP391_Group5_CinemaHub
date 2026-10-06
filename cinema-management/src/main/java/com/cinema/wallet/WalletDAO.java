package com.cinema.wallet;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for wallet + wallet_tx (Req 18.1-18.8). */
public class WalletDAO {

    /** Tạo ví cho customer_profile mới (balance 0). Bỏ qua nếu đã có (idempotent). */
    public void ensureWallet(long userId) throws Exception {
        String sql = """
            IF NOT EXISTS (SELECT 1 FROM dbo.wallet WHERE user_id = ?)
            INSERT INTO dbo.wallet (user_id, balance, version) VALUES (?, 0, 0)
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    public Optional<Wallet> findByUser(long userId) throws Exception {
        String sql = "SELECT user_id, balance, version FROM dbo.wallet WHERE user_id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Tiêu ví guarded (Req 18.3, 18.6 — design.md WalletService):
     * UPDATE balance = balance - amount WHERE balance >= amount trong connection của caller.
     * Hai spend đồng thời với số dư chỉ đủ một → đúng một affectedRows == 1;
     * số dư không bao giờ âm (CK_wallet_balance là chốt chặn cuối).
     *
     * @return số dư sau khi trừ, hoặc -1 nếu không đủ số dư
     */
    public long trySpend(Connection conn, long userId, long amount) throws SQLException {
        String update = """
            UPDATE dbo.wallet
            SET balance = balance - ?, version = version + 1
            WHERE user_id = ? AND balance >= ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, amount);
            ps.setLong(2, userId);
            ps.setLong(3, amount);
            if (ps.executeUpdate() == 0) return -1;
        }
        return readBalance(conn, userId);
    }

    /** Cộng số dư (top-up success / refund) trong connection của caller. */
    public long credit(Connection conn, long userId, long amount) throws SQLException {
        String update = """
            UPDATE dbo.wallet
            SET balance = balance + ?, version = version + 1
            WHERE user_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, amount);
            ps.setLong(2, userId);
            if (ps.executeUpdate() == 0) return -1; // ví chưa tồn tại
        }
        return readBalance(conn, userId);
    }

    /**
     * Ghi giao dịch ví trong connection của caller. UNIQUE(idempotency_key) chống
     * ghi trùng khi retry/callback lặp (top-up pending→success hai lần).
     *
     * @return false nếu trùng idempotency_key (giao dịch đã ghi trước đó)
     */
    public boolean insertTx(Connection conn, long userId, String type, long amount,
                            long balanceAfter, String refType, Long refId,
                            String idempotencyKey) throws SQLException {
        String sql = """
            INSERT INTO dbo.wallet_tx (user_id, type, amount, balance_after, ref_type, ref_id,
                                       idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, type);
            ps.setLong(3, amount);
            ps.setLong(4, balanceAfter);
            ps.setString(5, refType);
            if (refId == null) ps.setNull(6, java.sql.Types.BIGINT);
            else ps.setLong(6, refId);
            ps.setString(7, idempotencyKey);
            try {
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                // SQL Server 2627/2601 = unique constraint violation → trùng key
                if (e.getErrorCode() == 2627 || e.getErrorCode() == 2601) return false;
                throw e;
            }
        }
    }

    /** Lịch sử giao dịch ví (Req 18.7), mới nhất trước. */
    public List<WalletTx> findTxHistory(long userId) throws Exception {
        String sql = """
            SELECT id, user_id, type, amount, balance_after, ref_type, ref_id,
                   idempotency_key, created_at
            FROM dbo.wallet_tx WHERE user_id = ? ORDER BY created_at DESC, id DESC
            """;
        List<WalletTx> txs = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    WalletTx tx = new WalletTx();
                    tx.setId(rs.getLong("id"));
                    tx.setUserId(rs.getLong("user_id"));
                    tx.setType(rs.getString("type"));
                    tx.setAmount(rs.getLong("amount"));
                    tx.setBalanceAfter(rs.getLong("balance_after"));
                    tx.setRefType(rs.getString("ref_type"));
                    long refId = rs.getLong("ref_id");
                    tx.setRefId(rs.wasNull() ? null : refId);
                    tx.setIdempotencyKey(rs.getString("idempotency_key"));
                    Timestamp created = rs.getTimestamp("created_at");
                    if (created != null) tx.setCreatedAt(created.toLocalDateTime());
                    txs.add(tx);
                }
            }
        }
        return txs;
    }

    private long readBalance(Connection conn, long userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM dbo.wallet WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("balance") : -1;
            }
        }
    }

    private Wallet mapRow(ResultSet rs) throws SQLException {
        Wallet wallet = new Wallet();
        wallet.setUserId(rs.getLong("user_id"));
        wallet.setBalance(rs.getLong("balance"));
        wallet.setVersion(rs.getInt("version"));
        return wallet;
    }
}
