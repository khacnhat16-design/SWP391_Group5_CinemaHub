package com.cinema.payment;

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

/** Data access for payment table (Req 9.1-9.8). */
public class PaymentDAO {

    private static final String BASE_COLUMNS = """
        id, ticket_id, concession_order_id, method, amount, status,
        idempotency_key, provider_reference, hmac, created_at, confirmed_at
        """;

    /** Insert new payment; sets generated id. UNIQUE(idempotency_key) chống tạo trùng. */
    public void insert(Payment payment) throws Exception {
        String sql = """
            INSERT INTO dbo.payment (ticket_id, concession_order_id, method, amount, status,
                                     idempotency_key, provider_reference, hmac)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setNullableLong(ps, 1, payment.ticketId());
            setNullableLong(ps, 2, payment.concessionOrderId());
            ps.setString(3, payment.method());
            ps.setLong(4, payment.amount());
            ps.setString(5, payment.status());
            ps.setString(6, payment.idempotencyKey());
            ps.setString(7, payment.providerReference());
            ps.setString(8, payment.hmac());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) payment.setId(rs.getLong(1));
            }
        }
    }

    public Optional<Payment> findById(long paymentId) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.payment WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public Optional<Payment> findByIdempotencyKey(String key) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.payment WHERE idempotency_key = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Tìm payment theo idempotency_key trong transaction của caller, có row lock
     * (dùng cho callback VNPay — chống xử lý trùng khi IPN và Return URL cùng đến).
     */
    public Optional<Payment> findByIdempotencyKeyLocked(Connection conn, String key) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS
                + " FROM dbo.payment WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Khóa + đọc payment theo id trong transaction của caller (Req 9.7 — hai callback/
     * confirm đồng thời serialize trên row lock, đúng một lần chuyển trạng thái).
     */
    public Optional<Payment> findByIdLocked(Connection conn, long paymentId) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.payment WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, paymentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /** Payment PENDING mới nhất của một vé (nếu có). */
    public Optional<Payment> findPendingByTicket(long ticketId) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + """
             FROM dbo.payment
            WHERE ticket_id = ? AND status = 'PENDING'
            ORDER BY id DESC
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public List<Payment> findByTicket(long ticketId) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.payment WHERE ticket_id = ? ORDER BY id";
        List<Payment> payments = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) payments.add(mapRow(rs));
            }
        }
        return payments;
    }

    /** Mark successful payments of a cancelled ticket as refunded in the same transaction. */
    public int markTicketPaymentsRefunded(Connection conn, long ticketId) throws SQLException {
        String sql = """
            UPDATE dbo.payment
            SET status = 'REFUNDED', confirmed_at = COALESCE(confirmed_at, SYSUTCDATETIME())
            WHERE ticket_id = ? AND status = 'SUCCESS'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            return ps.executeUpdate();
        }
    }

    /**
     * Cập nhật trạng thái trong connection của caller (transaction đang giữ row lock).
     * Guard status = 'PENDING' để lần xử lý thứ hai trượt (idempotency, Req 9.7).
     *
     * @return true nếu chính request này thực hiện chuyển trạng thái
     */
    public boolean updateStatusGuarded(Connection conn, long paymentId, String newStatus,
                                       String providerReference, String hmac) throws Exception {
        String sql = """
            UPDATE dbo.payment
            SET status = ?, provider_reference = ?, hmac = ?, confirmed_at = SYSUTCDATETIME()
            WHERE id = ? AND status = 'PENDING'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, providerReference);
            ps.setString(3, hmac);
            ps.setLong(4, paymentId);
            return ps.executeUpdate() == 1;
        }
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws Exception {
        if (value == null) ps.setNull(index, java.sql.Types.BIGINT);
        else ps.setLong(index, value);
    }

    private Payment mapRow(ResultSet rs) throws Exception {
        Payment payment = new Payment();
        payment.setId(rs.getLong("id"));
        long ticketId = rs.getLong("ticket_id");
        payment.setTicketId(rs.wasNull() ? null : ticketId);
        long orderId = rs.getLong("concession_order_id");
        payment.setConcessionOrderId(rs.wasNull() ? null : orderId);
        payment.setMethod(rs.getString("method"));
        payment.setAmount(rs.getLong("amount"));
        payment.setStatus(rs.getString("status"));
        payment.setIdempotencyKey(rs.getString("idempotency_key"));
        payment.setProviderReference(rs.getString("provider_reference"));
        payment.setHmac(rs.getString("hmac"));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) payment.setCreatedAt(created.toLocalDateTime());
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        if (confirmed != null) payment.setConfirmedAt(confirmed.toLocalDateTime());
        return payment;
    }
}
