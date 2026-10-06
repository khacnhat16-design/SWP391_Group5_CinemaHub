package com.cinema.booking;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access cho ticket + ticket_seat (Req 7, 8, 11, 12).
 *
 * <p>Mọi thao tác ghi nhận Connection từ caller (transaction của BookingService) —
 * chuyển trạng thái vé luôn check trạng thái hiện tại trong cùng transaction với
 * row lock (design.md State Management).
 */
public class TicketDAO {

    private static final String BASE_COLUMNS = """
        id, ticket_code, showtime_id, branch_id, user_id, status, total_amount,
        refund_amount, hold_id, created_at, confirmed_at,
        cancelled_at, used_at, used_by, version
        """;

    /** Insert vé PENDING + ghế giá snapshot; trả về vé đã có id. */
    public long insert(Connection conn, Ticket ticket) throws SQLException {
        String sql = """
            INSERT INTO dbo.ticket (ticket_code, showtime_id, branch_id, user_id, status,
                                    total_amount, hold_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ticket.ticketCode());
            ps.setLong(2, ticket.showtimeId());
            ps.setLong(3, ticket.branchId());
            if (ticket.userId() == null) ps.setNull(4, java.sql.Types.BIGINT);
            else ps.setLong(4, ticket.userId());
            ps.setString(5, ticket.status());
            ps.setLong(6, ticket.totalAmount());
            if (ticket.holdId() == null) ps.setNull(7, java.sql.Types.BIGINT);
            else ps.setLong(7, ticket.holdId());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("Không tạo được vé");
                long id = rs.getLong(1);
                ticket.setId(id);
                return id;
            }
        }
    }

    /** Ghi ghế + giá snapshot + ticket_type của vé (Req 7.5, 8, 9 — giá ghi nhận tại thời điểm bán). */
    public void insertSeats(Connection conn, long ticketId, List<Ticket.TicketSeat> seats) throws SQLException {
        String sql = """
            INSERT INTO dbo.ticket_seat (ticket_id, seat_id, price, ticket_type)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Ticket.TicketSeat seat : seats) {
                ps.setLong(1, ticketId);
                ps.setLong(2, seat.seatId());
                ps.setLong(3, seat.price());
                if (seat.ticketType() == null) {
                    ps.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(4, seat.ticketType());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Khóa + đọc vé theo id trong transaction của caller (Req 12.4 — hai lần soát đồng thời). */
    public Optional<Ticket> findByIdLocked(Connection conn, long ticketId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Khóa + đọc vé theo mã (luồng soát vé Req 12.1). */
        /** KhÃ³a + Ä‘á»c vÃ© theo hold_id (luá»“ng xÃ¡c nháº­n Ä‘áº·t vÃ©). */
    public Optional<Ticket> findByHoldIdLocked(Connection conn, long holdId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE hold_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Ticket> findByCodeLocked(Connection conn, String ticketCode) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE ticket_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ticketCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Đọc vé không khóa (hiển thị chi tiết). */
    public Optional<Ticket> findById(long ticketId) throws SQLException {
        try (Connection conn = dal.DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WHERE id = ?")) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Ghế + giá snapshot + ticket_type của vé. */
    public List<Ticket.TicketSeat> findSeats(Connection conn, long ticketId) throws SQLException {
        String sql = """
            SELECT ts.seat_id, ts.price, ts.ticket_type,
                   se.row_label, se.col_no, se.seat_type
            FROM dbo.ticket_seat ts
            JOIN dbo.seat se ON se.id = ts.seat_id
            WHERE ts.ticket_id = ? ORDER BY se.row_label, se.col_no
            """;
        List<Ticket.TicketSeat> seats = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    seats.add(new Ticket.TicketSeat(
                            rs.getLong("seat_id"),
                            rs.getString("row_label"),
                            rs.getInt("col_no"),
                            rs.getString("seat_type"),
                            rs.getString("ticket_type"),
                            rs.getLong("price")));
                }
            }
        }
        return seats;
    }

    /** Read the customer-facing details needed for a ticket confirmation notification. */
    public Optional<ConfirmationDetails> findConfirmationDetails(Connection conn, long ticketId)
            throws SQLException {
        String sql = """
            SELECT m.title, st.start_time, ts.seat_id, ts.price, ts.ticket_type,
                   se.row_label, se.col_no, se.seat_type
            FROM dbo.ticket t
            JOIN dbo.showtime st ON st.id = t.showtime_id
            JOIN dbo.movie m ON m.id = st.movie_id
            LEFT JOIN dbo.ticket_seat ts ON ts.ticket_id = t.id
            LEFT JOIN dbo.seat se ON se.id = ts.seat_id
            WHERE t.id = ?
            ORDER BY se.row_label, se.col_no
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();

                String movieTitle = rs.getString("title");
                LocalDateTime showtimeStart = rs.getTimestamp("start_time").toLocalDateTime();
                List<Ticket.TicketSeat> seats = new ArrayList<>();
                do {
                    if (rs.getObject("seat_id") != null) {
                        seats.add(new Ticket.TicketSeat(
                                rs.getLong("seat_id"),
                                rs.getString("row_label"),
                                rs.getInt("col_no"),
                                rs.getString("seat_type"),
                                rs.getString("ticket_type"),
                                rs.getLong("price")));
                    }
                } while (rs.next());
                return Optional.of(new ConfirmationDetails(movieTitle, showtimeStart, seats));
            }
        }
    }

    public record ConfirmationDetails(String movieTitle, LocalDateTime showtimeStart,
                                      List<Ticket.TicketSeat> seats) {}

    /**
     * Batch load seats for many tickets in a single SQL round-trip.
     * Returns a map: ticketId → list of seats. Eliminates the N+1 query that
     * {@code findSeats(conn, ticketId)} caused in {@code BookingController.ticketsForUser}.
     */
    public java.util.Map<Long, List<Ticket.TicketSeat>> findSeatsByTickets(Connection conn,
                                                                           java.util.Collection<Long> ticketIds)
            throws SQLException {
        if (ticketIds == null || ticketIds.isEmpty()) return java.util.Collections.emptyMap();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ticketIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        String sql = """
                SELECT ts.ticket_id, ts.seat_id, ts.price, ts.ticket_type,
                       se.row_label, se.col_no, se.seat_type
                FROM dbo.ticket_seat ts
                JOIN dbo.seat se ON se.id = ts.seat_id
                WHERE ts.ticket_id IN (
                """
                + placeholders + """
                )
                ORDER BY ts.ticket_id, se.row_label, se.col_no
                """;
        java.util.Map<Long, List<Ticket.TicketSeat>> grouped = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Long id : ticketIds) ps.setLong(idx++, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ticketId = rs.getLong("ticket_id");
                    Ticket.TicketSeat seat = new Ticket.TicketSeat(
                            rs.getLong("seat_id"),
                            rs.getString("row_label"),
                            rs.getInt("col_no"),
                            rs.getString("seat_type"),
                            rs.getString("ticket_type"),
                            rs.getLong("price"));
                    grouped.computeIfAbsent(ticketId, k -> new ArrayList<>()).add(seat);
                }
            }
        }
        return grouped;
    }

    /** Vé của một user theo thời gian giảm dần (Req 16.5 — lịch sử mua). */
    public List<Ticket> findByUser(long userId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WHERE user_id = ? ORDER BY created_at DESC, id DESC";
        List<Ticket> tickets = new ArrayList<>();
        try (Connection conn = dal.DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tickets.add(mapRow(rs));
            }
        }
        return tickets;
    }

    /**
     * PENDING → CONFIRMED guarded (Req 7.5, 8.2, 9.7): chỉ một giao dịch chuyển được;
     * confirm lần hai trượt guard → 0 rows.
     */
        public boolean confirmGuarded(Connection conn, long ticketId, long totalAmount,
                                  String voucherCode, int pointsEarned) throws SQLException {
        String sql = """
            UPDATE dbo.ticket
            SET status = 'CONFIRMED', total_amount = ?, voucher_code = ?, points_earned = ?,
                confirmed_at = SYSUTCDATETIME(), version = version + 1
            WHERE id = ? AND status = 'PENDING'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, totalAmount);
            ps.setString(2, voucherCode);
            ps.setInt(3, pointsEarned);
            ps.setLong(4, ticketId);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean confirmGuarded(Connection conn, long ticketId, long totalAmount) throws SQLException {
        String sql = """
            UPDATE dbo.ticket
            SET status = 'CONFIRMED', total_amount = ?,
                confirmed_at = SYSUTCDATETIME(), version = version + 1
            WHERE id = ? AND status = 'PENDING'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, totalAmount);
            ps.setLong(2, ticketId);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * CONFIRMED → CANCELLED guarded (Req 11.1, 11.6): hai yêu cầu hủy đồng thời →
     * đúng một thắng; ghi refund_amount theo bậc thang.
     */
    public boolean cancelGuarded(Connection conn, long ticketId, long refundAmount) throws SQLException {
        String sql = """
            UPDATE dbo.ticket
            SET status = 'CANCELLED', refund_amount = ?, cancelled_at = SYSUTCDATETIME(),
                version = version + 1
            WHERE id = ? AND status = 'CONFIRMED'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, refundAmount);
            ps.setLong(2, ticketId);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * PENDING → CANCELLED (hold hết hạn / thanh toán thất bại — Req 7.4, 9.4).
     * Không refund (chưa thanh toán).
     */
    public boolean cancelPendingGuarded(Connection conn, long ticketId) throws SQLException {
        String sql = """
            UPDATE dbo.ticket
            SET status = 'CANCELLED', cancelled_at = SYSUTCDATETIME(), version = version + 1
            WHERE id = ? AND status = 'PENDING'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * CONFIRMED → USED guarded (Req 12.3, 12.4): ghi thời điểm + người soát;
     * hai nhân viên soát đồng thời → đúng một thắng, lần sau bị chặn.
     */
    public boolean useGuarded(Connection conn, long ticketId, long validatorId) throws SQLException {
        // UPDLOCK + ROWLOCK chống race khi 2 staff quét cùng mã vé đồng thời:
        // request thứ 2 phải chờ row lock của request thứ 1, WHERE `status='CONFIRMED'`
        // sẽ loại nó ra → chỉ 1 request thắng, các request sau nhận 409.
        String sql = """
            UPDATE dbo.ticket WITH (UPDLOCK, ROWLOCK)
            SET status = 'USED', used_at = SYSUTCDATETIME(), used_by = ?, version = version + 1
            WHERE id = ? AND status = 'CONFIRMED'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, validatorId);
            ps.setLong(2, ticketId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Vé PENDING của hold hết hạn → CANCELLED hàng loạt (scheduler task 11.1). */
    public int cancelExpiredPendingTickets(Connection conn) throws SQLException {
        String sql = """
            UPDATE dbo.ticket
            SET status = 'CANCELLED', cancelled_at = SYSUTCDATETIME(), version = version + 1
            WHERE status = 'PENDING'
              AND created_at < DATEADD(minute, -?, SYSUTCDATETIME())
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, SeatHoldDAO.HOLD_MINUTES);
            return ps.executeUpdate();
        }
    }

    private Ticket mapRow(ResultSet rs) throws SQLException {
        Ticket ticket = new Ticket();
        ticket.setId(rs.getLong("id"));
        ticket.setTicketCode(rs.getString("ticket_code"));
        ticket.setShowtimeId(rs.getLong("showtime_id"));
        ticket.setBranchId(rs.getLong("branch_id"));
        long userId = rs.getLong("user_id");
        ticket.setUserId(rs.wasNull() ? null : userId);
        ticket.setStatus(rs.getString("status"));
        ticket.setTotalAmount(rs.getLong("total_amount"));
        long refund = rs.getLong("refund_amount");
        ticket.setRefundAmount(rs.wasNull() ? null : refund);
        long holdId = rs.getLong("hold_id");
        ticket.setHoldId(rs.wasNull() ? null : holdId);
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) ticket.setCreatedAt(created.toLocalDateTime());
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        if (confirmed != null) ticket.setConfirmedAt(confirmed.toLocalDateTime());
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        if (cancelled != null) ticket.setCancelledAt(cancelled.toLocalDateTime());
        Timestamp used = rs.getTimestamp("used_at");
        if (used != null) ticket.setUsedAt(used.toLocalDateTime());
        long usedBy = rs.getLong("used_by");
        ticket.setUsedBy(rs.wasNull() ? null : usedBy);
        ticket.setVersion(rs.getInt("version"));
        return ticket;
    }
}
