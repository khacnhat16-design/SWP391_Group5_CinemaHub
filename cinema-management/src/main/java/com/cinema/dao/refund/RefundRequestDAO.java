package com.cinema.dao.refund;

import com.cinema.model.refund.RefundRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Data access for customer refund requests. */
public class RefundRequestDAO {
    public long insert(Connection conn, RefundRequest request) throws SQLException {
        String sql = """
            INSERT INTO dbo.refund_request
              (ticket_id, user_id, branch_id, customer_full_name, customer_phone, customer_email,
               bank_name, bank_account_name, bank_account_number, ticket_code, showtime_id,
               showtime_start, ticket_amount, status, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, request.ticketId());
            ps.setLong(2, request.userId());
            ps.setLong(3, request.branchId());
            ps.setString(4, request.customerFullName());
            ps.setString(5, request.customerPhone());
            ps.setString(6, request.customerEmail());
            ps.setString(7, request.bankName());
            ps.setString(8, request.bankAccountName());
            ps.setString(9, request.bankAccountNumber());
            ps.setString(10, request.ticketCode());
            ps.setLong(11, request.showtimeId());
            ps.setTimestamp(12, Timestamp.valueOf(request.showtimeStart()));
            ps.setLong(13, request.ticketAmount());
            ps.setString(14, request.reason());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("Không tạo được yêu cầu hoàn tiền");
                request.setId(rs.getLong(1));
                return request.id();
            }
        }
    }

    public RefundRequest lockById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM dbo.refund_request WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public RefundRequest findById(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM dbo.refund_request WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public RefundRequest findPendingForTicket(Connection conn, long ticketId) throws SQLException {
        String sql = """
            SELECT TOP 1 * FROM dbo.refund_request
            WHERE ticket_id = ? AND status = 'PENDING'
            ORDER BY created_at DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public List<RefundRequest> listForUser(Connection conn, long userId) throws SQLException {
        List<RefundRequest> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT * FROM dbo.refund_request
                WHERE user_id = ? ORDER BY created_at DESC
                """)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return result;
    }

    public List<RefundRequest> listForConsole(Connection conn, Set<Long> branchIds,
                                              boolean allBranches,
                                              RefundRequest.Status status) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM dbo.refund_request WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!allBranches) {
            if (branchIds == null || branchIds.isEmpty()) {
                sql.append(" AND 1=0");
            } else {
                sql.append(" AND branch_id IN (");
                for (int i = 0; i < branchIds.size(); i++) {
                    if (i > 0) sql.append(',');
                    sql.append('?');
                }
                sql.append(')');
                params.addAll(branchIds);
            }
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC");

        List<RefundRequest> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object value = params.get(i);
                if (value instanceof Long id) ps.setLong(i + 1, id);
                else ps.setString(i + 1, value.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        }
        return result;
    }

    public boolean updateStatusGuarded(Connection conn, long id, RefundRequest.Status next,
                                       RefundRequest.Status expected, Long managerId,
                                       String managerNote) throws SQLException {
        String sql = """
            UPDATE dbo.refund_request
            SET status = ?, manager_id = ?, manager_note = ?, decided_at = SYSUTCDATETIME()
            WHERE id = ? AND status = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, next.name());
            if (managerId == null) ps.setNull(2, java.sql.Types.BIGINT);
            else ps.setLong(2, managerId);
            ps.setString(3, managerNote);
            ps.setLong(4, id);
            ps.setString(5, expected.name());
            return ps.executeUpdate() == 1;
        }
    }

    public boolean markEmailSent(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE dbo.refund_request
                SET email_sent_at = SYSUTCDATETIME()
                WHERE id = ? AND status = 'APPROVED'
                """)) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    private static RefundRequest map(ResultSet rs) throws SQLException {
        RefundRequest request = new RefundRequest();
        request.setId(rs.getLong("id"));
        request.setTicketId(rs.getLong("ticket_id"));
        request.setUserId(rs.getLong("user_id"));
        request.setBranchId(rs.getLong("branch_id"));
        request.setCustomerFullName(rs.getString("customer_full_name"));
        request.setCustomerPhone(rs.getString("customer_phone"));
        request.setCustomerEmail(rs.getString("customer_email"));
        request.setBankName(rs.getString("bank_name"));
        request.setBankAccountName(rs.getString("bank_account_name"));
        request.setBankAccountNumber(rs.getString("bank_account_number"));
        request.setTicketCode(rs.getString("ticket_code"));
        request.setShowtimeId(rs.getLong("showtime_id"));
        Timestamp showtimeStart = rs.getTimestamp("showtime_start");
        if (showtimeStart != null) request.setShowtimeStart(showtimeStart.toLocalDateTime());
        request.setTicketAmount(rs.getLong("ticket_amount"));
        request.setStatus(RefundRequest.Status.valueOf(rs.getString("status")));
        request.setReason(rs.getString("reason"));
        long managerId = rs.getLong("manager_id");
        request.setManagerId(rs.wasNull() ? null : managerId);
        request.setManagerNote(rs.getString("manager_note"));
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        if (decidedAt != null) request.setDecidedAt(decidedAt.toLocalDateTime());
        Timestamp emailSentAt = rs.getTimestamp("email_sent_at");
        if (emailSentAt != null) request.setEmailSentAt(emailSentAt.toLocalDateTime());
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) request.setCreatedAt(createdAt.toLocalDateTime());
        return request;
    }
}
