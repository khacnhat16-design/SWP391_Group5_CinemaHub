package com.cinema.notification;

import com.cinema.common.ServiceException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Email sender for transactional messages (OTP delivery, etc).
 *
 * <p>Configuration is read from environment variables (never hard-coded):
 * <ul>
 *     <li>{@code MAIL_HOST}     - SMTP host (default {@code smtp.gmail.com})</li>
 *     <li>{@code MAIL_PORT}     - SMTP port (default {@code 587})</li>
 *     <li>{@code MAIL_USERNAME} - SMTP account / sender address</li>
 *     <li>{@code MAIL_PASSWORD} - SMTP password / app password</li>
 *     <li>{@code MAIL_FROM}     - "From" address (default = {@code MAIL_USERNAME})</li>
 *     <li>{@code MAIL_ENABLED}  - "true"/"false" — when false the service only logs (dev mode)</li>
 * </ul>
 *
 * <p>When {@code MAIL_ENABLED} is unset or "false", emails are written to the application log
 * with the subject and body — useful for local development and tests so the OTP flow can be
 * exercised without an SMTP relay.
 *
 * <p>SMTP delivery uses Jakarta Mail via reflection so this service remains an optional
 * dependency: if {@code jakarta.mail-api} is not on the classpath, sending falls back to
 * "logged only" mode rather than failing the build.
 */
public final class EmailService {

    private static final Logger logger = Logger.getLogger(EmailService.class.getName());

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String fromAddress;
    private final boolean mailApiAvailable;
    /** Gmail SMTP provider (ưu tiên duy nhất). */
    private final GmailSmtpProvider gmailSmtp;

    public EmailService() {
        this.host = env("MAIL_HOST", "smtp.gmail.com");
        this.port = Integer.parseInt(env("MAIL_PORT", "587"));
        this.username = env("MAIL_USERNAME", "");
        this.password = env("MAIL_PASSWORD", "");
        this.fromAddress = env("MAIL_FROM", username);
        this.enabled = Boolean.parseBoolean(env("MAIL_ENABLED", "false"))
                && !username.isBlank()
                && !password.isBlank();
        this.mailApiAvailable = isClassAvailable("jakarta.mail.Session");
        this.gmailSmtp = new GmailSmtpProvider(
                env("GMAIL_SMTP_HOST", "smtp.gmail.com"),
                Integer.parseInt(env("GMAIL_SMTP_PORT", "587")),
                env("GMAIL_SMTP_USER", ""),
                env("GMAIL_SMTP_PASSWORD", ""),
                env("GMAIL_FROM_EMAIL", env("GMAIL_SMTP_USER", "")),
                env("GMAIL_FROM_NAME", "CinemaHub")
        );
    }

    /**
     * Gửi email chứa thông tin đăng nhập (email + mật khẩu random) cho user mới được
     * Admin tạo. Mật khẩu là plaintext (vì user vừa được tạo) — chỉ gửi MỘT LẦN duy nhất.
     */
    public void sendAccountCredentials(String toEmail, String toFullName, String temporaryPassword) {
        String subject = "[CinemaHub] Tài khoản của bạn đã được tạo";
        String loginUrl = "http://localhost:8080/login";
        String body = """
                <html><body style="font-family:Arial,sans-serif;color:#222">
                <h2 style="color:#d9363e">CinemaHub — Tài khoản mới</h2>
                <p>Xin chào <strong>%s</strong>,</p>
                <p>Quản trị viên đã tạo tài khoản CinemaHub cho bạn với thông tin đăng nhập như sau:</p>
                <table style="border-collapse:collapse;margin:12px 0">
                  <tr><td style="padding:6px 12px;background:#f6f6f6"><strong>Email</strong></td>
                      <td style="padding:6px 12px">%s</td></tr>
                  <tr><td style="padding:6px 12px;background:#f6f6f6"><strong>Mật khẩu tạm thời</strong></td>
                      <td style="padding:6px 12px;font-family:monospace;font-weight:bold;color:#d9363e">%s</td></tr>
                </table>
                <p>Hãy đăng nhập và đổi mật khẩu ngay để bảo mật tài khoản:</p>
                <p><a href="%s" style="display:inline-block;padding:10px 20px;background:#d9363e;color:#fff;text-decoration:none;border-radius:6px">Đăng nhập CinemaHub</a></p>
                <p style="background:#fff4f4;border-left:4px solid #d9363e;padding:10px">
                    <strong>Lưu ý:</strong> Mật khẩu trên chỉ dùng được MỘT LẦN cho lần đăng nhập đầu tiên. Nếu bạn cần hỗ trợ, liên hệ quản trị viên.
                </p>
                <hr><small style="color:#777">© CinemaHub</small>
                </body></html>
                """.formatted(escapeHtml(toFullName), escapeHtml(toEmail),
                        escapeHtml(temporaryPassword), loginUrl);
        // Dev-mode: in plaintext password ra log để test khi mail không đến user
        if (Boolean.parseBoolean(env("DEV_OTP_LOG_TO_CONSOLE", "false"))) {
            logger.info("========== DEV CREDENTIALS ==========");
            logger.info("To: " + toEmail);
            logger.info("Temporary password: " + temporaryPassword);
            logger.info("=====================================");
        }
        send(toEmail, subject, body);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Send the password-reset OTP to {@code toEmail}. Body contains the 6-digit code
     * and clear instructions; the user is told the OTP expires.
     */
    public void sendPasswordResetOtp(String toEmail, String otp, int ttlMinutes) {
        String subject = "[CinemaHub] Mã xác nhận đặt lại mật khẩu";
        String expiry = LocalDateTime.now().plusMinutes(ttlMinutes)
                .format(DateTimeFormatter.ofPattern("HH:mm", new Locale("vi")));
        String body = """
                <html><body style="font-family:Arial,sans-serif;color:#222">
                <h2 style="color:#d9363e">CinemaHub — Đặt lại mật khẩu</h2>
                <p>Xin chào,</p>
                <p>Bạn (hoặc ai đó) vừa yêu cầu đặt lại mật khẩu cho tài khoản CinemaHub của bạn.</p>
                <p>Mã xác nhận (OTP) của bạn là:</p>
                <p style="font-size:1.6rem;font-weight:bold;letter-spacing:6px;color:#d9363e">%s</p>
                <p>Mã này có hiệu lực đến <strong>%s</strong> (khoảng %d phút kể từ bây giờ).</p>
                <p style="background:#fff4f4;border-left:4px solid #d9363e;padding:10px">
                    <strong>Lưu ý:</strong> Không chia sẻ mã này cho bất kỳ ai. Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này.
                </p>
                <hr><small style="color:#777">© CinemaHub</small>
                </body></html>
                """.formatted(otp, expiry, ttlMinutes);
        // Dev-mode fallback: in OTP ra log để test khi SMTP không đến được user
        // (ví dụ: From chưa verify, Gmail filter vào spam, network chặn port 587).
        if (Boolean.parseBoolean(env("DEV_OTP_LOG_TO_CONSOLE", "false"))) {
            logger.info("========== DEV OTP ==========");
            logger.info("To: " + toEmail);
            logger.info("OTP: " + otp + " (expires ~" + expiry + ")");
            logger.info("=============================");
        }
        send(toEmail, subject, body);
    }

    private void send(String to, String subject, String htmlBody) {
        // 1) Gmail SMTP — provider duy nhất. Nếu fail thì log warning nhưng KHÔNG crash.
        if (gmailSmtp != null && gmailSmtp.isConfigured()) {
            try {
                gmailSmtp.sendHtml(to, null, subject, htmlBody);
                return;
            } catch (Exception e) {
                logger.warning("Gmail SMTP send failed: " + e.getMessage());
                throw new ServiceException.BusinessRule("EMAIL_SEND_FAILED",
                        "Gửi email thất bại: " + e.getMessage());
            }
        }
        // 2) Dev mode — không có SMTP config → log nội dung để test.
        logger.warning("Gmail SMTP chưa được cấu hình — kiểm tra biến GMAIL_SMTP_USER / GMAIL_SMTP_PASSWORD trong .env");
        logger.info("[MAIL_DISABLED] To=" + to + " Subject=" + subject);
        logger.info("[MAIL_DISABLED] Body:\n" + htmlBody);
    }

    private static boolean isClassAvailable(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value;
        value = System.getProperty(key);
        if (value != null && !value.isBlank()) return value;
        return fallback;
    }
}
