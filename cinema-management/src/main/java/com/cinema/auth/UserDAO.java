package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Data access for user_account table. */
public class UserDAO {

    
        public void insert(User user) throws Exception {
        String sql = """
            INSERT INTO dbo.user_account 
            (email, phone, password_hash, full_name, role_code, status, failed_login_count,
             email_verified, email_verification_token, email_verification_expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSUTCDATETIME())
            """;
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.email());
            ps.setString(2, user.phone());
            ps.setString(3, user.passwordHash());
            ps.setString(4, user.fullName());
            ps.setString(5, user.role().name());
            ps.setString(6, user.status());
            ps.setInt(7, user.failedLoginCount());
            ps.setBoolean(8, user.emailVerified());
            ps.setString(9, user.verificationToken());
            ps.setObject(10, user.verificationExpiresAt());
            
            ps.executeUpdate();
            
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    user.setId(rs.getLong(1));
                    user.setCreatedAt(LocalDateTime.now());
                }
            }
        }
    }

    /**
     * Find user by email.
     */
    public Optional<User> findByEmail(String email) throws Exception {
        String sql = """
            SELECT id, email, phone, password_hash, full_name, role_code, status, 
                   failed_login_count, locked_until, last_login_at, created_at, version,
                   email_verified, email_verification_token, email_verification_expires_at
            FROM dbo.user_account
            WHERE email = ?
            """;
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Find user by phone.
     */
    public Optional<User> findByPhone(String phone) throws Exception {
        String sql = """
            SELECT id, email, phone, password_hash, full_name, role_code, status,
                   failed_login_count, locked_until, last_login_at, created_at, version,
                   email_verified, email_verification_token, email_verification_expires_at
            FROM dbo.user_account
            WHERE phone = ?
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Find user by ID.
     */
    public Optional<User> findById(Long id) throws Exception {
        String sql = """
            SELECT id, email, phone, password_hash, full_name, role_code, status,
                   failed_login_count, locked_until, last_login_at, created_at, version,
                   email_verified, email_verification_token, email_verification_expires_at
            FROM dbo.user_account
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
     * Update failed login count.
     */
    public void updateFailedLoginCount(Long userId, int count) throws Exception {
        String sql = "UPDATE dbo.user_account SET failed_login_count = ? WHERE id = ?";
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, count);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Update locked_until timestamp (temporary lockout).
     */
    public void updateLockedUntil(Long userId, LocalDateTime lockedUntil) throws Exception {
        String sql = "UPDATE dbo.user_account SET locked_until = ? WHERE id = ?";
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, lockedUntil);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Reset failed login count to 0 (on successful login).
     */
    public void resetFailedLoginCount(Long userId) throws Exception {
        String sql = "UPDATE dbo.user_account SET failed_login_count = 0, locked_until = NULL WHERE id = ?";
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    /**
     * Update last login timestamp.
     */
    public void updateLastLoginAt(Long userId, LocalDateTime at) throws Exception {
        String sql = "UPDATE dbo.user_account SET last_login_at = ? WHERE id = ?";
        
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, at);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }
    
  /**
     * Update user profile (name and phone). Enforces phone uniqueness excluding own account.
     */
    public void updateProfile(Long userId, String fullName, String phone) throws Exception {
        // Loại trừ chính user đang update khỏi WHERE để cho phép giữ nguyên SĐT
        // cũ mà không bị false-positive "đã tồn tại".
        String checkSql = "SELECT 1 FROM dbo.user_account WHERE phone = ? AND id != ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, phone);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new RuntimeException("Phone number already in use");
                }
            }
        }

        String sql = "UPDATE dbo.user_account SET full_name = ?, phone = ? WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, phone);
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
    }

    private User mapRow(ResultSet rs) throws Exception {
        User user = new User();
        user.setId(rs.getLong("id"));
        user.email = rs.getString("email");
        user.phone = rs.getString("phone");
        user.setPasswordHash(rs.getString("password_hash"));
        user.setFullName(rs.getString("full_name"));
        user.role = Role.valueOf(rs.getString("role_code"));
        user.setStatus(rs.getString("status"));
        user.setFailedLoginCount(rs.getInt("failed_login_count"));

        Object lockedUntilObj = rs.getObject("locked_until");
        if (lockedUntilObj != null) {
            user.setLockedUntil(((java.sql.Timestamp) lockedUntilObj).toLocalDateTime());
        }

        Object lastLoginObj = rs.getObject("last_login_at");
        if (lastLoginObj != null) {
            user.lastLoginAt = ((java.sql.Timestamp) lastLoginObj).toLocalDateTime();
        }

        Object createdAtObj = rs.getObject("created_at");
        if (createdAtObj != null) {
            user.setCreatedAt(((java.sql.Timestamp) createdAtObj).toLocalDateTime());
        }

        user.setVersion(rs.getInt("version"));
        user.setEmailVerified(rs.getBoolean("email_verified"));
        user.setVerificationToken(rs.getString("email_verification_token"));
        Object verificationExpiry = rs.getObject("email_verification_expires_at");
        if (verificationExpiry != null) {
            user.setVerificationExpiresAt(((java.sql.Timestamp) verificationExpiry).toLocalDateTime());
        }
        return user;
    }}
