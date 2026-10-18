package com.cinema.model.auth;

/**
 * Lightweight customer projection trả về cho staff/manager tra cứu.
 * Tách khỏi {@link User} để tránh lộ password_hash, failed_login_count,
 * email_verification_token, v.v. trong response.
 *
 * @param id        user_account.id
 * @param email     email khách
 * @param phone     số điện thoại khách (chuẩn hoá 84…)
 * @param fullName  họ tên
 * @param status    ACTIVE | LOCKED | INACTIVE
 */
public record CustomerSummary(
        Long id,
        String email,
        String phone,
        String fullName,
        String status
) {}
