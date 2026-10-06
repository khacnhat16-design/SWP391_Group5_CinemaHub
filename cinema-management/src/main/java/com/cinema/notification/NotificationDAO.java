package com.cinema.notification;

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

/** Data access for notification table (Req 23). */
public class NotificationDAO {

    private static final String BASE_COLUMNS =
            "id, user_id, type, title, body, is_read, created_at";

    /**
     * Insert thông báo. Nhận Connection tùy chọn: khi nằm trong transaction nghiệp vụ
     * chính (booking/cancel) dùng connection đó để thông báo rollback theo giao dịch;
     * khi null tự mở connection (best-effort — Req 23.4 lỗi gửi không rollback luồng chính).
     */
    public long insert(Connection conn, long userId, String type, String title, String body)
            throws SQLException {
        String sql = """
            INSERT INTO dbo.notification (user_id, type, title, body, is_read)
            VALUES (?, ?, ?, ?, 0)
            """;
        if (conn != null) {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                return executeInsert(ps, userId, type, title, body);
            }
        }
        try (Connection own = DBContext.getConnection();
             PreparedStatement ps = own.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            return executeInsert(ps, userId, type, title, body);
        }
    }

    private long executeInsert(PreparedStatement ps, long userId, String type,
                               String title, String body) throws SQLException {
        ps.setLong(1, userId);
        ps.setString(2, type);
        ps.setString(3, title);
        ps.setString(4, body);
        ps.executeUpdate();
        try (ResultSet rs = ps.getGeneratedKeys()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    /** Danh sách thông báo của một user (Req 23.5), mới nhất trước; lọc read/unread tùy chọn. */
    public List<Notification> findByUser(long userId, Boolean unreadOnly, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT " + BASE_COLUMNS + " FROM dbo.notification WHERE user_id = ?");
        if (Boolean.TRUE.equals(unreadOnly)) sql.append(" AND is_read = 0");
        sql.append(" ORDER BY created_at DESC, id DESC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");

        List<Notification> notifications = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) notifications.add(mapRow(rs));
            }
        }
        return notifications;
    }

    public Optional<Notification> findById(long id) throws SQLException {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.notification WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Đánh dấu đã đọc (Req 23.5). Guard user_id để không đánh dấu hộ thông báo
     * của tài khoản khác (IDOR — controller cũng chặn).
     */
    public boolean markRead(long notificationId, long userId) throws SQLException {
        String sql = "UPDATE dbo.notification SET is_read = 1 WHERE id = ? AND user_id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, notificationId);
            ps.setLong(2, userId);
            return ps.executeUpdate() == 1;
        }
    }

    /** Đánh dấu tất cả đã đọc (Req 23.5). */
    public int markAllRead(long userId) throws SQLException {
        String sql = "UPDATE dbo.notification SET is_read = 1 WHERE user_id = ? AND is_read = 0";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return ps.executeUpdate();
        }
    }

    /** Số thông báo chưa đọc (badge trên navbar). */
    public int countUnread(long userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM dbo.notification WHERE user_id = ? AND is_read = 0";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Vé CONFIRMED có suất chiếu bắt đầu trong khoảng nhắc (Req 23.3 — scheduler 11.1
     * gọi mỗi chu kỳ): trả (userId, ticketCode, startTime) cho các vé chưa được nhắc.
     */
    public List<ReminderCandidate> findReminderCandidates(int remindBeforeMinutes) throws SQLException {
        String sql = """
            SELECT t.user_id, t.ticket_code, s.start_time
            FROM dbo.ticket t
            JOIN dbo.showtime s ON s.id = t.showtime_id
            WHERE t.status = 'CONFIRMED' AND t.user_id IS NOT NULL
              AND s.status = 'OPEN'
              AND s.start_time > SYSUTCDATETIME()
              AND s.start_time <= DATEADD(minute, ?, SYSUTCDATETIME())
            """;
        List<ReminderCandidate> candidates = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, remindBeforeMinutes);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp start = rs.getTimestamp("start_time");
                    candidates.add(new ReminderCandidate(rs.getLong("user_id"),
                            rs.getString("ticket_code"),
                            start != null ? start.toLocalDateTime() : null));
                }
            }
        }
        return candidates;
    }

    /**
     * Check đã nhắc vé này chưa (idempotent — Req 23.3, scheduler chạy lặp không gửi trùng).
     */
    public boolean reminderAlreadySent(long userId, String ticketCode) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM dbo.notification
            WHERE user_id = ? AND type = 'SHOWTIME_REMINDER' AND body LIKE ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, "%" + ticketCode + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private Notification mapRow(ResultSet rs) throws SQLException {
        Notification notification = new Notification();
        notification.setId(rs.getLong("id"));
        notification.setUserId(rs.getLong("user_id"));
        notification.setType(rs.getString("type"));
        notification.setTitle(rs.getString("title"));
        notification.setBody(rs.getString("body"));
        notification.setRead(rs.getBoolean("is_read"));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) notification.setCreatedAt(created.toLocalDateTime());
        return notification;
    }

    /** Ứng viên nhắc suất chiếu (Req 23.3). */
    public record ReminderCandidate(long userId, String ticketCode,
                                    java.time.LocalDateTime startTime) { }
}
