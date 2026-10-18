package com.cinema.service.refund;

import com.cinema.common.ServiceException;
import com.cinema.dao.booking.TicketDAO;
import com.cinema.dao.refund.RefundRequestDAO;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.booking.Ticket;
import com.cinema.model.refund.RefundRequest;
import com.cinema.service.booking.BookingService;
import com.cinema.service.notification.EmailService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles customer refund requests, manager decisions, and customer email. */
public class RefundService {
    private static final Logger logger = Logger.getLogger(RefundService.class.getName());
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter SHOWTIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final RefundRequestDAO requestDao = new RefundRequestDAO();
    private final TicketDAO ticketDao = new TicketDAO();
    private final BookingService bookingService;
    private final EmailService emailService;

    public RefundService(BookingService bookingService, EmailService emailService) {
        this.bookingService = bookingService;
        this.emailService = emailService;
    }

    public RefundRequest openRequest(long ticketId, long userId, String bankName,
                                     String accountName, String accountNumber, String reason)
            throws Exception {
        String cleanBank = required(bankName, "Tên ngân hàng", 120);
        String cleanAccountName = required(accountName, "Tên chủ tài khoản", 120);
        String cleanAccountNumber = required(accountNumber, "Số tài khoản", 40);
        String cleanReason = optional(reason, "Lý do", 500);

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                        .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));
                if (!Ticket.STATUS_CONFIRMED.equals(ticket.getStatus())) {
                    throw new ServiceException.BusinessRule("TICKET_NOT_CONFIRMED",
                            "Chỉ vé đã thanh toán mới có thể yêu cầu hoàn tiền");
                }
                if (ticket.getUserId() == null || ticket.getUserId() != userId) {
                    throw new ServiceException.Forbidden("Chỉ chủ vé mới có thể yêu cầu hoàn tiền");
                }
                if (requestDao.findPendingForTicket(conn, ticketId) != null) {
                    throw new ServiceException.Conflict(
                            "Vé này đã có yêu cầu hoàn tiền đang chờ xử lý");
                }

                RefundRequest request = loadRequestSnapshot(conn, ticket, userId);
                if (request.getShowtimeStart() == null
                        || !request.getShowtimeStart().isAfter(LocalDateTime.now(BUSINESS_ZONE))) {
                    throw new ServiceException.BusinessRule("SHOWTIME_PASSED",
                            "Suất chiếu đã diễn ra — không thể yêu cầu hoàn tiền");
                }
                request.setBankName(cleanBank);
                request.setBankAccountName(cleanAccountName);
                request.setBankAccountNumber(cleanAccountNumber);
                request.setReason(cleanReason);
                requestDao.insert(conn, request);
                conn.commit();
                return request;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public List<RefundRequest> listMine(Connection conn, long userId) throws SQLException {
        return requestDao.listForUser(conn, userId);
    }

    public List<RefundRequest> listForConsole(Connection conn, Set<Long> branchIds,
                                              boolean allBranches,
                                              RefundRequest.Status status) throws SQLException {
        return requestDao.listForConsole(conn, branchIds, allBranches, status);
    }

    public List<EligibleTicket> listEligibleTickets(Connection conn, long userId)
            throws SQLException {
        String sql = """
            SELECT t.id, t.ticket_code, t.total_amount, s.start_time,
                   m.title AS movie_title, b.name AS branch_name,
                   sc.name AS screen_name
            FROM dbo.ticket t
            JOIN dbo.showtime s ON s.id = t.showtime_id
            JOIN dbo.movie m ON m.id = s.movie_id
            JOIN dbo.screen sc ON sc.id = s.screen_id
            JOIN dbo.branch b ON b.id = t.branch_id
            WHERE t.user_id = ? AND t.status = 'CONFIRMED'
              AND s.start_time > SYSUTCDATETIME()
              AND NOT EXISTS (
                  SELECT 1 FROM dbo.refund_request r
                  WHERE r.ticket_id = t.id AND r.status = 'PENDING'
              )
            ORDER BY t.created_at DESC
            """;
        List<EligibleTicket> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp start = rs.getTimestamp("start_time");
                    result.add(new EligibleTicket(rs.getLong("id"), rs.getString("ticket_code"),
                            rs.getLong("total_amount"), start == null ? null : start.toLocalDateTime(),
                            rs.getString("movie_title"), rs.getString("branch_name"),
                            rs.getString("screen_name")));
                }
            }
        }
        return result;
    }

    public RefundRequest findById(Connection conn, long id) throws SQLException {
        return requestDao.findById(conn, id);
    }

    public RefundDecision decide(long requestId, long managerId, Set<Long> managerBranchIds,
                                 boolean allBranches, boolean approve, String managerNote)
            throws Exception {
        String note = optional(managerNote, "Ghi chú", 1000);
        RefundRequest request;
        RefundRequest.Status next = approve
                ? RefundRequest.Status.APPROVED : RefundRequest.Status.REJECTED;
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                request = requestDao.lockById(conn, requestId);
                if (request == null) throw new ServiceException.NotFound("Yêu cầu hoàn tiền không tồn tại");
                if (request.status() != RefundRequest.Status.PENDING) {
                    throw new ServiceException.Conflict("Yêu cầu này đã được xử lý");
                }
                if (!allBranches && (managerBranchIds == null
                        || !managerBranchIds.contains(request.branchId()))) {
                    throw new ServiceException.Forbidden(
                            "Bạn không thể xử lý yêu cầu hoàn tiền của chi nhánh khác");
                }
                if (!requestDao.updateStatusGuarded(conn, requestId, next,
                        RefundRequest.Status.PENDING, managerId, note)) {
                    throw new ServiceException.Conflict("Yêu cầu vừa được xử lý bởi quản lý khác");
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        if (!approve) return new RefundDecision(request, next, true, null);

        Ticket cancelled;
        try {
            cancelled = bookingService.cancelTicket(request.ticketId(), null, request.branchId());
        } catch (Exception e) {
            rollbackApproval(requestId);
            throw e;
        }

        EmailResult email = sendApprovalEmail(request, cancelled.getRefundAmount() == null
                ? 0 : cancelled.getRefundAmount());
        if (email.sent()) markEmailSent(requestId);
        return new RefundDecision(request, next, email.sent(), email.error());
    }

    public EmailResult resendApprovalEmail(long requestId, Set<Long> managerBranchIds,
                                           boolean allBranches) throws Exception {
        RefundRequest request;
        try (Connection conn = DBContext.getConnection()) {
            request = requestDao.findById(conn, requestId);
        }
        if (request == null) throw new ServiceException.NotFound("Yêu cầu hoàn tiền không tồn tại");
        if (request.status() != RefundRequest.Status.APPROVED) {
            throw new ServiceException.Conflict("Chỉ yêu cầu đã duyệt mới có thể gửi email");
        }
        if (!allBranches && (managerBranchIds == null
                || !managerBranchIds.contains(request.branchId()))) {
            throw new ServiceException.Forbidden(
                    "Bạn không thể gửi email cho yêu cầu của chi nhánh khác");
        }
        if (request.emailSentAt() != null) {
            throw new ServiceException.Conflict("Email hoàn tiền đã được ghi nhận là đã gửi");
        }
        Ticket ticket = ticketDao.findById(request.ticketId())
                .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy vé hoàn tiền"));
        EmailResult result = sendApprovalEmail(request, ticket.getRefundAmount() == null
                ? 0 : ticket.getRefundAmount());
        if (result.sent()) markEmailSent(requestId);
        return result;
    }

    private RefundRequest loadRequestSnapshot(Connection conn, Ticket ticket, long userId)
            throws SQLException {
        String sql = """
            SELECT u.full_name, u.phone, u.email, s.start_time
            FROM dbo.user_account u
            JOIN dbo.showtime s ON s.id = ?
            WHERE u.id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ticket.getShowtimeId());
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException.NotFound("Không tìm thấy tài khoản hoặc suất chiếu");
                Timestamp showtimeStart = rs.getTimestamp("start_time");
                RefundRequest request = new RefundRequest();
                request.setTicketId(ticket.getId());
                request.setUserId(userId);
                request.setBranchId(ticket.getBranchId());
                request.setCustomerFullName(rs.getString("full_name"));
                request.setCustomerPhone(rs.getString("phone"));
                request.setCustomerEmail(rs.getString("email"));
                request.setTicketCode(ticket.getTicketCode());
                request.setShowtimeId(ticket.getShowtimeId());
                request.setShowtimeStart(showtimeStart == null ? null : showtimeStart.toLocalDateTime());
                request.setTicketAmount(ticket.getTotalAmount());
                return request;
            }
        }
    }

    private EmailResult sendApprovalEmail(RefundRequest request, long refundAmount) {
        if (request.customerEmail() == null || request.customerEmail().isBlank()) {
            return new EmailResult(false, "Tài khoản khách hàng không có email.");
        }
        String subject = "[CinemaHub] Yêu cầu hoàn tiền vé " + request.ticketCode() + " đã được duyệt";
        String body = """
                <html><body style="font-family:Arial,sans-serif;color:#222">
                <h2 style="color:#d9363e">CinemaHub — Hoàn tiền vé</h2>
                <p>Xin chào <strong>%s</strong>,</p>
                <p>Yêu cầu hoàn tiền cho vé <strong>%s</strong> đã được quản lý duyệt.</p>
                <table style="border-collapse:collapse;margin:12px 0">
                  <tr><td style="padding:6px 12px;background:#f6f6f6">Mã vé</td>
                      <td style="padding:6px 12px"><strong>%s</strong></td></tr>
                  <tr><td style="padding:6px 12px;background:#f6f6f6">Suất chiếu</td>
                      <td style="padding:6px 12px">%s</td></tr>
                  <tr><td style="padding:6px 12px;background:#f6f6f6">Số tiền hoàn</td>
                      <td style="padding:6px 12px;color:#d9363e;font-weight:bold">%sđ</td></tr>
                  <tr><td style="padding:6px 12px;background:#f6f6f6">Ngân hàng</td>
                      <td style="padding:6px 12px">%s</td></tr>
                  <tr><td style="padding:6px 12px;background:#f6f6f6">Chủ tài khoản</td>
                      <td style="padding:6px 12px">%s</td></tr>
                  <tr><td style="padding:6px 12px;background:#f6f6f6">Số tài khoản</td>
                      <td style="padding:6px 12px;font-family:monospace">%s</td></tr>
                </table>
                <p>Vui lòng đợi 1–3 ngày làm việc để ngân hàng xử lý. Liên hệ chi nhánh nếu
                   quá thời gian trên mà chưa nhận được tiền.</p>
                <hr><small style="color:#777">© CinemaHub</small>
                </body></html>
                """.formatted(escapeHtml(request.customerFullName()),
                escapeHtml(request.ticketCode()), escapeHtml(request.ticketCode()),
                request.showtimeStart() == null ? "—" : SHOWTIME_FORMAT.format(request.showtimeStart()),
                String.format("%,d", refundAmount), escapeHtml(request.bankName()),
                escapeHtml(request.bankAccountName()), escapeHtml(request.bankAccountNumber()));
        try {
            emailService.sendHtml(request.customerEmail(), subject, body);
            return new EmailResult(true, null);
        } catch (ServiceException.BusinessRule e) {
            logger.log(Level.WARNING, "Refund email failed for request " + request.id(), e);
            return new EmailResult(false, e.getMessage());
        }
    }

    private void rollbackApproval(long requestId) {
        try (Connection conn = DBContext.getConnection()) {
            requestDao.updateStatusGuarded(conn, requestId, RefundRequest.Status.PENDING,
                    RefundRequest.Status.APPROVED, null, null);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Could not roll back refund approval " + requestId, e);
        }
    }

    private void markEmailSent(long requestId) {
        try (Connection conn = DBContext.getConnection()) {
            if (!requestDao.markEmailSent(conn, requestId)) {
                logger.warning("Email was sent but request " + requestId
                        + " could not be marked as sent");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Email was sent but could not be recorded for refund "
                    + requestId, e);
        }
    }

    private static String required(String value, String label, int maxLength) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new ServiceException.Validation(label + " là bắt buộc");
        if (clean.length() > maxLength) {
            throw new ServiceException.Validation(label + " không được vượt quá " + maxLength + " ký tự");
        }
        return clean;
    }

    private static String optional(String value, String label, int maxLength) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > maxLength) {
            throw new ServiceException.Validation(label + " không được vượt quá " + maxLength + " ký tự");
        }
        return clean.isEmpty() ? null : clean;
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public record EligibleTicket(long id, String ticketCode, long totalAmount,
                                 LocalDateTime showtimeStart, String movieTitle,
                                 String branchName, String screenName) { }
    public record EmailResult(boolean sent, String error) { }
    public record RefundDecision(RefundRequest request, RefundRequest.Status status,
                                 boolean emailSent, String emailError) { }
}
