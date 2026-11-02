package com.cinema.dao.concession;

import com.cinema.model.concession.ConcessionOrder;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for concession_order + concession_order_line (Req 20, 21). */
public class ConcessionOrderDAO {

    private static final String BASE_COLUMNS = """
        id, order_code, branch_id, ticket_id, showtime_id, user_id, status, total_amount,
        points_used, points_discount,
        pickup_code, pickup_deadline, created_at, fulfilled_at, fulfilled_by_staff_id, cancel_reason, version
        """;

    // ---- Staff order search query ----

    /** Query parameters for staff order list search. */
    public record StaffOrderQuery(
            Long branchId,
            String status,
            LocalDate fromDate,
            LocalDate toDate,
            String searchText,
            Long productId,
            int offset,
            int limit,
            String sortBy,
            String sortDir
    ) {
        public StaffOrderQuery {
            if (sortBy == null || sortBy.isBlank()) sortBy = "created_at";
            if (sortDir == null || sortDir.isBlank()) sortDir = "DESC";
            if (limit <= 0) limit = 20;
            if (limit > 100) limit = 100;
            if (offset < 0) offset = 0;
        }
    }

    /** Paginated staff order search with filters. */
    public List<ConcessionOrder> searchStaffOrders(StaffOrderQuery q) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT co.id, co.order_code, co.branch_id, co.ticket_id, co.showtime_id, co.user_id,
                   co.status, co.total_amount, co.points_used, co.points_discount, co.pickup_code,
                   co.pickup_deadline, co.created_at, co.fulfilled_at, co.fulfilled_by_staff_id,
                   co.cancel_reason, co.version, b.name AS branch_name, ua.full_name AS customer_name
            FROM dbo.concession_order co
            LEFT JOIN dbo.branch b ON b.id = co.branch_id
            LEFT JOIN dbo.user_account ua ON ua.id = co.user_id
            """);
        boolean needsJoin = q.searchText() != null && !q.searchText().isBlank();
        boolean needsProductJoin = q.productId() != null && q.productId() > 0;
        if (needsProductJoin) {
            sql.append(" LEFT JOIN dbo.concession_order_line col ON col.order_id = co.id");
        }
        sql.append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (q.branchId() != null && q.branchId() > 0) {
            sql.append(" AND co.branch_id = ?");
            params.add(q.branchId());
        }
        if (q.status() != null && !q.status().isBlank()) {
            sql.append(" AND co.status = ?");
            params.add(q.status());
        }
        if (q.fromDate() != null) {
            sql.append(" AND co.created_at >= ?");
            params.add(Timestamp.valueOf(q.fromDate().atStartOfDay()));
        }
        if (q.toDate() != null) {
            sql.append(" AND co.created_at <= ?");
            params.add(Timestamp.valueOf(q.toDate().plusDays(1).atStartOfDay()));
        }
        if (needsJoin && q.searchText() != null && !q.searchText().isBlank()) {
            sql.append(" AND (co.order_code LIKE ? OR ua.full_name LIKE ? OR ua.phone LIKE ? OR ua.email LIKE ?)");
            String like = "%" + q.searchText().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (needsProductJoin) {
            sql.append(" AND col.product_id = ?");
            params.add(q.productId());
        }
        // Whitelist cột sort — không nối chuỗi từ input user thẳng vào SQL để chặn injection.
        // Giá trị không khớp → fallback về created_at (mặc định).
        String sortCol = switch (q.sortBy().toLowerCase()) {
            case "ordercode" -> "co.order_code";
            case "status" -> "co.status";
            case "totalamount" -> "co.total_amount";
            case "createdat" -> "co.created_at";
            default -> "co.created_at";
        };
        String sortDir = "ASC".equalsIgnoreCase(q.sortDir()) ? "ASC" : "DESC";
        sql.append(" ORDER BY ").append(sortCol).append(" ").append(sortDir);
        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(q.offset());
        params.add(q.limit());

        List<ConcessionOrder> orders = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ConcessionOrder order = mapRow(rs);
                    order.setBranchName(rs.getString("branch_name"));
                    order.setCustomerName(rs.getString("customer_name"));
                    orders.add(order);
                }
            }
        }
        return orders;
    }

    /** Count total matching rows for pagination. */
    public int countStaffOrders(StaffOrderQuery q) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT co.id) FROM dbo.concession_order co");
        boolean needsJoin = q.searchText() != null && !q.searchText().isBlank();
        if (needsJoin) {
            sql.append(" LEFT JOIN dbo.user_account ua ON ua.id = co.user_id");
        }
        boolean needsProductJoin = q.productId() != null && q.productId() > 0;
        if (needsProductJoin) {
            sql.append(" LEFT JOIN dbo.concession_order_line col ON col.order_id = co.id");
        }
        sql.append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (q.branchId() != null && q.branchId() > 0) {
            sql.append(" AND co.branch_id = ?");
            params.add(q.branchId());
        }
        if (q.status() != null && !q.status().isBlank()) {
            sql.append(" AND co.status = ?");
            params.add(q.status());
        }
        if (q.fromDate() != null) {
            sql.append(" AND co.created_at >= ?");
            params.add(Timestamp.valueOf(q.fromDate().atStartOfDay()));
        }
        if (q.toDate() != null) {
            sql.append(" AND co.created_at <= ?");
            params.add(Timestamp.valueOf(q.toDate().plusDays(1).atStartOfDay()));
        }
        if (needsJoin && q.searchText() != null && !q.searchText().isBlank()) {
            sql.append(" AND (co.order_code LIKE ? OR ua.full_name LIKE ? OR ua.phone LIKE ? OR ua.email LIKE ?)");
            String like = "%" + q.searchText().trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (needsProductJoin) {
            sql.append(" AND col.product_id = ?");
            params.add(q.productId());
        }
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    /** Update fulfilled_by_staff_id after pickup. */
    public void markFulfilledByStaff(Connection conn, long orderId, long staffId) throws SQLException {
        String sql = "UPDATE dbo.concession_order SET fulfilled_by_staff_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, staffId);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    /** Find by order code (staff QR scan). */
    public Optional<ConcessionOrder> findByOrderCode(String orderCode) throws SQLException {
        if (orderCode == null || orderCode.isBlank()) return Optional.empty();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + BASE_COLUMNS + " FROM dbo.concession_order WHERE order_code = ?")) {
            ps.setString(1, orderCode.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Insert đơn; sets generated id. */
    public long insert(Connection conn, ConcessionOrder order) throws SQLException {
        String sql = """
            INSERT INTO dbo.concession_order (order_code, branch_id, ticket_id, showtime_id,
                                              user_id, status, total_amount, points_used, points_discount,
                                              pickup_code, pickup_deadline, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, order.orderCode());
            ps.setLong(2, order.branchId());
            setNullableLong(ps, 3, order.ticketId());
            setNullableLong(ps, 4, order.showtimeId());
            setNullableLong(ps, 5, order.userId());
            ps.setString(6, order.status());
            ps.setLong(7, order.totalAmount());
            ps.setInt(8, order.pointsUsed());
            ps.setLong(9, order.pointsDiscount());
            ps.setString(10, order.pickupCode());
            if (order.pickupDeadline() == null) ps.setNull(11, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(11, Timestamp.valueOf(order.pickupDeadline()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("Không tạo được đơn bắp nước");
                long id = rs.getLong(1);
                order.setId(id);
                return id;
            }
        }
    }

    /** Ghi dòng đơn với giá snapshot (Req 19.8 — giá cũ không đổi khi Admin sửa giá). */
    public void insertLines(Connection conn, long orderId,
                            List<ConcessionOrder.OrderLine> lines) throws SQLException {
        String sql = """
            INSERT INTO dbo.concession_order_line (order_id, product_id, qty, unit_price)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ConcessionOrder.OrderLine line : lines) {
                ps.setLong(1, orderId);
                ps.setLong(2, line.productId());
                ps.setInt(3, line.qty());
                ps.setLong(4, line.unitPrice());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<ConcessionOrder.OrderLine> findLines(Connection conn, long orderId) throws SQLException {
        String sql = """
            SELECT ol.product_id, ol.qty, ol.unit_price, p.name
            FROM dbo.concession_order_line ol
            JOIN dbo.product p ON p.id = ol.product_id
            WHERE ol.order_id = ? ORDER BY p.name
            """;
        List<ConcessionOrder.OrderLine> lines = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new ConcessionOrder.OrderLine(
                            rs.getLong("product_id"), rs.getString("name"),
                            rs.getInt("qty"), rs.getLong("unit_price")));
                }
            }
        }
        return lines;
    }

    /** Khóa + đọc đơn trong transaction của caller (chống xử lý trùng đồng thời). */
    public Optional<ConcessionOrder> findByIdLocked(Connection conn, long orderId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS
                + " FROM dbo.concession_order WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<ConcessionOrder> findById(long orderId) throws SQLException {
        try (Connection conn = DBContext.getConnection()) {
            return findById(conn, orderId);
        }
    }

    /** Read an order within the caller's transaction, including its uncommitted writes. */
    public Optional<ConcessionOrder> findById(Connection conn, long orderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + BASE_COLUMNS + " FROM dbo.concession_order WHERE id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Tra theo pickup code (Req 21.4 — luồng nhận hàng). */
    public Optional<ConcessionOrder> findByPickupCode(String pickupCode) throws SQLException {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + BASE_COLUMNS + " FROM dbo.concession_order WHERE pickup_code = ?")) {
            ps.setString(1, pickupCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Đơn của một khách (Req 16.5 — lịch sử mua). */
    public List<ConcessionOrder> findByUser(long userId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS
                + " FROM dbo.concession_order WHERE user_id = ? ORDER BY created_at DESC, id DESC";
        List<ConcessionOrder> orders = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) orders.add(mapRow(rs));
            }
        }
        return orders;
    }

    /**
     * Chuyển trạng thái guarded — chỉ đúng một request thắng khi hai thao tác đồng thời
     * trên cùng đơn (Req 20.3, 21.5).
     */
    public boolean updateStatusGuarded(Connection conn, long orderId, String newStatus,
                                       String expectedStatus, String cancelReason,
                                       LocalDateTime fulfilledAt) throws SQLException {
        String sql = """
            UPDATE dbo.concession_order
            SET status = ?, cancel_reason = ?, fulfilled_at = ?, version = version + 1
            WHERE id = ? AND status = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, cancelReason);
            if (fulfilledAt == null) ps.setNull(3, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(3, Timestamp.valueOf(fulfilledAt));
            ps.setLong(4, orderId);
            ps.setString(5, expectedStatus);
            return ps.executeUpdate() == 1;
        }
    }

    /** Quét đơn PENDING quá hạn chờ thanh toán → CANCELLED (Req 20.6, 21.7; scheduler 11.1). */
    public List<Long> findExpiredPendingOrderIds(Connection conn, int pendingMinutes) throws SQLException {
        String sql = """
            SELECT id FROM dbo.concession_order
            WHERE status = 'PENDING'
              AND created_at < DATEADD(minute, -?, SYSUTCDATETIME())
            """;
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pendingMinutes);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    /**
     * Các đơn gắn với một vé theo danh sách trạng thái, CÓ khóa hàng — dùng trong
     * transaction của caller cho luồng gộp thanh toán (Req 21.1) và cascade hủy vé (Req 21.6).
     */
    public List<Long> findIdsByTicketLocked(Connection conn, long ticketId,
                                            List<String> statuses) throws SQLException {
        StringBuilder sql = new StringBuilder("""
            SELECT id FROM dbo.concession_order WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
            WHERE ticket_id = ? AND status IN (
            """);
        for (int i = 0; i < statuses.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") ORDER BY id");
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, ticketId);
            for (int i = 0; i < statuses.size(); i++) {
                ps.setString(i + 2, statuses.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    /** Tổng tiền các đơn PENDING gắn vé (Req 21.1 — gộp vào tổng thanh toán của vé). */
    public long sumPendingTotalByTicket(Connection conn, long ticketId) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(total_amount), 0) FROM dbo.concession_order
            WHERE ticket_id = ? AND status = 'PENDING'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Các đơn gắn vé (mọi trạng thái) — hiển thị lịch sử/biên nhận. */
    public List<ConcessionOrder> findByTicket(Connection conn, long ticketId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS
                + " FROM dbo.concession_order WHERE ticket_id = ? ORDER BY id";
        List<ConcessionOrder> orders = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) orders.add(mapRow(rs));
            }
        }
        return orders;
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.BIGINT);
        else ps.setLong(index, value);
    }

    private ConcessionOrder mapRow(ResultSet rs) throws SQLException {
        ConcessionOrder order = new ConcessionOrder();
        order.setId(rs.getLong("id"));
        order.setOrderCode(rs.getString("order_code"));
        order.setBranchId(rs.getLong("branch_id"));
        long ticketId = rs.getLong("ticket_id");
        order.setTicketId(rs.wasNull() ? null : ticketId);
        long showtimeId = rs.getLong("showtime_id");
        order.setShowtimeId(rs.wasNull() ? null : showtimeId);
        long userId = rs.getLong("user_id");
        order.setUserId(rs.wasNull() ? null : userId);
        order.setStatus(rs.getString("status"));
        order.setTotalAmount(rs.getLong("total_amount"));
        // Phase 3: Points redemption
        try {
            order.setPointsUsed(rs.getInt("points_used"));
            order.setPointsDiscount(rs.getLong("points_discount"));
        } catch (Exception e) {
            order.setPointsUsed(0);
            order.setPointsDiscount(0);
        }
        order.setPickupCode(rs.getString("pickup_code"));
        Timestamp deadline = rs.getTimestamp("pickup_deadline");
        if (deadline != null) order.setPickupDeadline(deadline.toLocalDateTime());
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) order.setCreatedAt(created.toLocalDateTime());
        Timestamp fulfilled = rs.getTimestamp("fulfilled_at");
        if (fulfilled != null) order.setFulfilledAt(fulfilled.toLocalDateTime());
        long fulfilledByStaffId = rs.getLong("fulfilled_by_staff_id");
        order.setFulfilledByStaffId(rs.wasNull() ? null : fulfilledByStaffId);
        order.setCancelReason(rs.getString("cancel_reason"));
        order.setVersion(rs.getInt("version"));
        return order;
    }
}
