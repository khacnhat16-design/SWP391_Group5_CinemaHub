package com.cinema.dao.legacy;

import com.cinema.common.ServiceException;
import com.cinema.dao.auth.UserDAO;
import com.cinema.infrastructure.database.DBContext;

import com.cinema.model.auth.Role;
import com.cinema.util.PasswordUtil;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO extension cho Admin Users screen (CRUD).
 * Không đụng tới UserDAO hiện có của auth (giữ logic lockout/intrusion nguyên bản),
 * nhưng dùng chung table dbo.user_account.
 */
public class UserDAOext {

    /** Bộ ký tự dùng để sinh mật khẩu tạm thời (tránh ký tự dễ nhầm lẫn O/0/l/1). */
    private static final String TEMP_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record UserRecord(long id, String email, String phone, String fullName,
                              Role role, String status, LocalDateTime createdAt,
                              LocalDateTime lastLoginAt) { }

    public List<UserRecord> list(String q, Role role, int page, int pageSize) throws Exception {
        int safePage = Math.max(page, 0);
        int safeSize = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        int offset = safePage * safeSize;

        StringBuilder sql = new StringBuilder("""
            SELECT id, email, phone, full_name, role_code, status, created_at, last_login_at
            FROM dbo.user_account
            WHERE 1=1
            """);
        List<Object> params = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (phone LIKE ? OR full_name LIKE ? OR email LIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        if (role != null) {
            sql.append(" AND role_code = ?");
            params.add(role.name());
        }
        sql.append(" ORDER BY created_at DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(offset);
        params.add(safeSize);

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            List<UserRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }

            return out;
        }
    }

    public List<UserRecord> listByBranch(String q, Role role, long branchId, int page, int pageSize)
            throws Exception {
        int safePage = Math.max(page, 0);
        int safeSize = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT u.id, u.email, u.phone, u.full_name, u.role_code, u.status,
                            u.created_at, u.last_login_at
            FROM dbo.user_account u
            JOIN dbo.staff_branch_assignment sba ON sba.user_id = u.id
            WHERE sba.branch_id = ? AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.role_code IN ('BRANCH_MANAGER', 'BRANCH_STAFF')
            """);
        List<Object> params = new ArrayList<>();
        params.add(branchId);
        if (q != null && !q.isBlank()) {
            sql.append(" AND (u.phone LIKE ? OR u.full_name LIKE ? OR u.email LIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            sql.append(" AND u.role_code = ?");
            params.add(role.name());
        }
        sql.append(" ORDER BY u.created_at DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(safePage * safeSize);
        params.add(safeSize);
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            List<UserRecord> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
            return out;
        }
    }

    /**
     * Tổng số user khớp filter (không phụ thuộc phân trang) — dùng để trả
     * {@code total} cho list-page.js hiển thị "X-Y / N mục" và tính số trang.
     */
    public long count(String q, Role role) throws Exception {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM dbo.user_account WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (phone LIKE ? OR full_name LIKE ? OR email LIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            sql.append(" AND role_code = ?");
            params.add(role.name());
        }
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Tổng user thuộc branch và khớp filter — dùng cho list ByBranch. */
    public long countByBranch(String q, Role role, long branchId) throws Exception {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(DISTINCT u.id)
            FROM dbo.user_account u
            JOIN dbo.staff_branch_assignment sba ON sba.user_id = u.id
            WHERE sba.branch_id = ? AND sba.status = 'ACTIVE'
              AND sba.effective_from <= SYSUTCDATETIME()
              AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
              AND u.role_code IN ('BRANCH_MANAGER', 'BRANCH_STAFF')
            """);
        List<Object> params = new ArrayList<>();
        params.add(branchId);
        if (q != null && !q.isBlank()) {
            sql.append(" AND (u.phone LIKE ? OR u.full_name LIKE ? OR u.email LIKE ?)");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (role != null) {
            sql.append(" AND u.role_code = ?");
            params.add(role.name());
        }
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public UserRecord findById(long id) throws Exception {
        String sql = """
            SELECT id, email, phone, full_name, role_code, status, created_at, last_login_at
            FROM dbo.user_account WHERE id = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }

    /**
     * Tạo user với mật khẩu tạm thời được sinh tự động.
     *
     * @return {@link CreateResult} chứa id user và plaintext password (chỉ trả về khi tự sinh).
     *         Caller chịu trách nhiệm gửi email cho user; password plaintext KHÔNG lưu DB.
     */
    public CreateResult create(String email, String phone, String fullName, Role role) throws Exception {
        // Phát hiện trùng email/phone trước khi insert để trả 409 thân thiện;
        // UNIQUE index ở DB vẫn là lớp bảo vệ cuối cùng cho race condition.
        if (findByEmail(email) != null) {
            throw new com.cinema.common.ServiceException.Conflict("Email đã tồn tại");
        }
        if (findByPhone(phone) != null) {
            throw new com.cinema.common.ServiceException.Conflict("Số điện thoại đã tồn tại");
        }
        String password = generateTemporaryPassword();
        String hash = PasswordUtil.hash(password);
        String sql = """
            INSERT INTO dbo.user_account
              (email, phone, password_hash, full_name, role_code, status,
               failed_login_count, version, email_verified)
            OUTPUT INSERTED.id
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, 0, 1)
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, phone);
            ps.setString(3, hash);
            ps.setString(4, fullName);
            ps.setString(5, role.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    // Nếu là CUSTOMER, tạo customer_profile luôn để tránh FK lủng.
                    if (role == Role.CUSTOMER) {
                        ensureCustomerProfile(conn, id);
                    }
                    return new CreateResult(id, password);
                }
            }
        }
        throw new com.cinema.common.ServiceException.NotFound("Không thể tạo user");
    }

    /**
     * Kết quả của {@link #create}; caller phải gửi mật khẩu qua email và KHÔNG log ra
     * response thường trực.
     */
    public record CreateResult(long id, String temporaryPassword) { }

    /** Sinh mật khẩu tạm thời gồm chữ + số, dài mặc định 12 ký tự. */
    public static String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    public void update(long id, String fullName, String phone, String status) throws Exception {
        StringBuilder sql = new StringBuilder("UPDATE dbo.user_account SET ");
        List<Object> params = new ArrayList<>();
        if (fullName != null && !fullName.isBlank()) {
            sql.append("full_name = ?");
            params.add(fullName);
        }
        if (phone != null && !phone.isBlank()) {
            if (!params.isEmpty()) sql.append(", ");
            sql.append("phone = ?");
            params.add(phone);
        }
        if (status != null && !status.isBlank()) {
            if (!params.isEmpty()) sql.append(", ");
            sql.append("status = ?");
            params.add(status);
        }
        if (params.isEmpty()) return;
        sql.append(", version = version + 1 WHERE id = ?");
        params.add(id);

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            ps.executeUpdate();
        }
    }

    public void updateStatus(long id, String status) throws Exception {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE dbo.user_account SET status = ?, version = version + 1 WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void softDelete(long id) throws Exception {
        updateStatus(id, "INACTIVE");
    }

    // ---- helpers ----

    private UserRecord mapRow(ResultSet rs) throws java.sql.SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("full_name"),
                Role.valueOf(rs.getString("role_code")),
                rs.getString("status"),
                ts(rs.getTimestamp("created_at")),
                ts(rs.getTimestamp("last_login_at"))
        );
    }

    private static LocalDateTime ts(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private UserRecord findByEmail(String email) throws Exception {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM dbo.user_account WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new UserRecord(rs.getLong(1), email, null, null, null, null, null, null);
            }
        }
        return null;
    }

    private UserRecord findByPhone(String phone) throws Exception {
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM dbo.user_account WHERE phone = ?")) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new UserRecord(rs.getLong(1), null, phone, null, null, null, null, null);
            }
        }
        return null;
    }

    private void ensureCustomerProfile(Connection conn, long userId) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "IF NOT EXISTS (SELECT 1 FROM dbo.customer_profile WHERE user_id = ?) "
                + "INSERT INTO dbo.customer_profile(user_id) VALUES (?)")) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }
}
