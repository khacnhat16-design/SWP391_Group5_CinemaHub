package com.cinema.showtime;

import com.cinema.audit.AuditService;
import com.cinema.auth.AccessScope;
import com.cinema.auth.Role;
import com.cinema.common.ServiceException;
import com.cinema.notification.Notification;
import com.cinema.notification.NotificationDAO;
import com.cinema.notification.NotificationService;
import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Nghiệp vụ phân bổ suất chiếu (showtime allocation).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Admin tạo / sửa / xóa phân bổ (Manager không thể tự thay đổi allocated_quantity).</li>
 *   <li>Manager chỉ xem được phân bổ của branch mình quản lý.</li>
 *   <li>Manager tạo showtime tăng createdQuantity — atomic update trong transaction
 *       có khóa row (UPDLOCK/HOLDLOCK/ROWLOCK) để chống race (Section 12).</li>
 *   <li>Khi created &gt; allocated → status OVER_ALLOCATED và phát cảnh báo.</li>
 * </ul>
 */
public class ShowtimeAllocationService {

    public enum ReminderStage {
        NONE,
        NORMAL,
        URGENT
    }

    private static final Logger logger = Logger.getLogger(ShowtimeAllocationService.class.getName());
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ShowtimeAllocationDAO dao;
    private final NotificationService notificationService;

    public ShowtimeAllocationService(ShowtimeAllocationDAO dao,
                                     NotificationService notificationService) {
        this.dao = dao;
        this.notificationService = notificationService;
    }

    /** Convenience constructor — mặc định dùng NotificationDAO JDBC. */
    public ShowtimeAllocationService(ShowtimeAllocationDAO dao) {
        this(dao, new NotificationService(new NotificationDAO()));
    }

    public int sendScheduleReminders() {
        return sendScheduleReminders(LocalDate.now(BUSINESS_ZONE));
    }

    public int sendScheduleReminders(LocalDate businessDate) {
        if (businessDate == null) throw new IllegalArgumentException("businessDate is required");
        List<ShowtimeAllocationDAO.ReminderCandidate> candidates;
        try {
            candidates = dao.findReminderCandidates(businessDate);
        } catch (SQLException e) {
            throw new IllegalStateException("Không quét được phân bổ cần nhắc xếp lịch", e);
        }

        int sent = 0;
        for (ShowtimeAllocationDAO.ReminderCandidate candidate : candidates) {
            try {
                if (sendScheduleReminder(candidate.allocationId(), businessDate)) sent++;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Không gửi được nhắc xếp lịch cho allocation "
                        + candidate.allocationId(), e);
            }
        }
        if (sent > 0) logger.info("Sent " + sent + " showtime schedule reminder(s)");
        return sent;
    }

    private boolean sendScheduleReminder(long allocationId, LocalDate businessDate)
            throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Optional<ShowtimeAllocationDAO.ReminderCandidate> locked =
                        dao.findReminderCandidateLocked(conn, allocationId);
                if (locked.isEmpty()) {
                    conn.commit();
                    return false;
                }

                ShowtimeAllocationDAO.ReminderCandidate allocation = locked.get();
                if (allocation.releaseDate() == null) {
                    conn.commit();
                    return false;
                }

                int createdQuantity = dao.countShowtimes(
                        conn, allocation.movieId(), allocation.branchId());
                if (createdQuantity >= allocation.allocatedQuantity()) {
                    conn.commit();
                    return false;
                }

                ReminderStage stage = reminderStage(allocation.releaseDate(), businessDate,
                        allocation.movieStatus(), allocation.allocatedQuantity(), createdQuantity);
                if (stage == ReminderStage.NONE) {
                    conn.commit();
                    return false;
                }

                boolean newReminderCycle = !allocation.releaseDate()
                        .equals(allocation.reminderReleaseDate());
                LocalDateTime normalSentAt = newReminderCycle
                        ? null : allocation.normalSentAt();
                LocalDateTime urgentSentAt = newReminderCycle
                        ? null : allocation.urgentSentAt();
                if ((stage == ReminderStage.NORMAL
                        && normalSentAt != null)
                        || (stage == ReminderStage.URGENT
                        && urgentSentAt != null)) {
                    conn.commit();
                    return false;
                }

                String type;
                String title;
                String urgency;
                if (stage == ReminderStage.URGENT) {
                    type = Notification.TYPE_SHOWTIME_SCHEDULE_URGENT;
                    title = "Khẩn cấp: cần hoàn tất lịch chiếu";
                    urgency = "KHẨN CẤP";
                } else {
                    type = Notification.TYPE_SHOWTIME_SCHEDULE_REMINDER;
                    title = "Sắp đến hạn xếp lịch chiếu";
                    urgency = "NHẮC NHỞ";
                }
                String body = String.format(
                        "%s · Phim %s tại chi nhánh %s còn thiếu %d/%d suất chiếu "
                                + "(đã tạo %d/%d). Ngày khởi chiếu: %s "
                                + "· /console?module=showtime-allocation&movieId=%d&branchId=%d",
                        urgency, allocation.movieTitle(), allocation.branchName(),
                        allocation.allocatedQuantity() - createdQuantity,
                        allocation.allocatedQuantity(), createdQuantity,
                        allocation.allocatedQuantity(), allocation.releaseDate(),
                        allocation.movieId(), allocation.branchId());
                int recipients = notificationService.notifyBranchManagers(
                        conn, allocation.branchId(), type, title, body);
                if (recipients == 0) {
                    conn.commit();
                    return false;
                }

                LocalDateTime sentAt = LocalDateTime.now(ZoneId.of("UTC"));
                if (stage == ReminderStage.NORMAL) {
                    normalSentAt = sentAt;
                } else {
                    urgentSentAt = sentAt;
                }
                if (!dao.updateReminderState(conn, allocation.allocationId(),
                        allocation.releaseDate(), normalSentAt, urgentSentAt)) {
                    throw new SQLException("Allocation disappeared while writing reminder state: "
                            + allocation.allocationId());
                }
                conn.commit();
                return true;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }

    public static ReminderStage reminderStage(LocalDate releaseDate, LocalDate businessDate,
                                             String movieStatus, int allocatedQuantity,
                                             int createdQuantity) {
        if (releaseDate == null || businessDate == null
                || !"PUBLISHED".equals(movieStatus)
                || allocatedQuantity <= 0 || createdQuantity >= allocatedQuantity) {
            return ReminderStage.NONE;
        }
        long daysRemaining = ChronoUnit.DAYS.between(businessDate, releaseDate);
        if (daysRemaining >= 1 && daysRemaining <= 2) return ReminderStage.URGENT;
        if (daysRemaining >= 3 && daysRemaining <= 7) return ReminderStage.NORMAL;
        return ReminderStage.NONE;
    }

    // ---------------------------------------------------------------------
    // Admin: CRUD
    // ---------------------------------------------------------------------

    /** Admin tạo phân bổ — sẽ thông báo cho Manager của branch đó. */
    public ShowtimeAllocation adminCreate(AccessScope scope, long movieId, long branchId,
                                          int allocatedQuantity, String note) {
        requireAdmin(scope);
        validateQuantity(allocatedQuantity);
        validateMovieAndBranch(movieId, branchId);

        ShowtimeAllocation a = new ShowtimeAllocation();
        a.setMovieId(movieId);
        a.setBranchId(branchId);
        a.setAllocatedQuantity(allocatedQuantity);
        a.setCreatedQuantity(0);
        a.setStatus(ShowtimeAllocation.STATUS_PENDING);
        a.setNote(note);
        a.setCreatedBy(scope.userId());
        a.setUpdatedBy(scope.userId());
        a.setAllocatedAt(LocalDateTime.now());
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());

        QuantityChange quantityChange;
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Lock check: cặp (movie, branch) phải chưa có phân bổ
                Optional<ShowtimeAllocation> existing =
                        dao.findLocked(conn, movieId, branchId);
                if (existing.isPresent()) {
                    throw new ServiceException.Conflict(
                            "Phân bổ cho movie + branch này đã tồn tại (id="
                                    + existing.get().id() + "). Hãy cập nhật thay vì tạo mới.");
                }
                int createdQuantity = dao.countShowtimes(conn, movieId, branchId);
                a.setCreatedQuantity(createdQuantity);
                a.setStatus(computeStatus(allocatedQuantity, createdQuantity));
                if (ShowtimeAllocation.STATUS_COMPLETED.equals(a.status())) {
                    a.setCompletedAt(LocalDateTime.now());
                }
                if (ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(a.status())) {
                    a.setLastWarningAt(LocalDateTime.now());
                }
                long id = dao.insert(conn, a);
                a.setId(id);
                dao.insertHistory(conn, id, "ALLOCATED", null,
                        "{\"allocated_quantity\":" + allocatedQuantity
                                + ",\"created_quantity\":" + createdQuantity
                                + ",\"status\":\"" + a.status() + "\"}",
                        scope.userId(), note);
                conn.commit();
                quantityChange = new QuantityChange(null, a);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create allocation", e);
            throw new ServiceException("INTERNAL_ERROR", "Lỗi tạo phân bổ: " + e.getMessage(), 500);
        }

        // Audit + notify best-effort (sau commit)
        AuditService audit = new AuditService();
        audit.recordSafely(scope.userId(), "CREATE_SHOWTIME_ALLOCATION", "SHOWTIME_ALLOCATION",
                a.id(), null,
                String.format("{\"movie_id\":%d,\"branch_id\":%d,\"allocated_quantity\":%d}",
                        movieId, branchId, allocatedQuantity),
                AuditService.SUCCESS);
        notifyManagerOnAllocation(a);
        notifyQuantityChange(quantityChange, scope.userId());
        return a;
    }

    /** Admin sửa số lượng phân bổ và / hoặc note. */
    public ShowtimeAllocation adminUpdate(AccessScope scope, long id,
                                          int allocatedQuantity, String note) {
        requireAdmin(scope);
        validateQuantity(allocatedQuantity);
        ShowtimeAllocation existing;
        QuantityChange quantityChange;
        int oldQty;
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Optional<ShowtimeAllocation> locked = dao.findByIdLocked(conn, id);
                if (locked.isEmpty()) {
                    throw new ServiceException.NotFound("Không tìm thấy phân bổ #" + id);
                }
                ShowtimeAllocation a = locked.get();
                oldQty = a.allocatedQuantity();
                String oldStatus = a.status();
                int createdQuantity = dao.countShowtimes(conn, a.movieId(), a.branchId());
                String newStatus = computeStatus(allocatedQuantity, createdQuantity);
                LocalDateTime completedAt = ShowtimeAllocation.STATUS_COMPLETED.equals(newStatus)
                        ? (ShowtimeAllocation.STATUS_COMPLETED.equals(oldStatus)
                                ? a.completedAt() : LocalDateTime.now())
                        : null;
                LocalDateTime warningAt = ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(newStatus)
                        && !ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(oldStatus)
                        ? LocalDateTime.now() : a.lastWarningAt();
                if (!dao.updateAllocation(conn, id, allocatedQuantity, note, scope.userId())) {
                    throw new ServiceException.NotFound("Không tìm thấy phân bổ #" + id);
                }
                boolean quantityChanged = createdQuantity != a.createdQuantity()
                        || !java.util.Objects.equals(newStatus, oldStatus);
                if (!dao.updateCreatedQuantityAndStatus(conn, id, createdQuantity, newStatus,
                        completedAt, warningAt, scope.userId())) {
                    throw new ServiceException.NotFound("Không tìm thấy phân bổ #" + id);
                }
                dao.insertHistory(conn, id, "UPDATED", String.valueOf(oldQty),
                        String.valueOf(allocatedQuantity), scope.userId(), note);
                if (quantityChanged) {
                    insertQuantityHistory(conn, a, oldStatus, createdQuantity, newStatus,
                            scope.userId(), "Admin cập nhật phân bổ");
                }
                conn.commit();
                a.setAllocatedQuantity(allocatedQuantity);
                if (note != null) a.setNote(note);
                a.setCreatedQuantity(createdQuantity);
                a.setStatus(newStatus);
                a.setCompletedAt(completedAt);
                a.setLastWarningAt(warningAt);
                existing = a;
                quantityChange = new QuantityChange(oldStatus, a);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to update allocation", e);
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
        AuditService audit = new AuditService();
        audit.recordSafely(scope.userId(), "UPDATE_SHOWTIME_ALLOCATION", "SHOWTIME_ALLOCATION",
                id,
                String.format("{\"allocated_quantity\":%d}", oldQty),
                String.format("{\"allocated_quantity\":%d}", allocatedQuantity),
                AuditService.SUCCESS);
        notifyQuantityChange(quantityChange, scope.userId());
        if (oldQty != allocatedQuantity) {
            notifyManagerOnAllocationUpdate(oldQty, existing);
        }
        return existing;
    }

    /** Admin xóa phân bổ — chỉ khi created = 0. */
    public boolean adminDelete(AccessScope scope, long id) {
        requireAdmin(scope);
        ShowtimeAllocation existing;
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                existing = dao.findByIdLocked(conn, id).orElseThrow(
                        () -> new ServiceException.NotFound("Không tìm thấy phân bổ #" + id));
                int createdQuantity = dao.countShowtimes(conn, existing.movieId(),
                        existing.branchId());
                if (createdQuantity > 0) {
                    throw new ServiceException.BusinessRule("HAS_CREATED_SHOWTIMES",
                            "Không thể xóa phân bổ đã có showtime (createdQuantity="
                                    + createdQuantity + ")");
                }
                if (!dao.updateCreatedQuantityAndStatus(conn, id, 0,
                        ShowtimeAllocation.STATUS_PENDING,
                        null, existing.lastWarningAt(), scope.userId())
                        || !dao.deleteIfNoCreated(conn, id)) {
                    throw new ServiceException.Conflict(
                            "Phân bổ đã thay đổi; vui lòng tải lại dữ liệu.");
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
        try {
            AuditService audit = new AuditService();
            audit.recordSafely(scope.userId(), "DELETE_SHOWTIME_ALLOCATION",
                    "SHOWTIME_ALLOCATION", id,
                    String.format("{\"movie_id\":%d,\"branch_id\":%d,\"allocated_quantity\":%d}",
                            existing.movieId(), existing.branchId(), existing.allocatedQuantity()),
                    null, AuditService.SUCCESS);
            return true;
        } catch (Exception e) {
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
    }

    // ---------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------

    /**
     * Lấy danh sách phân bổ theo scope.
     * <ul>
     *   <li>ADMIN: thấy tất cả (kèy branchId/movieId nếu truyền).</li>
     *   <li>BRANCH_MANAGER: chỉ thấy branch mình quản lý.</li>
     *   <li>BRANCH_STAFF: 403 — không được xem allocation của chính họ.</li>
     * </ul>
     */
    public List<ShowtimeAllocation> listForScope(AccessScope scope, Long branchId,
                                                  Long movieId, String status) {
        if (scope.isGuest() || scope.role() == Role.CUSTOMER || scope.role() == Role.BRANCH_STAFF) {
            throw new ServiceException.Forbidden("Không có quyền xem phân bổ suất chiếu");
        }
        if (scope.role() == Role.BRANCH_MANAGER) {
            if (branchId != null && !scope.includesBranch(branchId)) {
                throw new ServiceException.Forbidden(
                        "Manager không thể xem phân bổ của chi nhánh khác");
            }
            if (scope.branchIds().isEmpty()) {
                return List.of();
            }
        }
        try {
            if (scope.role() == Role.BRANCH_MANAGER) {
                return dao.listAllForBranches(branchId == null
                                ? scope.branchIds() : java.util.Set.of(branchId),
                        movieId, status);
            }
            return dao.listAll(branchId, movieId, status);
        } catch (Exception e) {
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
    }

    public ShowtimeAllocation getForScope(AccessScope scope, long id) {
        ShowtimeAllocation a;
        try {
            a = dao.findById(id).orElseThrow(
                    () -> new ServiceException.NotFound("Không tìm thấy phân bổ #" + id));
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
        if (!canReadAllocation(scope, a)) {
            throw new ServiceException.Forbidden("Không có quyền xem phân bổ này");
        }
        return a;
    }

    public List<ShowtimeAllocationDAO.ShowtimeAllocationHistory> listHistory(AccessScope scope, long id) {
        ShowtimeAllocation a = getForScope(scope, id);
        try {
            return dao.listHistory(a.id());
        } catch (Exception e) {
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
    }

    // ---------------------------------------------------------------------
    // Showtime lifecycle integration
    // ---------------------------------------------------------------------

    public void lockForShowtime(Connection conn, long movieId, long branchId) throws Exception {
        dao.findLocked(conn, movieId, branchId);
    }

    public QuantityChange reconcileShowtimeQuantity(Connection conn, long movieId, long branchId,
                                                    Long actorUserId, String note)
            throws Exception {
        Optional<ShowtimeAllocation> locked = dao.findLocked(conn, movieId, branchId);
        if (locked.isEmpty()) return null;

        ShowtimeAllocation allocation = locked.get();
        String previousStatus = allocation.status();
        int createdQuantity = dao.countShowtimes(conn, movieId, branchId);
        String newStatus = computeStatus(allocation.allocatedQuantity(), createdQuantity);
        LocalDateTime completedAt = ShowtimeAllocation.STATUS_COMPLETED.equals(newStatus)
                ? (ShowtimeAllocation.STATUS_COMPLETED.equals(previousStatus)
                        ? allocation.completedAt() : LocalDateTime.now())
                : null;
        LocalDateTime warningAt = ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(newStatus)
                && !ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(previousStatus)
                ? LocalDateTime.now() : allocation.lastWarningAt();

        boolean changed = createdQuantity != allocation.createdQuantity()
                || !java.util.Objects.equals(previousStatus, newStatus)
                || !java.util.Objects.equals(allocation.completedAt(), completedAt)
                || !java.util.Objects.equals(allocation.lastWarningAt(), warningAt);
        if (changed) {
            if (!dao.updateCreatedQuantityAndStatus(conn, allocation.id(),
                    createdQuantity, newStatus,
                    completedAt, warningAt, actorUserId)) {
                throw new ServiceException("INTERNAL_ERROR",
                        "Không đồng bộ được số lượng allocation", 500);
            }
            insertQuantityHistory(conn, allocation, previousStatus, createdQuantity,
                    newStatus, actorUserId, note);
        }

        allocation.setCreatedQuantity(createdQuantity);
        allocation.setStatus(newStatus);
        allocation.setCompletedAt(completedAt);
        allocation.setLastWarningAt(warningAt);
        return new QuantityChange(previousStatus, allocation);
    }

    public void notifyQuantityChange(QuantityChange change, Long actorUserId) {
        if (change == null || notificationService == null) return;
        ShowtimeAllocation allocation = change.allocation();
        if (ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(allocation.status())
                && !ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(change.previousStatus())) {
            handleOverAllocation(allocation, actorUserId);
        } else if (ShowtimeAllocation.STATUS_COMPLETED.equals(allocation.status())
                && !ShowtimeAllocation.STATUS_COMPLETED.equals(change.previousStatus())) {
            handleCompleted(allocation, actorUserId);
        }
    }

    private void insertQuantityHistory(Connection conn, ShowtimeAllocation allocation,
                                       String previousStatus, int createdQuantity,
                                       String newStatus, Long actorUserId, String note)
            throws Exception {
        String eventType = ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(newStatus)
                && !ShowtimeAllocation.STATUS_OVER_ALLOCATED.equals(previousStatus)
                ? "OVER_ALLOCATED"
                : ShowtimeAllocation.STATUS_COMPLETED.equals(newStatus)
                        && !ShowtimeAllocation.STATUS_COMPLETED.equals(previousStatus)
                        ? "COMPLETED" : "PROGRESSED";
        dao.insertHistory(conn, allocation.id(), eventType,
                "{\"created_quantity\":" + allocation.createdQuantity()
                        + ",\"status\":\"" + previousStatus + "\"}",
                "{\"created_quantity\":" + createdQuantity
                        + ",\"status\":\"" + newStatus + "\"}",
                actorUserId, note);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean canReadAllocation(AccessScope scope, ShowtimeAllocation a) {
        if (scope.isGuest() || scope.role() == Role.CUSTOMER
                || scope.role() == Role.BRANCH_STAFF) {
            return false;
        }
        if (scope.role() == Role.ADMIN) return true;
        return scope.includesBranch(a.branchId());
    }

    private void requireAdmin(AccessScope scope) {
        if (scope.isGuest() || scope.role() != Role.ADMIN) {
            throw new ServiceException.Forbidden(
                    "Chỉ Admin được thực hiện thao tác này");
        }
    }

    private void validateQuantity(int qty) {
        if (qty < 0) {
            throw new ServiceException.Validation("Số lượng phân bổ phải >= 0");
        }
        if (qty > 10000) {
            throw new ServiceException.Validation("Số lượng phân bổ quá lớn (max 10000)");
        }
    }

    private void validateMovieAndBranch(long movieId, long branchId) {
        try (Connection conn = DBContext.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM dbo.movie WHERE id = ? AND status = 'PUBLISHED'")) {
                ps.setLong(1, movieId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ServiceException.Validation(
                                "Movie không tồn tại hoặc chưa được publish");
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM dbo.branch WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setLong(1, branchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ServiceException.Validation(
                                "Branch không tồn tại hoặc không active");
                    }
                }
            }
        } catch (ServiceException se) {
            throw se;
        } catch (SQLException e) {
            throw new ServiceException("INTERNAL_ERROR", e.getMessage(), 500);
        }
    }

    /**
     * Tính status từ allocated/created. Quy tắc ưu tiên:
     * <ul>
     *   <li>created &gt; allocated → OVER_ALLOCATED</li>
     *   <li>created == 0 → PENDING</li>
     *   <li>created == allocated → COMPLETED</li>
     *   <li>0 &lt; created &lt; allocated → IN_PROGRESS</li>
     * </ul>
     */
    public static String computeStatus(int allocated, int created) {
        if (allocated < 0 || created < 0) return ShowtimeAllocation.STATUS_PENDING;
        if (created > allocated) return ShowtimeAllocation.STATUS_OVER_ALLOCATED;
        if (created == 0 || allocated == 0) return ShowtimeAllocation.STATUS_PENDING;
        if (created == allocated) return ShowtimeAllocation.STATUS_COMPLETED;
        return ShowtimeAllocation.STATUS_IN_PROGRESS;
    }

    private void notifyManagerOnAllocation(ShowtimeAllocation a) {
        if (notificationService == null) return;
        try {
            String title = String.format("Phân bổ %d suất chiếu mới", a.allocatedQuantity());
            AllocationNames names = findAllocationNames(a);
            String body = String.format(
                    "Admin đã phân bổ %d suất chiếu cho phim %s tại chi nhánh %s. "
                            + "Vui lòng tạo lịch chiếu và phòng chiếu tương ứng.",
                    a.allocatedQuantity(), names.movieTitle(), names.branchName());
            notificationService.notifyBranchManagers(a.branchId(),
                    "SHOWTIME_ALLOCATION_NEW", title, body);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được thông báo phân bổ", e);
        }
    }

    private void notifyManagerOnAllocationUpdate(int oldQuantity, ShowtimeAllocation allocation) {
        if (notificationService == null) return;
        try {
            AllocationNames names = findAllocationNames(allocation);
            String body = formatAllocationUpdateNotificationBody(
                    names.movieTitle(), names.branchName(), oldQuantity,
                    allocation.allocatedQuantity(), allocation.createdQuantity());
            notificationService.notifyBranchManagers(allocation.branchId(),
                    Notification.TYPE_SHOWTIME_ALLOCATION_UPDATED,
                    "Phân bổ suất chiếu đã được cập nhật", body);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được thông báo cập nhật phân bổ", e);
        }
    }

    static String formatAllocationUpdateNotificationBody(String movieTitle, String branchName,
                                                         int oldQuantity, int newQuantity,
                                                         int createdQuantity) {
        int remaining = newQuantity - createdQuantity;
        String progress = remaining >= 0
                ? String.format("Chi nhánh đã tạo %d suất và còn cần tạo %d suất.",
                        createdQuantity, remaining)
                : String.format("Chi nhánh đã tạo %d suất, vượt %d suất so với phân bổ mới.",
                        createdQuantity, -remaining);
        return String.format(
                "Admin đã cập nhật số suất phân bổ của phim %s tại chi nhánh %s từ %d lên %d. %s",
                movieTitle, branchName, oldQuantity, newQuantity, progress);
    }

    private void handleOverAllocation(ShowtimeAllocation a, Long actorUserId) {
        if (notificationService == null) return;
        String body;
        try {
            AllocationNames names = findAllocationNames(a);
            body = String.format(
                    "Phim %s tại chi nhánh %s đang tạo %d suất chiếu, vượt quá số lượng được Admin phân bổ là %d suất.",
                    names.movieTitle(), names.branchName(), a.createdQuantity(), a.allocatedQuantity());
            notificationService.notifyBranchManagers(a.branchId(),
                    "SHOWTIME_OVER_ALLOCATION",
                    "Cảnh báo vượt phân bổ suất chiếu", body);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được cảnh báo vượt phân bổ", e);
            return;
        }
        // Cảnh báo Admin (best-effort)
        try {
            notificationService.notifyUser(
                    a.createdBy() == null ? actorUserId : a.createdBy(),
                    "SHOWTIME_OVER_ALLOCATION",
                    "Chi nhánh tạo suất chiếu vượt phân bổ",
                    body);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được cảnh báo allocation tới Admin", e);
        }
    }

    private void handleCompleted(ShowtimeAllocation a, Long actorUserId) {
        if (notificationService == null) return;
        try {
            AllocationNames names = findAllocationNames(a);
            String body = String.format(
                    "Chi nhánh %s đã hoàn tất %d/%d suất chiếu cho phim %s.",
                    names.branchName(), a.createdQuantity(), a.allocatedQuantity(), names.movieTitle());
            notificationService.notifyBranchManagers(a.branchId(),
                    "SHOWTIME_ALLOCATION_COMPLETED",
                    "Đã hoàn tất phân bổ suất chiếu", body);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Không gửi được thông báo hoàn tất phân bổ", e);
        }
    }

    private AllocationNames findAllocationNames(ShowtimeAllocation allocation) throws SQLException {
        String sql = """
                SELECT m.title AS movie_title, b.name AS branch_name
                FROM dbo.movie m
                CROSS JOIN dbo.branch b
                WHERE m.id = ? AND b.id = ?
                """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, allocation.movieId());
            ps.setLong(2, allocation.branchId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Không tìm thấy tên phim hoặc chi nhánh cho phân bổ "
                            + allocation.id());
                }
                return new AllocationNames(rs.getString("movie_title"), rs.getString("branch_name"));
            }
        }
    }

    private record AllocationNames(String movieTitle, String branchName) { }

    public record QuantityChange(String previousStatus, ShowtimeAllocation allocation) { }
}
