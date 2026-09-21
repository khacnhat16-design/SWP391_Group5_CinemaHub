package com.cinema.auth;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DAO for {@link PasswordResetOtp} (table {@code dbo.password_reset_otp}).
 *
 * <p>
 * This table is reused for both Register email verification and Forgot Password
 * OTP;
 * they are distinguished by the {@code purpose} column so an OTP issued for one
 * flow
 * cannot verify the other.
 */
public class PasswordResetOtpDAO {

    /**
     * Insert a new OTP. Caller must hash the OTP before passing it in.
     */
    public long insert(PasswordResetOtp otp) throws Exception {
        String sql = """
                INSERT INTO dbo.password_reset_otp
                  (user_id, otp_hash, expires_at, attempts, purpose)
                VALUES (?, ?, ?, 0, ?)
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, otp.getUserId());
            ps.setString(2, otp.getOtpHash());
            ps.setTimestamp(3, Timestamp.valueOf(otp.getExpiresAt()));
            ps.setString(4, otp.getPurpose());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    otp.setId(id);
                    return id;
                }
            }
            throw new IllegalStateException("Không lấy được ID OTP vừa tạo");
        }
    }

    /**
     * Find the most recent unused OTP for a user with a given purpose.
     * Used to invalidate previous OTPs before issuing a new one.
     */
    public Optional<PasswordResetOtp> findLatestActive(long userId, String purpose) throws Exception {
        String sql = """
                SELECT id, user_id, otp_hash, expires_at, used_at, attempts, purpose, created_at
                FROM dbo.password_reset_otp
                WHERE user_id = ? AND purpose = ? AND used_at IS NULL
                ORDER BY created_at DESC
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, purpose);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Invalidate all active OTPs for a user + purpose by marking them used.
     * Used when issuing a new OTP (so the old one can't be used in parallel).
     */
    public int invalidateActive(long userId, String purpose) throws Exception {
        String sql = """
                UPDATE dbo.password_reset_otp
                SET used_at = SYSUTCDATETIME()
                WHERE user_id = ? AND purpose = ? AND used_at IS NULL
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, purpose);
            return ps.executeUpdate();
        }
    }

    /** Increment attempt counter and return the new value. */
    public int incrementAttempts(long otpId) throws Exception {
        String sql = """
                UPDATE dbo.password_reset_otp
                SET attempts = attempts + 1
                WHERE id = ?
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, otpId);
            ps.executeUpdate();
            try (ResultSet rs = ps.executeQuery("SELECT attempts FROM dbo.password_reset_otp WHERE id = " + otpId)) {
                if (rs.next())
                    return rs.getInt(1);
            }
        }
        return 0;
    }

    /** Mark OTP as used (consumed). */
    public void markUsed(long otpId) throws Exception {
        String sql = """
                UPDATE dbo.password_reset_otp
                SET used_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, otpId);
            ps.executeUpdate();
        }
    }

    /**
     * Count recent OTP requests for rate-limiting (max 5 per hour per
     * user/purpose).
     */
    public long countRecent(long userId, String purpose, int withinMinutes) throws Exception {
        String sql = """
                SELECT COUNT(*) FROM dbo.password_reset_otp
                WHERE user_id = ? AND purpose = ?
                  AND created_at > DATEADD(minute, -?, SYSUTCDATETIME())
                """;
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, purpose);
            ps.setInt(3, withinMinutes);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getLong(1);
            }
        }
        return 0L;
    }

    /** Recent OTP history (for rate-limit response — last send time). */
    public List<PasswordResetOtp> recentHistory(long userId, String purpose, int withinMinutes) throws Exception {
        String sql = """
                SELECT id, user_id, otp_hash, expires_at, used_at, attempts, purpose, created_at
                FROM dbo.password_reset_otp
                WHERE user_id = ? AND purpose = ?
                  AND created_at > DATEADD(minute, -?, SYSUTCDATETIME())
                ORDER BY created_at DESC
                """;
        List<PasswordResetOtp> list = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, purpose);
            ps.setInt(3, withinMinutes);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(mapRow(rs));
            }
        }
        return list;
    }

    private PasswordResetOtp mapRow(ResultSet rs) throws Exception {
        PasswordResetOtp otp = new PasswordResetOtp();
        otp.setId(rs.getLong("id"));
        otp.setUserId(rs.getLong("user_id"));
        otp.setOtpHash(rs.getString("otp_hash"));
        Timestamp exp = rs.getTimestamp("expires_at");
        otp.setExpiresAt(exp != null ? exp.toLocalDateTime() : null);
        Timestamp used = rs.getTimestamp("used_at");
        otp.setUsedAt(used != null ? used.toLocalDateTime() : null);
        otp.setAttempts(rs.getInt("attempts"));
        otp.setPurpose(rs.getString("purpose"));
        Timestamp created = rs.getTimestamp("created_at");
        otp.setCreatedAt(created != null ? created.toLocalDateTime() : null);
        return otp;
    }
}
