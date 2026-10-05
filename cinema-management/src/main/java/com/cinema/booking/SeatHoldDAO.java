package com.cinema.booking;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Data access cho seat_hold + showtime_seat (Req 7.1-7.4, 8.1-8.6).
 *
 * <p>Mọi thao tác nhận Connection từ caller (transaction của BookingService) để giữ
 * lock nhất quán: SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK) theo seat_id tăng dần
 * (design.md — tránh deadlock, đúng một giao dịch thắng khi giữ cùng ghế).
 */
public class SeatHoldDAO {
    /** Thời hạn giữ ghế (Req 7.1): 10 phút. */
    public static final int HOLD_MINUTES = 10;

    /**
     * Khóa + đọc trạng thái ghế của một showtime theo danh sách seatId (đã sắp tăng dần).
     * Lazy expiry: ghế HOLD nhưng hold_expires_at đã qua được coi là AVAILABLE (Req 7.2, 8.4).
     */
    public SeatRow lockSeat(Connection conn, long showtimeId, long seatId) throws SQLException {
        String sql = """
            SELECT ss.seat_id, ss.status, ss.hold_id, ss.hold_expires_at,
                   se.row_label, se.col_no, se.seat_type
            FROM dbo.showtime_seat ss WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
            JOIN dbo.seat se ON se.id = ss.seat_id AND se.status = 'ACTIVE'
            WHERE ss.showtime_id = ? AND ss.seat_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            ps.setLong(2, seatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp expires = rs.getTimestamp("hold_expires_at");
                long holdId = rs.getLong("hold_id");
                SeatRow row = new SeatRow();
                row.seatId = rs.getLong("seat_id");
                row.status = rs.getString("status");
                row.holdId = rs.wasNull() ? null : holdId;
                row.holdExpiresAt = expires != null ? expires.toLocalDateTime() : null;
                row.rowLabel = rs.getString("row_label");
                row.colNo = rs.getInt("col_no");
                row.seatType = rs.getString("seat_type");
                return row;
            }
        }
    }

    /** INSERT seat_hold ACTIVE, expires_at = now + 10 phút; trả về hold id. */
    public long insertHold(Connection conn, long showtimeId, Long userId) throws SQLException {
        String sql = """
            INSERT INTO dbo.seat_hold (showtime_id, user_id, status, expires_at)
            VALUES (?, ?, 'ACTIVE', DATEADD(minute, ?, SYSUTCDATETIME()))
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, showtimeId);
            if (userId == null) ps.setNull(2, java.sql.Types.BIGINT);
            else ps.setLong(2, userId);
            ps.setInt(3, HOLD_MINUTES);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("Không tạo được bản ghi giữ ghế");
                return rs.getLong(1);
            }
        }
    }

    /** Đánh dấu ghế HOLD + gắn hold_id/hold_expires_at (sau khi đã lock row). */
    public void markSeatHeld(Connection conn, long showtimeId, long seatId,
                             long holdId, LocalDateTime expiresAt) throws SQLException {
        String sql = """
            UPDATE dbo.showtime_seat
            SET status = 'HOLD', hold_id = ?, hold_expires_at = ?
            WHERE showtime_id = ? AND seat_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            ps.setTimestamp(2, Timestamp.valueOf(expiresAt));
            ps.setLong(3, showtimeId);
            ps.setLong(4, seatId);
            ps.executeUpdate();
        }
    }

    /** Đánh dấu ghế SOLD + xóa hold_id/hold_expires_at khi confirm (Req 7.5). */
    public void markSeatSold(Connection conn, long showtimeId, long seatId) throws SQLException {
        String sql = """
            UPDATE dbo.showtime_seat
            SET status = 'SOLD', hold_id = NULL, hold_expires_at = NULL
            WHERE showtime_id = ? AND seat_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            ps.setLong(2, seatId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Không tìm thấy ghế của suất chiếu để giải phóng");
            }
        }
    }

    /** Giải phóng ghế về AVAILABLE (hold hết hạn / vé hủy — Req 7.4, 11.5). */
    public void releaseSeat(Connection conn, long showtimeId, long seatId) throws SQLException {
        String sql = """
            UPDATE dbo.showtime_seat
            SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL
            WHERE showtime_id = ? AND seat_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            ps.setLong(2, seatId);
            ps.executeUpdate();
        }
    }

    /** Khóa + đọc một hold theo id (confirm/cancel luồng). */
    public HoldRow lockHold(Connection conn, long holdId) throws SQLException {
        String sql = """
            SELECT id, showtime_id, user_id, status, expires_at
            FROM dbo.seat_hold WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
            WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long userId = rs.getLong("user_id");
                HoldRow row = new HoldRow();
                row.id = rs.getLong("id");
                row.showtimeId = rs.getLong("showtime_id");
                row.userId = rs.wasNull() ? null : userId;
                row.status = rs.getString("status");
                Timestamp expires = rs.getTimestamp("expires_at");
                row.expiresAt = expires != null ? expires.toLocalDateTime() : null;
                return row;
            }
        }
    }

    /** Chuyển trạng thái hold (ACTIVE → CONFIRMED/CANCELLED/EXPIRED), guard trạng thái cũ. */
    public boolean updateHoldStatus(Connection conn, long holdId, String newStatus,
                                    String expectedCurrentStatus) throws SQLException {
        String sql = "UPDATE dbo.seat_hold SET status = ? WHERE id = ? AND status = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setLong(2, holdId);
            ps.setString(3, expectedCurrentStatus);
            return ps.executeUpdate() == 1;
        }
    }

    /** Danh sách seat_id thuộc một hold (qua showtime_seat.hold_id), tăng dần. */
    public java.util.List<Long> findSeatIdsOfHold(Connection conn, long holdId) throws SQLException {
        String sql = """
            SELECT seat_id, showtime_id FROM dbo.showtime_seat
            WHERE hold_id = ? ORDER BY seat_id
            """;
        java.util.List<Long> seatIds = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) seatIds.add(rs.getLong("seat_id"));
            }
        }
        return seatIds;
    }

    /** showtime_id của hold (cần khi giải phóng ghế). */
    public long showtimeIdOfHold(Connection conn, long holdId) throws SQLException {
        String sql = "SELECT showtime_id FROM dbo.showtime_seat WHERE hold_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("showtime_id") : -1;
            }
        }
    }

    /**
     * Quét hold ACTIVE hết hạn → EXPIRED + giải phóng ghế (Req 7.4 lazy expiry;
     * scheduler 30s ở task 11.1 gọi cùng hàm). Idempotent.
     *
     * @return số hold đã giải phóng
     */
    public int expireElapsedHolds(Connection conn) throws SQLException {
        // Giải phóng ghế trước (còn tham chiếu hold_id), rồi mới đổi trạng thái hold
        String releaseSeats = """
            UPDATE dbo.showtime_seat
            SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL
            WHERE status = 'HOLD' AND hold_expires_at <= SYSUTCDATETIME()
            """;
        try (PreparedStatement ps = conn.prepareStatement(releaseSeats)) {
            ps.executeUpdate();
        }
        String expireHolds = """
            UPDATE dbo.seat_hold
            SET status = 'EXPIRED'
            WHERE status = 'ACTIVE' AND expires_at <= SYSUTCDATETIME()
            """;
        try (PreparedStatement ps = conn.prepareStatement(expireHolds)) {
            return ps.executeUpdate();
        }
    }

    /** Trạng thái một ghế trong showtime (đã lock). */
    public static class SeatRow {
        public long seatId;
        public String status;
        public Long holdId;
        public LocalDateTime holdExpiresAt;
        public String rowLabel;
        public int colNo;
        public String seatType;
    }

    /** Bản ghi seat_hold (đã lock). */
    public static class HoldRow {
        public long id;
        public long showtimeId;
        public Long userId;
        public String status;
        public LocalDateTime expiresAt;
    }
}
