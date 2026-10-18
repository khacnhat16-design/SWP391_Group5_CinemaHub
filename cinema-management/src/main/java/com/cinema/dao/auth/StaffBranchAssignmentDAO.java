package com.cinema.dao.auth;

import com.cinema.common.ServiceException;
import com.cinema.model.auth.StaffBranchAssignment;

import com.cinema.infrastructure.database.DBContext;

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
        try (Connection conn = DBContext.getConnection()) {
            insert(conn, assignment);
        }
    }

    public void insert(Connection conn, StaffBranchAssignment assignment) throws Exception {
        String sql = """
            INSERT INTO dbo.staff_branch_assignment
            (user_id, branch_id, assigned_by, effective_from, status)
            VALUES (?, ?, ?, SYSUTCDATETIME(), 'ACTIVE')
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, assignment.userId());
            ps.setLong(2, assignment.branchId());
            ps.setLong(3, assignment.assignedBy());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) assignment.setId(rs.getLong(1));
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

    public boolean hasActiveAssignmentForRole(Long branchId, String roleCode) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return hasActiveAssignmentForRole(conn, branchId, roleCode);
        }
    }

    public void activateBranchIfStaffed(Long branchId) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String status;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM dbo.branch WITH (UPDLOCK, HOLDLOCK) WHERE id = ?")) {
                    ps.setLong(1, branchId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new ServiceException.NotFound("Chi nhánh không tồn tại");
                        status = rs.getString("status");
                    }
                }
                if (!"INACTIVE".equalsIgnoreCase(status)) {
                    throw new ServiceException.BusinessRule("BRANCH_NOT_INACTIVE",
                            "Chi nhánh không ở trạng thái ngưng hoạt động");
                }
                if (!hasActiveAssignmentForRole(conn, branchId, "BRANCH_MANAGER")
                        || !hasActiveAssignmentForRole(conn, branchId, "BRANCH_STAFF")) {
                    throw new ServiceException.BusinessRule("BRANCH_STAFFING_REQUIRED",
                            "Cần có Branch Manager và ít nhất một Branch Staff trước khi mở lại chi nhánh");
                }
                new com.cinema.dao.branch.BranchDAO().updateStatus(conn, branchId, "ACTIVE");
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }

    public boolean hasActiveAssignmentForRole(Connection conn, Long branchId, String roleCode)
            throws Exception {
        String sql = """
            SELECT TOP (1) 1
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            WHERE sba.branch_id = ? AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.status = 'ACTIVE' AND u.role_code = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setString(2, roleCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public int countActiveAssignmentsForRole(Long branchId, String roleCode) throws Exception {
        String sql = """
            SELECT COUNT(*)
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            WHERE sba.branch_id = ? AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.status = 'ACTIVE' AND u.role_code = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setString(2, roleCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
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
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long branchId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT branch_id FROM dbo.staff_branch_assignment WHERE id = ?")) {
                    ps.setLong(1, assignmentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ServiceException.NotFound("Phân công không tồn tại");
                        }
                        branchId = rs.getLong("branch_id");
                    }
                }

                String branchStatus;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT status FROM dbo.branch WITH (UPDLOCK, HOLDLOCK) WHERE id = ?
                        """)) {
                    ps.setLong(1, branchId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new ServiceException.NotFound("Chi nhánh không tồn tại");
                        branchStatus = rs.getString("status");
                    }
                }

                String assignmentStatus;
                String roleCode;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT sba.status AS assignment_status, u.role_code
                        FROM dbo.staff_branch_assignment sba WITH (UPDLOCK, HOLDLOCK)
                        JOIN dbo.user_account u ON u.id = sba.user_id
                        WHERE sba.id = ?
                        """)) {
                    ps.setLong(1, assignmentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new ServiceException.NotFound("Phân công không tồn tại");
                        }
                        assignmentStatus = rs.getString("assignment_status");
                        roleCode = rs.getString("role_code");
                    }
                }

                if ("ACTIVE".equalsIgnoreCase(assignmentStatus)
                        && "ACTIVE".equalsIgnoreCase(branchStatus)
                        && ("BRANCH_MANAGER".equals(roleCode) || "BRANCH_STAFF".equals(roleCode))
                        && countActiveAssignments(conn, branchId, roleCode) <= 1) {
                    throw new ServiceException.BusinessRule("BRANCH_STAFFING_REQUIRED",
                            "Không thể gỡ nhân sự cuối cùng khỏi chi nhánh đang hoạt động");
                }

                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE dbo.staff_branch_assignment
                        SET status = 'ENDED', effective_to = SYSUTCDATETIME()
                        WHERE id = ? AND status = 'ACTIVE'
                        """)) {
                    ps.setLong(1, assignmentId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }

    private int countActiveAssignments(Connection conn, Long branchId, String roleCode)
            throws Exception {
        String sql = """
            SELECT COUNT(*)
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            WHERE sba.branch_id = ? AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.status = 'ACTIVE' AND u.role_code = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setString(2, roleCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Lấy danh sách staff_id (kèm fullName) ACTIVE thuộc branch — dùng cho week-grid
     * Manager để hiển thị rows trước cả khi chưa có assignment.
     */
    public java.util.List<java.util.Map<String, Object>> findStaffByBranch(Long branchId) throws Exception {
        String sql = """
            SELECT sba.id AS assignment_id, u.id AS user_id, u.full_name, u.email, u.role_code
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
                    row.put("id", rs.getLong("assignment_id"));
                    row.put("assignmentId", rs.getLong("assignment_id"));
                    row.put("userId", rs.getLong("user_id"));
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

    /** Returns active branch names for each active Branch Manager and Branch Staff user. */
    public java.util.Map<Long, java.util.List<String>> findActiveBranchNamesByUser()
            throws Exception {
        return findActiveBranchNamesByUser(null);
    }

    /** Returns active branch names for all users, optionally limited to the caller's branches. */
    public java.util.Map<Long, java.util.List<String>> findActiveBranchNamesByUser(
            java.util.Set<Long> branchIds) throws Exception {
        if (branchIds != null && branchIds.isEmpty()) return java.util.Map.of();
        StringBuilder sql = new StringBuilder("""
            SELECT sba.user_id, b.name AS branch_name
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            JOIN dbo.branch b ON b.id = sba.branch_id
            WHERE sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
            """);
        if (branchIds != null) {
            sql.append(" AND sba.branch_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(branchIds.size(), "?")))
                    .append(")");
        }
        sql.append("""
              AND u.status = 'ACTIVE'
              AND u.role_code IN ('BRANCH_MANAGER', 'BRANCH_STAFF')
            ORDER BY u.id, b.name
            """);
        java.util.Map<Long, java.util.List<String>> map = new java.util.LinkedHashMap<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (branchIds != null) {
                int index = 1;
                for (Long branchId : branchIds) ps.setLong(index++, branchId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long userId = rs.getLong("user_id");
                    String branchName = rs.getString("branch_name");
                    map.computeIfAbsent(userId, key -> new java.util.ArrayList<>()).add(branchName);
                }
            }
        }
        return map;
    }

    /**
     * Tra branchId từ assignmentId — dùng để kiểm tra scope trước khi cho phép
     * BRM xóa phân công.
     */
    public java.util.Optional<Long> findBranchIdByAssignmentId(long assignmentId) throws Exception {
        String sql = "SELECT branch_id FROM dbo.staff_branch_assignment WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, assignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(rs.getLong(1))
                        : java.util.Optional.empty();
            }
        }
    }

    /**
     * Tra branchId + roleCode của user trong assignment — dùng để BRM không thể
     * xóa phân công BRM (chỉ Admin mới có quyền đổi BRM của chi nhánh).
     */
    public java.util.Optional<java.util.Map<String, Object>> findAssignmentContext(long assignmentId)
            throws Exception {
        String sql = """
            SELECT sba.branch_id, u.role_code
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            WHERE sba.id = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, assignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("branchId", rs.getLong("branch_id"));
                row.put("roleCode", rs.getString("role_code"));
                return java.util.Optional.of(row);
            }
        }
    }

    /**
     * Trả về tất cả user (BRM + BRANCH_STAFF) ACTIVE thuộc branch — dùng cho
     * popup phân công để frontend phân biệt BRM (radio) và Staff (checkbox).
     * Method cũ {@link #findStaffByBranch} chỉ trả STAFF; popup gán chi nhánh cần
     * cả BRANCH_MANAGER và BRANCH_STAFF.
     */
    public java.util.List<java.util.Map<String, Object>> findAssignedUsersByBranch(Long branchId)
            throws Exception {
        String sql = """
            SELECT sba.id AS assignment_id, u.id AS user_id, u.full_name, u.email, u.role_code
            FROM dbo.staff_branch_assignment sba
            JOIN dbo.user_account u ON u.id = sba.user_id
            WHERE sba.branch_id = ?
              AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.status = 'ACTIVE'
              AND u.role_code IN ('BRANCH_MANAGER', 'BRANCH_STAFF')
            ORDER BY CASE u.role_code
                         WHEN 'BRANCH_MANAGER' THEN 0
                         ELSE 1
                     END, u.full_name
            """;
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong("assignment_id"));
                    row.put("assignmentId", rs.getLong("assignment_id"));
                    row.put("userId", rs.getLong("user_id"));
                    row.put("fullName", rs.getString("full_name"));
                    row.put("email", rs.getString("email"));
                    row.put("roleCode", rs.getString("role_code"));
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
