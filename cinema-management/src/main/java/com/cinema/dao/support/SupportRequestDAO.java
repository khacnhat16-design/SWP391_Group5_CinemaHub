package com.cinema.dao.support;

import com.cinema.model.support.SupportRequest;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** DAO for dbo.support_request (RSC-3). */
public class SupportRequestDAO {

    public long insert(SupportRequest sr) throws Exception {
        String sql = """
                INSERT INTO dbo.support_request (user_id, subject, message, status)
                OUTPUT INSERTED.id
                VALUES (?, ?, ?, ?)
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sr.userId());
            ps.setString(2, sr.subject());
            ps.setString(3, sr.message());
            ps.setString(4, sr.status() == null ? SupportRequest.STATUS_OPEN : sr.status());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    public List<SupportRequest> findByUser(long userId) throws Exception {
        String sql = SELECT_BASE + " WHERE s.user_id = ? ORDER BY s.created_at DESC, s.id DESC";
        List<SupportRequest> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public List<SupportRequest> findAll(String status) throws Exception {
        StringBuilder sql = new StringBuilder(SELECT_BASE + " WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND s.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY s.created_at DESC, s.id DESC");
        List<SupportRequest> out = new ArrayList<>();
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public Optional<SupportRequest> findById(long id) throws Exception {
        String sql = SELECT_BASE + " WHERE s.id = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public boolean updateReply(long id, String reply, long managerId, String newStatus) throws Exception {
        String sql = """
                UPDATE dbo.support_request
                SET manager_reply = ?, replied_by = ?, replied_at = SYSUTCDATETIME(),
                    status = ?, updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reply);
            ps.setLong(2, managerId);
            ps.setString(3, newStatus);
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateStatus(long id, String status) throws Exception {
        String sql = "UPDATE dbo.support_request SET status = ?, updated_at = SYSUTCDATETIME() WHERE id = ?";
        try (Connection c = DBContext.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    private static final String SELECT_BASE = """
            SELECT s.id, s.user_id, u.full_name, u.email, s.subject, s.message, s.status,
                   s.manager_reply, s.replied_by, s.replied_at, s.created_at, s.updated_at
            FROM dbo.support_request s
            JOIN dbo.user_account u ON u.id = s.user_id
            """;

    private SupportRequest map(ResultSet rs) throws Exception {
        SupportRequest s = new SupportRequest();
        s.setId(rs.getLong("id"));
        s.setUserId(rs.getLong("user_id"));
        s.setUserFullName(rs.getString("full_name"));
        s.setUserEmail(rs.getString("email"));
        s.setSubject(rs.getString("subject"));
        s.setMessage(rs.getString("message"));
        s.setStatus(rs.getString("status"));
        s.setManagerReply(rs.getString("manager_reply"));
        long rb = rs.getLong("replied_by");
        s.setRepliedBy(rs.wasNull() ? null : rb);
        Timestamp ra = rs.getTimestamp("replied_at");
        if (ra != null) s.setRepliedAt(ra.toLocalDateTime());
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) s.setCreatedAt(ca.toLocalDateTime());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) s.setUpdatedAt(ua.toLocalDateTime());
        return s;
    }
}
