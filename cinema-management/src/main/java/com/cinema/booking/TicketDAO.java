package com.cinema.booking;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data access cho ticket + ticket_seat (Người 4 - Chức năng 3).
 *
 * <p>Mọi thao tác ghi nhận Connection từ caller (Transaction của BookingService) để
 * đảm bảo tính nhất quán (Atomicity), chuyển trạng thái vé luôn kiểm tra trạng thái
 * hiện tại trong cùng transaction với Row Lock.
 */
public class TicketDAO {

    private static final String BASE_COLUMNS = """
        id, ticket_code, showtime_id, branch_id, user_id, status, total_amount,
        voucher_code, refund_amount, points_earned, hold_id, created_at, confirmed_at,
        cancelled_at, used_at, used_by, version
        """;

    /** Insert vé PENDING; trả về vé đã có ID tự tăng. */
    public long insert(Connection conn, Ticket ticket) throws SQLException {
        String sql = """
            INSERT INTO dbo.ticket (ticket_code, showtime_id, branch_id, user_id, status,
                                    total_amount, voucher_code, hold_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ticket.getTicketCode());
            ps.setLong(2, ticket.getShowtimeId());
            ps.setLong(3, ticket.getBranchId());
            if (ticket.getUserId() == null) ps.setNull(4, java.sql.Types.BIGINT);
            else ps.setLong(4, ticket.getUserId());
            ps.setString(5, ticket.getStatus());
            ps.setLong(6, ticket.getTotalAmount());
            ps.setString(7, ticket.getVoucherCode());
            if (ticket.getHoldId() == null) ps.setNull(8, java.sql.Types.BIGINT);
            else ps.setLong(8, ticket.getHoldId());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("Không tạo được bản ghi vé");
                long id = rs.getLong(1);
                ticket.setId(id);
                return id;
            }
        }
    }

    /** Ghi ghế + giá snapshot của vé vào dbo.ticket_seat (Giá ghi nhận tại thời điểm bán). */
    public void insertSeats(Connection conn, long ticketId, List<Ticket.TicketSeat> seats) throws SQLException {
        if (seats == null || seats.isEmpty()) return;
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

    /** Khóa + đọc vé theo id trong transaction của caller (Row Lock). */
    public Optional<Ticket> findByIdLocked(Connection conn, long ticketId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Khóa + đọc vé theo hold_id trong transaction (Phục vụ xác nhận đặt vé). */
    public Optional<Ticket> findByHoldIdLocked(Connection conn, long holdId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WITH (UPDLOCK, HOLDLOCK, ROWLOCK) WHERE hold_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Khóa + đọc vé theo mã vé (ticket_code). */
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
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WHERE id = ?")) {
            ps.setLong(1, ticketId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /** Đọc danh sách ghế snapshot của một vé. */
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

    /**
     * Batch load ghế cho nhiều vé trong 1 câu SQL duy nhất (Tránh lỗi N+1 Query).
     */
    public Map<Long, List<Ticket.TicketSeat>> findSeatsByTickets(Connection conn,
                                                                 Collection<Long> ticketIds)
            throws SQLException {
        if (ticketIds == null || ticketIds.isEmpty()) return Collections.emptyMap();
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
        Map<Long, List<Ticket.TicketSeat>> grouped = new HashMap<>();
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

    /** Lấy danh sách vé của một người dùng theo thời gian giảm dần (Lịch sử đặt vé). */
    public List<Ticket> findByUser(long userId) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.ticket WHERE user_id = ? ORDER BY created_at DESC, id DESC";
        List<Ticket> tickets = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tickets.add(mapRow(rs));
            }
        }
        return tickets;
    }

    /**
     * Chuyển trạng thái PENDING -> CONFIRMED (Bảo vệ Optimistic Locking chống xác nhận 2 lần).
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

    /** CONFIRMED -> CANCELLED guarded */
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

    /** PENDING -> CANCELLED (khi hold hết hạn hoặc khách hủy) */
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

    /** CONFIRMED -> USED guarded khi nhân viên soát vé */
    public boolean useGuarded(Connection conn, long ticketId, long validatorId) throws SQLException {
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

    /** Vé PENDING của hold hết hạn -> CANCELLED hàng loạt */
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
        ticket.setVoucherCode(rs.getString("voucher_code"));
        long refund = rs.getLong("refund_amount");
        ticket.setRefundAmount(rs.wasNull() ? null : refund);
        ticket.setPointsEarned(rs.getInt("points_earned"));
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