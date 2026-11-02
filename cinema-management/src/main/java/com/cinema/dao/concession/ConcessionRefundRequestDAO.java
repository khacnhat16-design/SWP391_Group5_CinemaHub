package com.cinema.dao.concession;

import com.cinema.model.concession.ConcessionRefundRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class ConcessionRefundRequestDAO {
    private static final String COLUMNS = """
        r.id, r.order_id, r.user_id, r.branch_id, o.order_code, r.refund_amount,
        r.refund_points, r.bank_name, r.bank_account_name, r.bank_account_number,
        r.reason, r.status, r.manager_note, r.created_at, r.decided_at
        """;

    public ConcessionRefundRequest insert(Connection conn, long orderId, long userId, long branchId,
                                          String bankName, String accountName, String accountNumber,
                                          String reason, long amount, int points) throws SQLException {
        String sql = """
            INSERT INTO dbo.concession_refund_request
                (order_id, user_id, branch_id, refund_amount, refund_points, bank_name,
                 bank_account_name, bank_account_number, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orderId);
            ps.setLong(2, userId);
            ps.setLong(3, branchId);
            ps.setLong(4, amount);
            ps.setInt(5, points);
            ps.setString(6, bankName);
            ps.setString(7, accountName);
            ps.setString(8, accountNumber);
            ps.setString(9, reason);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("Could not retrieve concession refund request ID");
                return findById(conn, rs.getLong(1));
            }
        }
    }

    public boolean hasPendingForOrder(Connection conn, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM dbo.concession_refund_request
                WHERE order_id = ? AND status = 'PENDING'
                """)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public ConcessionRefundRequest findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
             FROM dbo.concession_refund_request r
             JOIN dbo.concession_order o ON o.id = r.order_id
             WHERE r.id = ?
             """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public ConcessionRefundRequest lockById(Connection conn, long id) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
             FROM dbo.concession_refund_request r WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
             JOIN dbo.concession_order o ON o.id = r.order_id
             WHERE r.id = ?
             """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public List<ConcessionRefundRequest> findMine(Connection conn, long userId) throws SQLException {
        String sql = "SELECT " + COLUMNS + """
             FROM dbo.concession_refund_request r
             JOIN dbo.concession_order o ON o.id = r.order_id
             WHERE r.user_id = ? ORDER BY r.created_at DESC, r.id DESC
             """;
        return query(conn, sql, ps -> ps.setLong(1, userId));
    }

    public List<ConcessionRefundRequest> findForConsole(Connection conn, List<Long> branchIds,
                                                          String status) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append("""
             FROM dbo.concession_refund_request r
             JOIN dbo.concession_order o ON o.id = r.order_id
             WHERE 1 = 1
             """);
        List<Object> parameters = new ArrayList<>();
        if (branchIds != null) {
            if (branchIds.isEmpty()) return List.of();
            sql.append(" AND r.branch_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(branchIds.size(), "?")))
                    .append(")");
            parameters.addAll(branchIds);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
            parameters.add(status);
        }
        sql.append(" ORDER BY CASE WHEN r.status = 'PENDING' THEN 0 ELSE 1 END, r.created_at DESC");
        return query(conn, sql.toString(), ps -> {
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                if (value instanceof Long branchId) ps.setLong(i + 1, branchId);
                else ps.setString(i + 1, (String) value);
            }
        });
    }

    public boolean updateDecision(Connection conn, long id, String status, long managerId,
                                  String managerNote) throws SQLException {
        String sql = """
            UPDATE dbo.concession_refund_request
            SET status = ?, manager_id = ?, manager_note = ?, decided_at = SYSUTCDATETIME()
            WHERE id = ? AND status = 'PENDING'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, managerId);
            ps.setString(3, managerNote);
            ps.setLong(4, id);
            return ps.executeUpdate() == 1;
        }
    }

    private List<ConcessionRefundRequest> query(Connection conn, String sql, Binder binder)
            throws SQLException {
        List<ConcessionRefundRequest> requests = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) requests.add(map(rs));
            }
        }
        return requests;
    }

    private ConcessionRefundRequest map(ResultSet rs) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new ConcessionRefundRequest(
                rs.getLong("id"), rs.getLong("order_id"), rs.getLong("user_id"),
                rs.getLong("branch_id"), rs.getString("order_code"),
                rs.getLong("refund_amount"), rs.getInt("refund_points"),
                rs.getString("bank_name"), rs.getString("bank_account_name"),
                rs.getString("bank_account_number"), rs.getString("reason"),
                rs.getString("status"), rs.getString("manager_note"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                decided == null ? null : decided.toLocalDateTime());
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
