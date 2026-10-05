package com.cinema.notification;

import com.cinema.common.ServiceException;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Gửi email qua Gmail SMTP (smtp.gmail.com, port 465 SSL hoặc 587 TLS).
 * Dùng tài khoản Gmail của ứng dụng nên:
 * <ul>
 *   <li>Email gửi từ chính Gmail account của bạn → SPF/DKIM hợp lệ → không bị spam filter.</li>
 *   <li>Không cần verify sender trên bên thứ ba.</li>
 * </ul>
 *
 * <p><strong>Cấu hình .env:</strong>
 * <pre>
 * GMAIL_SMTP_HOST=smtp.gmail.com
 * GMAIL_SMTP_PORT=587
 * GMAIL_SMTP_USER=your@gmail.com
 * GMAIL_SMTP_PASSWORD=xxxx xxxx xxxx xxxx    # App Password (16 ký tự, không phải mật khẩu thường)
 * GMAIL_FROM_EMAIL=your@gmail.com
 * GMAIL_FROM_NAME=CinemaHub
 * </pre>
 * <p>Để tạo App Password: Google Account → Security → 2-Step Verification → App passwords.
 */
public final class GmailSmtpProvider {

    private static final Logger logger = Logger.getLogger(GmailSmtpProvider.class.getName());

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String fromEmail;
    private final String fromName;

    public GmailSmtpProvider(String host, int port, String user, String password,
                             String fromEmail, String fromName) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    public void sendHtml(String toEmail, String toName, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) return;

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");

        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        // Timeout để không block server quá lâu
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.writetimeout", "15000");

        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, password);
            }
        });

        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(fromEmail, fromName));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail, toName));
            msg.setSubject(subject, StandardCharsets.UTF_8.name());
            msg.setContent(htmlBody, "text/html; charset=UTF-8");
            msg.setHeader("Content-Transfer-Encoding", "8bit");

            Transport.send(msg);
            logger.info("Gmail SMTP sent to " + toEmail + " subject=\"" + subject + "\"");
        } catch (Exception e) {
            logger.warning("Gmail SMTP send failed: " + e.getMessage());
            throw new ServiceException.BusinessRule("GMAIL_SMTP_FAILED",
                    "Gửi email qua Gmail thất bại: " + e.getMessage());
        }
    }

    public boolean isConfigured() {
        return host != null && !host.isBlank()
                && user != null && !user.isBlank()
                && password != null && !password.isBlank()
                && fromEmail != null && !fromEmail.isBlank();
    }
}
