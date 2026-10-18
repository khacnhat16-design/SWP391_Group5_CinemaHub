package com.cinema.dao.legacy;

import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.notification.Notification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Notification DAO dùng cho header bell + workspace unread count.
 * Tôn trọng branch scope qua AccessScope (nếu có thể truy cập nhiều chi nhánh).
 *
 * <p>Lưu ý: schema hiện tại gắn notification theo user_id, không theo branch.
 * Do đó với Manager/Staff ta trả unread của riêng user họ (notification cá nhân).
 * Với Admin ta lấy unread của một nhóm user nhỏ (placeholder: chính admin).
 *
 * <p>Nếu sau này notification có broadcast theo branch, mở rộng tại đây.
 */
public class NotificationDAO {

    public record NotificationRow(long id, String type, String title, String body,
                                  boolean read, LocalDateTime createdAt) { }

    public List<NotificationRow> findRecentForCurrent(Long userId, int limit) {
        if (userId == null) return Collections.emptyList();

        String sql = """
            SELECT TOP (?) id, type, title, body, is_read, created_at
            FROM dbo.notification
            WHERE user_id = ?
            ORDER BY created_at DESC
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setLong(2, userId);
            List<NotificationRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    rows.add(new NotificationRow(
                            rs.getLong("id"),
                            rs.getString("type"),
                            rs.getString("title"),
                            rs.getString("body"),
                            rs.getBoolean("is_read"),
                            ts == null ? null : ts.toLocalDateTime()));
                }
            }
            return rows;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public int countUnread(Long userId) {
        if (userId == null) return 0;
        String sql = "SELECT COUNT(*) FROM dbo.notification WHERE user_id = ? AND is_read = 0";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
