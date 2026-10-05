package com.cinema.auth;

import com.cinema.common.ServiceException;
import com.cinema.notification.EmailService;

import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Coordinates the Forgot Password flow:
 * <ol>
 *     <li>User submits email → service issues an OTP, stores a hash, and emails the plaintext.</li>
 *     <li>User submits OTP → service verifies hash + expiry + attempts.</li>
 *     <li>User submits new password → service updates hash and marks the OTP used.</li>
 * </ol>
 *
 * <p>Security rules:
 * <ul>
 *     <li>OTPs are 6-digit numeric strings generated server-side via {@link SecureRandom}.</li>
 *     <li>Plaintext OTP is NEVER persisted — only the BCrypt hash.</li>
 *     <li>OTPs expire after 5 minutes (configurable via {@link PasswordResetOtp#DEFAULT_TTL_MINUTES}).</li>
 *     <li>OTPs are single-use; the previous active OTP for the same user/purpose is invalidated
 *         when a new one is issued.</li>
 *     <li>OTPs are scoped by {@code purpose} so a Register email-verification OTP cannot
 *         be used to reset a password and vice versa.</li>
 *     <li>Rate limit: max 5 OTP requests per hour per user/purpose.</li>
 *     <li>Brute-force protection: after 5 failed attempts the OTP is invalidated.</li>
 *     <li>The "email does not exist" enumeration leak is prevented by always returning a
 *         neutral response ("If the email is registered, a code has been sent").</li>
 * </ul>
 */
public class PasswordResetService {

    private static final Logger logger = Logger.getLogger(PasswordResetService.class.getName());
    private static final SecureRandom RNG = new SecureRandom();

    private static final int MAX_REQUESTS_PER_HOUR = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private final PasswordResetOtpDAO otpDao;
    private final UserDAO userDao;
    private final EmailService emailService;

    public PasswordResetService() {
        this(new PasswordResetOtpDAO(), new UserDAO(), new EmailService());
    }

    public PasswordResetService(PasswordResetOtpDAO otpDao, UserDAO userDao, EmailService emailService) {
        this.otpDao = otpDao;
        this.userDao = userDao;
        this.emailService = emailService;
    }

    /**
     * Request an OTP for the given email. Always returns success (no enumeration leak)
     * unless the user has hit the rate limit.
     *
     * @return result with success flag and optional retry-after seconds
     */
    public RequestResult requestOtp(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            // Trả neutral success để attacker không enumerate được email nào tồn tại
            // — đây là nguyên tắc chống user enumeration cho forgot-password flow.
            return new RequestResult(true, false, RESEND_COOLDOWN_SECONDS);
        }
            Optional<User> userOpt;
        try {
            userOpt = userDao.findByEmail(email.trim().toLowerCase());
        } catch (Exception e) {
            logger.warning("findByEmail failed: " + e.getMessage());
            userOpt = Optional.empty();
        }
        if (userOpt.isEmpty()) {
            return new RequestResult(true, false, RESEND_COOLDOWN_SECONDS);
        }
        User user = userOpt.get();
        long userId = user.id();

        // Two-layer rate limit: hourly cap (chống brute-force) + per-request cooldown
        // (chống spam nút "gửi lại"). Nếu vượt thì trả retry-after để client show countdown.
        try {
            long recent = otpDao.countRecent(userId, PasswordResetOtp.PURPOSE_PASSWORD_RESET, 60);
            if (recent >= MAX_REQUESTS_PER_HOUR) {
                logger.warning("OTP rate limit exceeded for user " + userId);
                return new RequestResult(false, true, 3600);
            }
            var history = otpDao.recentHistory(userId, PasswordResetOtp.PURPOSE_PASSWORD_RESET, 60);
            if (!history.isEmpty()) {
                LocalDateTime lastCreated = history.get(0).getCreatedAt();
                if (lastCreated != null) {
                    long secsSinceLast = java.time.Duration.between(lastCreated, LocalDateTime.now()).getSeconds();
                    if (secsSinceLast < RESEND_COOLDOWN_SECONDS) {
                        return new RequestResult(true, true, (int) (RESEND_COOLDOWN_SECONDS - secsSinceLast));
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Rate-limit check failed: " + e.getMessage());
        }

        // Vô hiệu hoá OTP cũ trước khi tạo OTP mới — chỉ giữ 1 OTP ACTIVE/user
        // để tránh nhập nhằng giữa OTP cũ (có thể đã bị leak) và OTP mới.
        try {
            otpDao.invalidateActive(userId, PasswordResetOtp.PURPOSE_PASSWORD_RESET);
        } catch (Exception e) {
            logger.warning("Failed to invalidate previous OTPs: " + e.getMessage());
        }

        String otp = generateOtp();
        String hash = BCrypt.hashpw(otp, BCrypt.gensalt(10));
        LocalDateTime expires = LocalDateTime.now().plusMinutes(PasswordResetOtp.DEFAULT_TTL_MINUTES);
        PasswordResetOtp otpRow = new PasswordResetOtp(userId, hash, expires,
                PasswordResetOtp.PURPOSE_PASSWORD_RESET);
        try {
            otpDao.insert(otpRow);
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("OTP_INSERT_FAILED",
                    "Không thể tạo OTP: " + e.getMessage());
        }

        try {
            emailService.sendPasswordResetOtp(email.trim(), otp, PasswordResetOtp.DEFAULT_TTL_MINUTES);
        } catch (ServiceException emailErr) {
            logger.warning("Email send failed: " + emailErr.getMessage());
            // Swallow lỗi SMTP: OTP đã insert vào DB, user có thể yêu cầu resend.
            // Không throw để tránh lộ email có tồn tại hay không.
        }

        return new RequestResult(true, false, RESEND_COOLDOWN_SECONDS);
    }

    /**
     * Verify the OTP for a given email. On success, returns the userId to be used in the
     * subsequent reset-password call as a session-bound reset token.
     */
    public long verifyOtp(String email, String otp) {
        if (email == null || email.isBlank() || otp == null || otp.isBlank()) {
            throw new ServiceException.Validation("Thiếu email hoặc OTP");
        }
        User user;
        try {
            Optional<User> u = userDao.findByEmail(email.trim().toLowerCase());
            if (u.isEmpty()) throw new ServiceException.Validation("Mã OTP không hợp lệ hoặc đã hết hạn");
            user = u.get();
        } catch (ServiceException re) {
            throw re;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("USER_LOOKUP_FAILED", e.getMessage());
        }

        PasswordResetOtp active;
        try {
            Optional<PasswordResetOtp> opt = otpDao.findLatestActive(user.id(),
                    PasswordResetOtp.PURPOSE_PASSWORD_RESET);
            if (opt.isEmpty()) throw new ServiceException.Validation("Mã OTP không hợp lệ hoặc đã hết hạn");
            active = opt.get();
        } catch (ServiceException re) {
            throw re;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("OTP_LOOKUP_FAILED", e.getMessage());
        }

        if (active.isExpired()) throw new ServiceException.Validation("Mã OTP đã hết hạn");
        if (active.isUsed()) throw new ServiceException.Validation("Mã OTP đã được sử dụng");
        if (active.isBlocked()) throw new ServiceException.Validation("Mã OTP đã bị khóa do nhập sai quá nhiều lần");

        boolean matches;
        try {
            matches = BCrypt.checkpw(otp.trim(), active.getOtpHash());
        } catch (Exception e) {
            matches = false;
        }
        int newAttempts;
        try {
            newAttempts = otpDao.incrementAttempts(active.getId());
        } catch (Exception e) {
            newAttempts = active.getAttempts() + 1;
        }
        if (!matches) {
            if (newAttempts >= PasswordResetOtp.MAX_ATTEMPTS) {
                try { otpDao.markUsed(active.getId()); } catch (Exception ignored) { }
                throw new ServiceException.Validation("Mã OTP không đúng. Mã đã bị khóa do nhập sai quá nhiều lần.");
            }
            throw new ServiceException.Validation("Mã OTP không đúng. Còn "
                    + (PasswordResetOtp.MAX_ATTEMPTS - newAttempts) + " lần thử.");
        }
        // Trả userId để controller đưa vào session/reset-token; OTP vẫn ở trạng thái
        // ACTIVE+VERIFIED để resetPassword() re-verify + mark used ở bước kế tiếp.
        return user.id();
    }

    /**
     * Reset the password for a user after successful OTP verification.
     * Caller MUST supply the same email + OTP again so we re-verify and consume the OTP.
     */
    public void resetPassword(String email, String otp, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ServiceException.Validation("Mật khẩu phải có ít nhất 8 ký tự");
        }
        // Re-verify OTP tại đây để chặn race: user A mở 2 tab, tab1 verify xong
        // nhưng tab2 lỡ gọi resetPassword() với OTP đã stale — verifyOtp sẽ fail
        // vì OTP không còn active hoặc attempts đã max.
        long userId = verifyOtp(email, otp);

        String passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        try {
            userDao.updatePassword(userId, passwordHash);
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("PASSWORD_UPDATE_FAILED", e.getMessage());
        }

        // Defense in depth: mark OTP used + invalidate mọi OTP ACTIVE còn lại
        // (trường hợp có OTP race-condition chưa bị invalidation ở request trước).
        try {
            PasswordResetOtp active = otpDao.findLatestActive(userId,
                    PasswordResetOtp.PURPOSE_PASSWORD_RESET).orElse(null);
            if (active != null) {
                otpDao.markUsed(active.getId());
            }
            otpDao.invalidateActive(userId, PasswordResetOtp.PURPOSE_PASSWORD_RESET);
        } catch (Exception e) {
            logger.warning("Failed to mark OTP consumed: " + e.getMessage());
        }

        logger.info("Password reset completed for user " + userId);
    }

    /** Completes reset only after the servlet has established OTP verification in session. */
    public void resetPasswordAfterVerification(long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ServiceException.Validation("Mật khẩu phải có ít nhất 8 ký tự");
        }
        try {
            userDao.updatePassword(userId, BCrypt.hashpw(newPassword, BCrypt.gensalt(12)));
            logger.info("Password reset completed for verified user " + userId);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("PASSWORD_UPDATE_FAILED",
                    "Không thể cập nhật mật khẩu lúc này");
        }
    }

    /** Marks the successfully verified OTP as consumed before issuing reset access. */
    public void markOtpVerified(long userId) {
        try {
            PasswordResetOtp active = otpDao.findLatestActive(userId,
                    PasswordResetOtp.PURPOSE_PASSWORD_RESET).orElseThrow(
                    () -> new ServiceException.Validation("Mã OTP không hợp lệ hoặc đã hết hạn"));
            if (active.isExpired() || active.isBlocked()) {
                throw new ServiceException.Validation("Mã OTP không hợp lệ hoặc đã hết hạn");
            }
            otpDao.markUsed(active.getId());
            otpDao.invalidateActive(userId, PasswordResetOtp.PURPOSE_PASSWORD_RESET);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("OTP_UPDATE_FAILED",
                    "Không thể xác nhận OTP lúc này");
        }
    }

    /** Result of {@link #requestOtp(String)}. */
    public record RequestResult(boolean accepted, boolean cooldown, int retryAfterSeconds) { }

    private static String generateOtp() {
        // 6-digit code from SecureRandom
        int code = RNG.nextInt(1_000_000); // 0..999999
        return String.format("%06d", code);
    }
}
