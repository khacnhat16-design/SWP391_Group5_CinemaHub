package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Data access for staff_branch_assignment table. */
public class StaffBranchAssignmentDAO {

    /**
     * Insert new assignment.
     */
    public void insert(StaffBranchAssignment assignment) throws Exception {
        String sql = """
            INSERT INTO dbo.staff_branch_assignment
            (user_id, branch_id, assigned_by, effective_from, status)
            VALUES (?, ?, ?, SYSUTCDATETIME(), 'ACTIVE')
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, assignment.userId());
            ps.setLong(2, assignment.branchId());
            ps.setLong(3, assignment.assignedBy());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    assignment.setId(rs.getLong(1));
                }
            }
        }
    }

    /**
     * Find assignment by ID.
     */
    public Optional<StaffBranchAssignment> findById(Long id) throws Exception {
        String sql = """
            SELECT id, user_id, branch_id, effective_from, effective_to, status, assigned_by
            FROM dbo.staff_branch_assignment
            WHERE id = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Check if user has ACTIVE assignment to the branch.
     */
    public boolean hasActiveAssignment(Long userId, Long branchId) throws Exception {
        String sql = """
            SELECT 1 FROM dbo.staff_branch_assignment
            WHERE user_id = ? AND branch_id = ? AND status = 'ACTIVE'
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Find all ACTIVE branches for a user.
     */
    public Set<Long> findActiveBranchesByUser(Long userId) throws Exception {
        String sql = """
            SELECT branch_id FROM dbo.staff_branch_assignment
            WHERE user_id = ? AND status = 'ACTIVE'
            AND effective_from <= SYSUTCDATETIME()
            AND (effective_to IS NULL OR effective_to > SYSUTCDATETIME())
            """;

        Set<Long> branches = new HashSet<>();

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    branches.add(rs.getLong("branch_id"));
                }
            }
        }

        return branches;
    }

    /**
     * End assignment by marking as ENDED and setting effective_to.
     */
    public void endAssignment(Long assignmentId) throws Exception {
        String sql = """
            UPDATE dbo.staff_branch_assignment
            SET status = 'ENDED', effective_to = SYSUTCDATETIME()
            WHERE id = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, assignmentId);
            ps.executeUpdate();
        }
    }

    /**
     * Lấy danh sách staff_id (kèm fullName) ACTIVE thuộc branch — dùng cho week-grid
     * Manager để hiển thị rows trước cả khi chưa có assignment.
     */
    public java.util.List<java.util.Map<String, Object>> findStaffByBranch(Long branchId) throws Exception {
        String sql = """
            SELECT u.id AS user_id, u.full_name, u.email, u.role_code
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            WHERE sba.branch_id = ?
              AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.status = 'ACTIVE'
              AND u.role_code = 'BRANCH_STAFF'
            ORDER BY u.full_name
            """;
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("user_id"));
                    row.put("fullName", rs.getString("full_name"));
                    row.put("email", rs.getString("email"));
                    row.put("roleCode", rs.getString("role_code"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** Return active assignments with branch metadata for Admin assignment management. */
    public java.util.List<java.util.Map<String, Object>> findActiveAssignments(Long userId) throws Exception {
        String sql = """
            SELECT sba.id, sba.user_id, sba.branch_id, b.name AS branch_name,
                   sba.effective_from, sba.effective_to, sba.status
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.branch b ON b.id = sba.branch_id
            WHERE sba.user_id = ?
              AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
            ORDER BY b.name
            """;
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("userId", rs.getLong("user_id"));
                    row.put("branchId", rs.getLong("branch_id"));
                    row.put("branchName", rs.getString("branch_name"));
                    row.put("status", rs.getString("status"));
                    row.put("effectiveFrom", rs.getObject("effective_from"));
                    row.put("effectiveTo", rs.getObject("effective_to"));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private StaffBranchAssignment mapRow(ResultSet rs) throws Exception {
        StaffBranchAssignment assignment = new StaffBranchAssignment(
            rs.getLong("user_id"),
            rs.getLong("branch_id"),
            rs.getLong("assigned_by")
        );

        assignment.setId(rs.getLong("id"));
        assignment.setStatus(rs.getString("status"));

        Object fromObj = rs.getObject("effective_from");
        if (fromObj != null) {
            assignment.setEffectiveFrom(((java.sql.Timestamp) fromObj).toLocalDateTime());
        }

        Object toObj = rs.getObject("effective_to");
        if (toObj != null) {
            assignment.setEffectiveTo(((java.sql.Timestamp) toObj).toLocalDateTime());
        }

        return assignment;
    }
}
