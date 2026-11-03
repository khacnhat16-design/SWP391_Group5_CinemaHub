package com.cinema.dao.complaint;

import com.cinema.model.complaint.Complaint;

import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** DAO for dbo.complaint (RSC-4). */
public class ComplaintDAO {

    public long insert(Complaint c) throws Exception {
        String sql = """
                INSERT INTO dbo.complaint (user_id, branch_id, category, subject, message, related_ticket_id, status)
                OUTPUT INSERTED.id
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, c.userId());
            ps.setLong(2, c.branchId());
            ps.setString(3, c.category());
            ps.setString(4, c.subject());
            ps.setString(5, c.message());
            if (c.relatedTicketId() == null) ps.setNull(6, java.sql.Types.BIGINT);
            else ps.setLong(6, c.relatedTicketId());
            ps.setString(7, c.status() == null ? Complaint.STATUS_NEW : c.status());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    public boolean ticketBelongsToUserAndBranch(long ticketId, long userId, long branchId) throws Exception {
        String sql = """
                SELECT 1 FROM dbo.ticket
                WHERE id = ? AND user_id = ? AND branch_id = ?
                """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticketId);
            ps.setLong(2, userId);
            ps.setLong(3, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Complaint> findByUser(long userId) throws Exception {
        String sql = SELECT_BASE + " WHERE c.user_id = ? ORDER BY c.created_at DESC, c.id DESC";
        List<Complaint> out = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public List<Complaint> findAll(String status, Set<Long> branchIds) throws Exception {
        if (branchIds != null && branchIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(SELECT_BASE + " WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (branchIds != null) {
            sql.append(" AND c.branch_id IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(branchIds.size(), "?")));
            sql.append(")");
            params.addAll(branchIds);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY c.created_at DESC, c.id DESC");
        List<Complaint> out = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public Optional<Complaint> findById(long id) throws Exception {
        String sql = SELECT_BASE + " WHERE c.id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(map(rs));
            }
        }
        return Optional.empty();
    }

    public boolean updateReply(long id, String reply, Long managerId) throws Exception {
        String sql = """
                UPDATE dbo.complaint
                SET manager_reply = ?, replied_by = ?, replied_at = SYSUTCDATETIME(),
                    updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reply);
            if (managerId == null) ps.setNull(2, java.sql.Types.BIGINT);
            else ps.setLong(2, managerId);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateStatus(long id, String status, Long managerId) throws Exception {
        String sql = """
                UPDATE dbo.complaint
                SET status = ?, replied_by = ?, replied_at = ISNULL(replied_at, SYSUTCDATETIME()),
                    resolved_at = CASE WHEN ? = 'RESOLVED' THEN SYSUTCDATETIME() ELSE resolved_at END,
                    updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            if (managerId == null) ps.setNull(2, java.sql.Types.BIGINT);
            else ps.setLong(2, managerId);
            ps.setString(3, status);
            ps.setLong(4, id);
            return ps.executeUpdate() > 0;
        }
    }

    private static final String SELECT_BASE = """
            SELECT c.id, c.user_id, c.branch_id, b.name AS branch_name, u.full_name, u.email,
                   c.category, c.subject, c.message,
                   c.related_ticket_id, c.status, c.manager_reply, c.replied_by, c.replied_at,
                   c.resolved_at, c.created_at, c.updated_at
            FROM dbo.complaint c
            JOIN dbo.user_account u ON u.id = c.user_id
            LEFT JOIN dbo.branch b ON b.id = c.branch_id
            """;

    private Complaint map(ResultSet rs) throws Exception {
        Complaint c = new Complaint();
        c.setId(rs.getLong("id"));
        c.setUserId(rs.getLong("user_id"));
        long branchId = rs.getLong("branch_id");
        c.setBranchId(rs.wasNull() ? null : branchId);
        c.setBranchName(rs.getString("branch_name"));
        c.setUserFullName(rs.getString("full_name"));
        c.setUserEmail(rs.getString("email"));
        c.setCategory(rs.getString("category"));
        c.setSubject(rs.getString("subject"));
        c.setMessage(rs.getString("message"));
        long rid = rs.getLong("related_ticket_id");
        c.setRelatedTicketId(rs.wasNull() ? null : rid);
        c.setStatus(rs.getString("status"));
        c.setManagerReply(rs.getString("manager_reply"));
        long rb = rs.getLong("replied_by");
        c.setRepliedBy(rs.wasNull() ? null : rb);
        Timestamp ra = rs.getTimestamp("replied_at");
        if (ra != null) c.setRepliedAt(ra.toLocalDateTime());
        Timestamp res = rs.getTimestamp("resolved_at");
        if (res != null) c.setResolvedAt(res.toLocalDateTime());
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) c.setCreatedAt(ca.toLocalDateTime());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) c.setUpdatedAt(ua.toLocalDateTime());
        return c;
    }
}
