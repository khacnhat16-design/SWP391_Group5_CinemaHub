package com.cinema.auth;

import java.time.LocalDateTime;

/** User account entity — shared across all roles (Admin, Manager, Staff, Customer). */
public class User {
    private Long id;
    public String email; // Package-private for DAO access
    public String phone;
    private String passwordHash;
    public String fullName;
    public Role role;
    private String status; // ACTIVE, LOCKED, INACTIVE
    private int failedLoginCount;
    private LocalDateTime lockedUntil;
    public LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private int version;
    private boolean emailVerified = true;
    private String verificationToken;
    private LocalDateTime verificationExpiresAt;

    public User() {}

    public User(String email, String phone, String passwordHash, String fullName, Role role) {
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.status = "ACTIVE";
        this.failedLoginCount = 0;
        this.emailVerified = true;
    }

    // -----------------------------------------------------------------------
    // Accessors — chỉ đọc, dùng trong JSP EL ${user.email()} hoặc từ controller.
    // -----------------------------------------------------------------------
    public Long id() { return id; }
    public String email() { return email; }
    public String phone() { return phone; }
    public String passwordHash() { return passwordHash; }
    public String fullName() { return fullName; }
    public Role role() { return role; }
    public String status() { return status; }
    public int failedLoginCount() { return failedLoginCount; }
    public LocalDateTime lockedUntil() { return lockedUntil; }
    public LocalDateTime lastLoginAt() { return lastLoginAt; }
    public LocalDateTime createdAt() { return createdAt; }
    public int version() { return version; }
    public boolean emailVerified() { return emailVerified; }
    public String verificationToken() { return verificationToken; }
    public LocalDateTime verificationExpiresAt() { return verificationExpiresAt; }

    // -----------------------------------------------------------------------
    // Mutators — DAO gọi để gán giá trị khi load từ DB; controller không nên
    // gọi trực tiếp, hãy dùng service layer.
    // -----------------------------------------------------------------------
    public void setId(Long id) { this.id = id; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setFailedLoginCount(int count) { this.failedLoginCount = count; }
    public void setLockedUntil(LocalDateTime until) { this.lockedUntil = until; }
    public void setLastLoginAt(LocalDateTime at) { this.lastLoginAt = at; }
    public void setStatus(String status) { this.status = status; }
    public void setVersion(int version) { this.version = version; }
    public void setCreatedAt(LocalDateTime at) { this.createdAt = at; }
    public void setPasswordHash(String hash) { this.passwordHash = hash; }
    public void setEmailVerified(boolean value) { this.emailVerified = value; }
    public void setVerificationToken(String value) { this.verificationToken = value; }
    public void setVerificationExpiresAt(LocalDateTime value) { this.verificationExpiresAt = value; }

    // -----------------------------------------------------------------------
    // Domain methods — derived state từ các field raw, không map xuống DB.
    // -----------------------------------------------------------------------
    public boolean isLocked() {
        return "LOCKED".equals(status) || (lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now()));
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
