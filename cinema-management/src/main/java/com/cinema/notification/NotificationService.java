package com.cinema.notification;

import com.cinema.booking.Ticket;
import com.cinema.booking.TicketDAO;
import com.cinema.common.ServiceException;
import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service thông báo in-app và reminder suất chiếu (Req 23.1-23.6).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Tạo thông báo confirmed/cancel/refund kèm mã vé, suất chiếu, ghế, tổng tiền /
 *       lý do và số tiền hoàn (Req 23.1, 23.2).</li>
 *   <li>Best-effort: lỗi gửi/ghi thông báo được log và KHÔNG rollback hay chặn giao dịch
 *       chính (Req 23.4 — design.md NotificationService).</li>
 *   <li>Danh sách chỉ trả thông báo của chính owner; read/unread + đánh dấu đã đọc,
 *       guard user_id chống IDOR (Req 23.5).</li>
 *   <li>Tài khoản LOCKED: ngừng thông báo tiếp thị nhưng vẫn gửi thông báo giao dịch
 *       liên quan hoàn tiền (Req 23.6).</li>
 *   <li>Reminder 60 phút trước giờ chiếu, idempotent — scheduler (task 11.1) chạy lặp
 *       không gửi trùng (Req 23.3).</li>
 * </ul>
 */
public class NotificationService {
    private static final Logger logger = Logger.getLogger(NotificationService.class.getName());
    private static final DateTimeFormatter SHOWTIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Khoảng nhắc suất chiếu mặc định (Req 23.3): 60 phút. */
    public static final int DEFAULT_REMIND_BEFORE_MINUTES = 60;
    private static final int DEFAULT_LIST_LIMIT = 100;

    private final NotificationDAO notificationDao;

    public NotificationService(NotificationDAO notificationDao) {
        this.notificationDao = notificationDao;
    }

    /**
     * Thông báo vé confirmed (Req 23.1): mã vé, suất chiếu, ghế, tổng tiền.
     * Gọi trong transaction confirm (conn != null) hoặc best-effort sau commit (conn = null).
     */
    public void notifyTicketConfirmed(Connection conn, Long userId, String ticketCode,
                                      TicketDAO.ConfirmationDetails details, long totalAmount) {
        if (userId == null) return; // vé bán quầy cho khách vãng lai — không có tài khoản để thông báo
        if (details == null) {
            logger.warning("Missing showtime details for confirmed ticket " + ticketCode);
        }
        sendSafely(conn, userId, Notification.TYPE_TICKET_CONFIRMED, "Xác nhận đặt vé thành công",
                "Mã vé " + ticketCode + " · " + confirmationShowtimeInfo(details) + " · Ghế "
                        + confirmationSeatInfo(details)
                        + " · Tổng tiền " + totalAmount + "đ");
    }

    static String confirmationShowtimeInfo(TicketDAO.ConfirmationDetails details) {
        if (details == null) return "Lịch chiếu";
        String title = details.movieTitle() == null || details.movieTitle().isBlank()
                ? "Lịch chiếu" : details.movieTitle();
        return details.showtimeStart() == null ? title
                : title + " · " + SHOWTIME_FORMAT.format(details.showtimeStart());
    }

    static String confirmationSeatInfo(TicketDAO.ConfirmationDetails details) {
        if (details == null || details.seats().isEmpty()) return "chưa xác định";
        StringBuilder labels = new StringBuilder();
        int shown = Math.min(6, details.seats().size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) labels.append(", ");
            Ticket.TicketSeat seat = details.seats().get(i);
            labels.append(seat.rowLabel()).append(seat.colNo());
        }
        int remaining = details.seats().size() - shown;
        if (remaining > 0) labels.append(" và ").append(remaining).append(" ghế khác");
        return labels.toString();
    }

    /**
     * Thông báo hủy/hoàn tiền (Req 23.2): lý do + số tiền hoàn.
     * Loại REFUND vẫn gửi khi tài khoản LOCKED (Req 23.6).
     */
    public void notifyCancelledOrRefund(Connection conn, Long userId, String type,
                                        String ticketCode, String reason, Long refundAmount) {
        if (userId == null) return;
        String title = refundAmount != null && refundAmount > 0
                ? "Hủy vé và hoàn tiền" : "Hủy vé";
        String body = "Vé " + ticketCode + " — " + reason
                + (refundAmount != null && refundAmount > 0
                    ? " · Số tiền hoàn " + refundAmount + "đ" : " · Không hoàn tiền theo chính sách");
        sendSafely(conn, userId, type, title, body);
    }

    /** Thông báo tiếp thị (Req 23.6) — bỏ qua khi tài khoản LOCKED/INACTIVE. */
    public void notifyMarketing(Long userId, String title, String body) {
        if (userId == null) return;
        if (!isAccountActive(userId)) {
            logger.info("Skipped marketing notification for locked account " + userId);
            return;
        }
        sendSafely(null, userId, Notification.TYPE_MARKETING, title, body);
    }

    /**
     * Quét và gửi reminder suất chiếu (Req 23.3) — scheduler gọi định kỳ.
     * Idempotent: vé đã được nhắc thì không nhắc lại. Lỗi từng vé không chặn các vé khác.
     *
     * @return số reminder đã gửi
     */
    public int sendShowtimeReminders() {
        int sent = 0;
        List<NotificationDAO.ReminderCandidate> candidates;
        try {
            candidates = notificationDao.findReminderCandidates(DEFAULT_REMIND_BEFORE_MINUTES);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Không quét được ứng viên nhắc suất chiếu", e);
            return 0;
        }
        for (NotificationDAO.ReminderCandidate candidate : candidates) {
            try {
                if (notificationDao.reminderAlreadySent(candidate.userId(), candidate.ticketCode())) {
                    continue;
                }
                notificationDao.insert(null, candidate.userId(),
                        Notification.TYPE_SHOWTIME_REMINDER,
                        "Nhắc suất chiếu sắp bắt đầu",
                        "Vé " + candidate.ticketCode() + " khởi chiếu lúc " + candidate.startTime()
                                + " — vui lòng có mặt trước giờ chiếu để soát vé");
                sent++;
            } catch (Exception e) {
                // Req 23.4 — lỗi một reminder không chặn các reminder còn lại
                logger.log(Level.WARNING, "Không gửi được reminder cho vé "
                        + candidate.ticketCode(), e);
            }
        }
        if (sent > 0) logger.info("Sent " + sent + " showtime reminder(s)");
        return sent;
    }

    /** Danh sách thông báo của chính tài khoản (Req 23.5). */
    public List<Notification> listForUser(long userId, Boolean unreadOnly) throws SQLException {
        return notificationDao.findByUser(userId, unreadOnly, DEFAULT_LIST_LIMIT);
    }

    /** Số chưa đọc cho badge navbar. */
    public int unreadCount(long userId) throws SQLException {
        return notificationDao.countUnread(userId);
    }

    /**
     * Đánh dấu đã đọc (Req 23.5) — guard owner: thông báo của tài khoản khác
     * không đánh dấu được (trả 403 để controller phân biệt với 404).
     */
    public Notification markRead(long notificationId, long userId) throws SQLException {
        Notification notification = notificationDao.findById(notificationId)
                .orElseThrow(() -> new ServiceException.NotFound("Thông báo không tồn tại"));
        if (notification.userId() != userId) {
            throw new ServiceException.Forbidden("Không được thao tác thông báo của tài khoản khác");
        }
        notificationDao.markRead(notificationId, userId);
        notification.setRead(true);
        return notification;
    }

    /** Đánh dấu tất cả đã đọc (Req 23.5). */
    public int markAllRead(long userId) throws SQLException {
        return notificationDao.markAllRead(userId);
    }

    /**
     * Thông báo F&B thanh toán thành công qua VNPay (Req 21.x).
     * Gọi best-effort sau commit transaction.
     */
    public void notifyFnbOrderConfirmed(long userId, long orderId, String orderCode, long totalAmount) {
        if (userId <= 0) return;
        sendSafely(null, userId, Notification.TYPE_FNB_CONFIRMED,
                "Thanh toán Bắp & Nước thành công",
                "Đơn " + orderCode + " · Tổng tiền " + totalAmount + "đ · "
                        + "Đơn của bạn đang được chuẩn bị");
    }

    /**
     * Thông báo F&B sẵn sàng nhận (Req 21.x).
     * Gọi best-effort sau commit transaction.
     */
    public void notifyFnbReadyForPickup(long userId, long orderId, String orderCode, String pickupCode) {
        if (userId <= 0) return;
        sendSafely(null, userId, Notification.TYPE_FNB_READY,
                "Đơn Bắp & Nước đã sẵn sàng nhận",
                "Đơn " + orderCode + " · Mã nhận: " + pickupCode
                        + " · Vui lòng nhận tại quầy Bắp & Nước");
    }

    /**
     * Thông báo F&B bị hủy (Req 20.5/21.6).
     * Gọi best-effort sau commit transaction.
     */
    public void notifyFnbCancelled(long userId, long orderId, String orderCode, long refundAmount) {
        if (userId <= 0) return;
        String body = refundAmount > 0
                ? "Đơn " + orderCode + " đã bị hủy · Hoàn tiền " + refundAmount + "đ"
                : "Đơn " + orderCode + " đã bị hủy";
        sendSafely(null, userId, Notification.TYPE_FNB_CANCELLED,
                "Đơn Bắp & Nước đã hủy", body);
    }

    /**
     * Thông báo F&B đã hoàn tiền (Req 20.5/21.6).
     * Gọi best-effort sau commit transaction.
     */
    public void notifyFnbRefunded(long userId, long orderId, String orderCode, long pointsRefunded) {
        if (userId <= 0) return;
        String body = pointsRefunded > 0
                ? "Đơn " + orderCode + " · Hoàn " + pointsRefunded + " điểm"
                : "Đơn " + orderCode + " · Đã xử lý hoàn tiền";
        sendSafely(null, userId, Notification.TYPE_FNB_REFUNDED,
                "Đơn Bắp & Nước đã hoàn tiền", body);
    }

    /**
     * Thông báo F&B đã giao thành công (staff pickup confirmation).
     */
    public void notifyFnbFulfilled(long userId, long orderId, String orderCode) {
        if (userId <= 0) return;
        sendSafely(null, userId, Notification.TYPE_FNB_READY,
                "Đơn Bắp & Nước đã được giao",
                "Đơn " + orderCode + " · Đã nhận thành công tại quầy");
    }

    /**
     * Thông báo điểm thưởng được điều chỉnh bởi nhân viên (staff point adjustment).
     */
    public void notifyPointsAdjusted(long userId, int delta, String reason) {
        if (userId <= 0) return;
        String sign = delta > 0 ? "+" : "";
        String body = sign + delta + " điểm · " + reason;
        sendSafely(null, userId, Notification.TYPE_MARKETING,
                "Điểm thưởng được điều chỉnh", body);
    }

    /**
     * Gửi thông báo generic cho 1 user — dùng cho payroll complaint, support...
     * (REQ-PR-060, RSC-5).
     */
    public void notifyUser(Long userId, String type, String title, String body) {
        if (userId == null || userId <= 0) return;
        sendSafely(null, userId, type, title, body);
    }

    /**
     * Gửi thông báo cho tất cả manager của 1 chi nhánh.
     * Best-effort: lỗi 1 user không chặn các user khác.
     */
    public void notifyBranchManagers(Long branchId, String type, String title, String body) {
        if (branchId == null || branchId <= 0) return;
        String sql = """
                SELECT id FROM dbo.user_account
                WHERE status = 'ACTIVE'
                  AND role_code = 'BRANCH_MANAGER'
                  AND id IN (SELECT user_id FROM dbo.staff_branch_assignment
                              WHERE branch_id = ? AND status = 'ACTIVE')
                """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sendSafely(null, rs.getLong(1), type, title, body);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được thông báo tới manager branch " + branchId, e);
        }
    }

    public int notifyBranchManagers(Connection conn, long branchId, String type,
                                    String title, String body) throws SQLException {
        String sql = """
                SELECT DISTINCT ua.id AS user_id
                FROM dbo.user_account ua
                JOIN dbo.staff_branch_assignment sba ON sba.user_id = ua.id
                JOIN dbo.branch b ON b.id = sba.branch_id
                WHERE sba.branch_id = ?
                  AND ua.status = 'ACTIVE'
                  AND ua.role_code = 'BRANCH_MANAGER'
                  AND sba.status = 'ACTIVE'
                  AND sba.effective_from <= SYSUTCDATETIME()
                  AND (sba.effective_to IS NULL OR sba.effective_to > SYSUTCDATETIME())
                  AND b.status = 'ACTIVE'
                ORDER BY ua.id
                """;
        List<Long> managerIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) managerIds.add(rs.getLong("user_id"));
            }
        }
        for (long managerId : managerIds) {
            notificationDao.insert(conn, managerId, type, title, body);
        }
        return managerIds.size();
    }

    // ---- internals ----

    /**
     * Req 23.4 — ghi thông báo best-effort: mọi lỗi chỉ log, không ném lên caller,
     * không rollback giao dịch chính.
     */
    private void sendSafely(Connection conn, long userId, String type, String title, String body) {
        try {
            // Req 23.6 — tài khoản LOCKED chỉ nhận thông báo giao dịch (non-marketing)
            if (Notification.TYPE_MARKETING.equals(type) && !isAccountActive(userId)) {
                logger.info("Skipped marketing notification for locked account " + userId);
                return;
            }
            notificationDao.insert(conn, userId, type, title, body);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được thông báo " + type
                    + " cho user " + userId + " — bỏ qua, không ảnh hưởng giao dịch chính", e);
        }
    }

    private boolean isAccountActive(long userId) {
        String sql = "SELECT status FROM dbo.user_account WHERE id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "ACTIVE".equals(rs.getString("status"));
            }
        } catch (Exception e) {
            // Không đọc được trạng thái → mặc định KHÔNG gửi marketing (an toàn hơn)
            logger.warning("Không kiểm tra được trạng thái tài khoản " + userId);
            return false;
        }
    }
}
