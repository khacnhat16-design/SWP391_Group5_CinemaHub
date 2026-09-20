package com.cinema.auth;

import java.time.LocalDateTime;

/**
 * Password reset OTP record. Maps to {@code dbo.password_reset_otp} table.
 * The {@code otpHash} field stores a BCrypt hash of the original 6-digit code
 * (plaintext is NEVER persisted).
 */
public class PasswordResetOtp {

    public static final String PURPOSE_PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PURPOSE_REGISTER_VERIFY = "REGISTER_EMAIL_VERIFICATION";

    /** Max attempts allowed before the OTP is auto-invalidated. */
    public static final int MAX_ATTEMPTS = 5;

    /** Default OTP lifetime: 5 minutes. */
    public static final int DEFAULT_TTL_MINUTES = 5;

    private Long id;
    private Long userId;
    private String otpHash;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private int attempts;
    private String purpose;
    private LocalDateTime createdAt;

    public PasswordResetOtp() { }

    public PasswordResetOtp(Long userId, String otpHash, LocalDateTime expiresAt, String purpose) {
        this.userId = userId;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
        this.purpose = purpose;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isBlocked() {
        return attempts >= MAX_ATTEMPTS;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getOtpHash() { return otpHash; }
    public void setOtpHash(String otpHash) { this.otpHash = otpHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
