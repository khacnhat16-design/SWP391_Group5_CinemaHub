package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access for customer purchase history (tickets + F&B orders).
 * Returns transactions sorted by date descending.
 */
public class PurchaseHistoryDAO {

    /**
     * Find all purchase transactions for a customer, sorted by date descending.
     * Combines ticket bookings and F&B orders into a single timeline.
     */
    public List<Transaction> findCustomerTransactions(Long customerId, int limit, int offset) throws Exception {
        // UNION ALL giữa seat_hold (vé) và concession_order (F&B) để trả về
        // một timeline thống nhất cho trang "Lịch sử mua" của khách — frontend
        // chỉ cần render một danh sách thay vì gọi 2 API và merge.
        String sql = """
            SELECT TOP (?) id, customer_id, type, amount, status, points_earned, created_at, description
            FROM (
                SELECT
                    id, user_id as customer_id, 'TICKET_BOOKING' as type,
                    total_price as amount, status, points_earned, created_at,
                    CONCAT('Vé - ', ISNULL(movie_name, 'N/A')) as description
                FROM dbo.seat_hold
                WHERE user_id = ? AND status IN ('CONFIRMED', 'USED', 'CANCELLED')

                UNION ALL

                SELECT
                    id, customer_id, 'F&B_ORDER' as type,
                    total_amount as amount, status, 0 as points_earned, created_at,
                    CONCAT('F&B - ', ISNULL(order_detail_summary, 'N/A')) as description
                FROM dbo.concession_order
                WHERE customer_id = ? AND status IN ('COMPLETED', 'CANCELLED')
            ) combined
            ORDER BY created_at DESC
            OFFSET ? ROWS
            """;

        List<Transaction> transactions = new ArrayList<>();

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setLong(2, customerId);
            ps.setLong(3, customerId);
            ps.setInt(4, offset);

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
        // customer_id được truyền vào cả 2 nhánh của UNION để đảm bảo query trả về
        // rỗng nếu khách A cố truy cập transaction của khách B (chống IDOR).
        String sql = """
            SELECT id, customer_id, type, amount, status, points_earned, created_at, description
            FROM (
                SELECT
                    id, user_id as customer_id, 'TICKET_BOOKING' as type,
                    total_price as amount, status, points_earned, created_at,
                    CONCAT('Vé - ', ISNULL(movie_name, 'N/A')) as description
                FROM dbo.seat_hold
                WHERE id = ? AND user_id = ?

                UNION ALL

                SELECT
                    id, customer_id, 'F&B_ORDER' as type,
                    total_amount as amount, status, 0 as points_earned, created_at,
                    CONCAT('F&B - ', ISNULL(order_detail_summary, 'N/A')) as description
                FROM dbo.concession_order
                WHERE id = ? AND customer_id = ?
            ) combined
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, transactionId);
            ps.setLong(2, customerId);
            ps.setLong(3, transactionId);
            ps.setLong(4, customerId);

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
        Transaction tx = new Transaction();
        tx.setId(rs.getLong("id"));
        // customer_id được DB verify qua WHERE; không cần đưa vào Transaction object
        // (đã biết từ session user — controller tự check ownership).
        String type = rs.getString("type");
        java.math.BigDecimal amount = rs.getBigDecimal("amount");
        String status = rs.getString("status");
        int pointsEarned = rs.getInt("points_earned");
        Object createdAtObj = rs.getObject("created_at");
        String description = rs.getString("description");

        return new Transaction(
            rs.getLong("customer_id"),
            type,
            amount,
            status,
            pointsEarned,
            createdAtObj != null ? ((java.sql.Timestamp) createdAtObj).toLocalDateTime() : null,
            description
        );
    }
}
