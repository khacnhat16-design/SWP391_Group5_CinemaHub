package com.cinema.dao.auth;

import com.cinema.model.auth.Transaction;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Data access for customer ticket purchase history. */
public class PurchaseHistoryDAO {

    /** Find ticket purchases for a customer, sorted by date descending. */
    public List<Transaction> findCustomerTransactions(Long customerId, int limit, int offset) throws Exception {
        String sql = """
            SELECT t.id, t.user_id AS customer_id, 'TICKET_BOOKING' AS type,
                   t.total_amount AS amount, t.status, t.created_at,
                   CONCAT('Vé - ', COALESCE(m.title, 'N/A')) AS description
            FROM dbo.ticket t
            JOIN dbo.showtime s ON s.id = t.showtime_id
            LEFT JOIN dbo.movie m ON m.id = s.movie_id
            WHERE t.user_id = ? AND t.status IN ('CONFIRMED', 'USED', 'CANCELLED')
            ORDER BY t.created_at DESC
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """;

        List<Transaction> transactions = new ArrayList<>();

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            ps.setInt(2, offset);
            ps.setInt(3, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    transactions.add(mapRow(rs));
                }
            }
        }

        return transactions;
    }

    /**
     * Find a single transaction by ID (for detailed view with IDOR check).
     */
    public Transaction findById(Long transactionId, Long customerId) throws Exception {
        String sql = """
            SELECT t.id, t.user_id AS customer_id, 'TICKET_BOOKING' AS type,
                   t.total_amount AS amount, t.status, t.created_at,
                   CONCAT('Vé - ', COALESCE(m.title, 'N/A')) AS description
            FROM dbo.ticket t
            JOIN dbo.showtime s ON s.id = t.showtime_id
            LEFT JOIN dbo.movie m ON m.id = s.movie_id
            WHERE t.id = ? AND t.user_id = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, transactionId);
            ps.setLong(2, customerId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }

        // Không có row nào → transaction không tồn tại HOẶC không thuộc về customer.
        // Service sẽ ném NotFound — không phân biệt 2 trường hợp để tránh leak IDOR.
        return null;
    }

    private Transaction mapRow(ResultSet rs) throws Exception {
        String type = rs.getString("type");
        java.math.BigDecimal amount = rs.getBigDecimal("amount");
        String status = rs.getString("status");
        Object createdAtObj = rs.getObject("created_at");
        String description = rs.getString("description");

        return new Transaction(
            rs.getLong("id"),
            rs.getLong("customer_id"),
            type,
            amount,
            status,
            createdAtObj != null ? ((java.sql.Timestamp) createdAtObj).toLocalDateTime() : null,
            description
        );
    }
}
