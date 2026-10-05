package com.cinema.showtime;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Data access cho {@link ShowtimeAllocation}.
 *
 * <p>Sử dụng {@code WITH (UPDLOCK, HOLDLOCK, ROWLOCK)} trên các thao tác tăng
 * {@code created_quantity} để tránh race condition khi nhiều request tạo
 * showtime đồng thời (Section 12 của yêu cầu).
 */
public class ShowtimeAllocationDAO {

    /** Hint khóa chống race khi cập nhật createdQuantity đồng thời. */
    private static final String LOCK_HINT = "WITH (UPDLOCK, HOLDLOCK, ROWLOCK)";

    private static final String BASE_COLUMNS = """
            a.id, a.movie_id, a.branch_id, a.allocated_quantity, a.created_quantity,
            a.status, a.note, a.created_by, a.updated_by,
            a.allocated_at, a.completed_at, a.last_warning_at,
            a.reminder_release_date, a.schedule_reminder_sent_at,
            a.schedule_urgent_reminder_sent_at,
            a.created_at, a.updated_at
            """;

    /**
     * Lấy allocation theo (movie, branch) — có khóa row. Phải gọi trong
     * transaction (autoCommit=false) để hint UPDLOCK/HOLDLOCK có hiệu lực.
     */
    public Optional<ShowtimeAllocation> findLocked(Connection conn, long movieId, long branchId)
            throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.showtime_allocation a " + LOCK_HINT
                + " WHERE a.movie_id = ? AND a.branch_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Lock allocation theo id — dùng khi Manager tạo showtime tăng createdQuantity.
     * Trả về {@link Optional#empty()} nếu không tồn tại.
     */
    public Optional<ShowtimeAllocation> findByIdLocked(Connection conn, long id)
            throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.showtime_allocation a " + LOCK_HINT
                + " WHERE a.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public List<ReminderCandidate> findReminderCandidates(LocalDate businessDate)
            throws SQLException {
        String sql = """
                SELECT a.id, a.movie_id, a.branch_id, a.allocated_quantity, a.created_quantity,
                       m.title AS movie_title, b.name AS branch_name, m.status AS movie_status,
                       m.release_date,
                       a.reminder_release_date, a.schedule_reminder_sent_at,
                       a.schedule_urgent_reminder_sent_at
                FROM dbo.showtime_allocation a
                JOIN dbo.movie m ON m.id = a.movie_id
                JOIN dbo.branch b ON b.id = a.branch_id
                WHERE a.allocated_quantity > a.created_quantity
                  AND a.allocated_quantity > 0
                  AND m.status = 'PUBLISHED'
                  AND m.release_date > ?
                  AND m.release_date <= DATEADD(day, 7, ?)
                  AND (
                      a.reminder_release_date IS NULL
                      OR a.reminder_release_date <> m.release_date
                      OR (m.release_date > DATEADD(day, 2, ?)
                          AND a.schedule_reminder_sent_at IS NULL)
                      OR (m.release_date <= DATEADD(day, 2, ?)
                          AND a.schedule_urgent_reminder_sent_at IS NULL)
                  )
                ORDER BY m.release_date, a.id
                """;
        List<ReminderCandidate> candidates = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            java.sql.Date date = java.sql.Date.valueOf(businessDate);
            ps.setDate(1, date);
            ps.setDate(2, date);
            ps.setDate(3, date);
            ps.setDate(4, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) candidates.add(mapReminderCandidate(rs));
            }
        }
        return candidates;
    }

    public Optional<ReminderCandidate> findReminderCandidateLocked(Connection conn, long id)
            throws SQLException {
        String sql = """
                SELECT a.id, a.movie_id, a.branch_id, a.allocated_quantity, a.created_quantity,
                       m.title AS movie_title, b.name AS branch_name, m.status AS movie_status,
                       m.release_date,
                       a.reminder_release_date, a.schedule_reminder_sent_at,
                       a.schedule_urgent_reminder_sent_at
                FROM dbo.showtime_allocation a WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
                JOIN dbo.movie m WITH (UPDLOCK, HOLDLOCK, ROWLOCK) ON m.id = a.movie_id
                JOIN dbo.branch b ON b.id = a.branch_id
                WHERE a.id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapReminderCandidate(rs));
            }
        }
        return Optional.empty();
    }

    public boolean updateReminderState(Connection conn, long id, LocalDate releaseDate,
                                       LocalDateTime normalSentAt,
                                       LocalDateTime urgentSentAt) throws SQLException {
        String sql = """
                UPDATE dbo.showtime_allocation
                SET reminder_release_date = ?,
                    schedule_reminder_sent_at = ?,
                    schedule_urgent_reminder_sent_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (releaseDate == null) ps.setNull(1, Types.DATE);
            else ps.setDate(1, java.sql.Date.valueOf(releaseDate));
            ps.setTimestamp(2, timestampOrNull(normalSentAt));
            ps.setTimestamp(3, timestampOrNull(urgentSentAt));
            ps.setLong(4, id);
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<ShowtimeAllocation> findById(long id) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.showtime_allocation a WHERE a.id = ?";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public List<ShowtimeAllocation> listAll(Long branchId, Long movieId, String status)
            throws Exception {
        return listAll(branchId == null ? null : Set.of(branchId), movieId, status);
    }

    public List<ShowtimeAllocation> listAllForBranches(Set<Long> branchIds, Long movieId,
                                                       String status) throws Exception {
        if (branchIds == null || branchIds.isEmpty()) return List.of();
        return listAll(branchIds, movieId, status);
    }

    private List<ShowtimeAllocation> listAll(Set<Long> branchIds, Long movieId, String status)
            throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.movie_id, a.branch_id, a.allocated_quantity, a.created_quantity,
                       a.status, a.note, a.created_by, a.updated_by,
                       a.allocated_at, a.completed_at, a.last_warning_at,
                       a.reminder_release_date, a.schedule_reminder_sent_at,
                       a.schedule_urgent_reminder_sent_at,
                       a.created_at, a.updated_at,
                       m.title AS movie_title, b.name AS branch_name
                FROM dbo.showtime_allocation a
                JOIN dbo.movie m  ON m.id  = a.movie_id
                JOIN dbo.branch b ON b.id = a.branch_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (branchIds != null) {
            sql.append(" AND a.branch_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(branchIds.size(), "?")))
                    .append(")");
            params.addAll(branchIds);
        }
        if (movieId != null) {
            sql.append(" AND a.movie_id = ?");
            params.add(movieId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND a.status = ?");
            params.add(status.trim());
        }
        sql.append(" ORDER BY a.updated_at DESC, a.id DESC");

        List<ShowtimeAllocation> result = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ShowtimeAllocation a = mapRow(rs);
                    a.setMovieTitle(rs.getString("movie_title"));
                    a.setBranchName(rs.getString("branch_name"));
                    result.add(a);
                }
            }
        }
        return result;
    }

    /** Insert — Admin tạo phân bổ. Trả về id. */
    public long insert(Connection conn, ShowtimeAllocation a) throws Exception {
        String sql = """
                INSERT INTO dbo.showtime_allocation
                    (movie_id, branch_id, allocated_quantity, created_quantity,
                     status, note, created_by, updated_by,
                     allocated_at, completed_at, last_warning_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, a.movieId());
            ps.setLong(2, a.branchId());
            ps.setInt(3, a.allocatedQuantity());
            ps.setInt(4, a.createdQuantity());
            ps.setString(5, a.status() == null ? ShowtimeAllocation.STATUS_PENDING : a.status());
            if (a.note() == null) ps.setNull(6, Types.NVARCHAR); else ps.setString(6, a.note());
            if (a.createdBy() == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, a.createdBy());
            if (a.updatedBy() == null) ps.setNull(8, Types.BIGINT); else ps.setLong(8, a.updatedBy());
            ps.setTimestamp(9, timestampOrNow(a.allocatedAt()));
            ps.setTimestamp(10, timestampOrNull(a.completedAt()));
            ps.setTimestamp(11, timestampOrNull(a.lastWarningAt()));
            ps.setTimestamp(12, timestampOrNow(a.createdAt()));
            ps.setTimestamp(13, timestampOrNow(a.updatedAt()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new IllegalStateException("Insert did not return generated key");
        }
    }

    /**
     * Update phân bổ (Admin) — không cho sửa nếu status COMPLETED trừ khi forceUpdate=true.
     * Trả về true nếu update đúng 1 dòng.
     */
    public boolean updateAllocation(Connection conn, long id, int allocatedQuantity,
                                    String note, Long updatedBy) throws Exception {
        String sql = """
                UPDATE dbo.showtime_allocation
                SET allocated_quantity = ?, note = COALESCE(?, note),
                    updated_by = ?, updated_at = SYSUTCDATETIME(),
                    allocated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, allocatedQuantity);
            if (note == null) ps.setNull(2, Types.NVARCHAR); else ps.setString(2, note);
            if (updatedBy == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, updatedBy);
            ps.setLong(4, id);
            return ps.executeUpdate() == 1;
        }
    }

    /** Xóa phân bổ (Admin) — chỉ xóa khi chưa có showtime nào được tạo. */
    public boolean deleteIfNoCreated(Connection conn, long id) throws Exception {
        String sql = """
                DELETE FROM dbo.showtime_allocation
                WHERE id = ? AND created_quantity = 0
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Đồng bộ counter và status từ COUNT(showtime). Phải gọi khi giữ khóa allocation.
     */
    public boolean updateCreatedQuantityAndStatus(Connection conn, long id, int newCreated,
                                                 String newStatus,
                                                 LocalDateTime completedAt,
                                                 LocalDateTime lastWarningAt,
                                                 Long updatedBy) throws Exception {
        String sql = """
                UPDATE dbo.showtime_allocation
                SET created_quantity = ?, status = ?,
                    completed_at = ?, last_warning_at = ?,
                    updated_by = ?, updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newCreated);
            ps.setString(2, newStatus);
            ps.setTimestamp(3, completedAt == null ? null : Timestamp.valueOf(completedAt));
            ps.setTimestamp(4, lastWarningAt == null ? null : Timestamp.valueOf(lastWarningAt));
            if (updatedBy == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, updatedBy);
            ps.setLong(6, id);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean updateStatusOnly(Connection conn, long id, String newStatus,
                                    LocalDateTime completedAt, Long updatedBy)
            throws Exception {
        String sql = """
                UPDATE dbo.showtime_allocation
                SET status = ?, completed_at = ?, updated_by = ?, updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setTimestamp(2, completedAt == null ? null : Timestamp.valueOf(completedAt));
            if (updatedBy == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, updatedBy);
            ps.setLong(4, id);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Đếm số showtime tương ứng (movie, branch) — dùng để tính createdQuantity
     * ban đầu khi Admin tạo allocation và Manager chưa tạo gì.
     */
    public int countShowtimes(Connection conn, long movieId, long branchId) throws Exception {
        String sql = """
                SELECT COUNT(*) FROM dbo.showtime
                WHERE movie_id = ? AND branch_id = ? AND status <> 'CANCELLED'
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, movieId);
            ps.setLong(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public int countShowtimes(long movieId, long branchId) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return countShowtimes(conn, movieId, branchId);
        }
    }

    // ---- history ----

    public void insertHistory(Connection conn, long allocationId, String eventType,
                              String oldValue, String newValue,
                              Long actorUserId, String note) throws Exception {
        String sql = """
                INSERT INTO dbo.showtime_allocation_history
                    (allocation_id, event_type, old_value, new_value, actor_user_id, note)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, allocationId);
            ps.setString(2, eventType);
            if (oldValue == null) ps.setNull(3, Types.NVARCHAR); else ps.setString(3, oldValue);
            if (newValue == null) ps.setNull(4, Types.NVARCHAR); else ps.setString(4, newValue);
            if (actorUserId == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, actorUserId);
            if (note == null) ps.setNull(6, Types.NVARCHAR); else ps.setString(6, note);
            ps.executeUpdate();
        }
    }

    public List<ShowtimeAllocationHistory> listHistory(long allocationId) throws Exception {
        String sql = """
                SELECT id, allocation_id, event_type, old_value, new_value,
                       actor_user_id, note, created_at
                FROM dbo.showtime_allocation_history
                WHERE allocation_id = ?
                ORDER BY created_at DESC, id DESC
                """;
        List<ShowtimeAllocationHistory> out = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, allocationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ShowtimeAllocationHistory h = new ShowtimeAllocationHistory();
                    h.setId(rs.getLong("id"));
                    h.setAllocationId(rs.getLong("allocation_id"));
                    h.setEventType(rs.getString("event_type"));
                    h.setOldValue(rs.getString("old_value"));
                    h.setNewValue(rs.getString("new_value"));
                    long actor = rs.getLong("actor_user_id");
                    h.setActorUserId(rs.wasNull() ? null : actor);
                    h.setNote(rs.getString("note"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    if (ts != null) h.setCreatedAt(ts.toLocalDateTime());
                    out.add(h);
                }
            }
        }
        return out;
    }

    // ---- helpers ----

    static Timestamp timestampOrNull(LocalDateTime ts) {
        return ts == null ? null : Timestamp.valueOf(ts);
    }

    private static Timestamp timestampOrNow(LocalDateTime ts) {
        return ts == null ? Timestamp.valueOf(LocalDateTime.now()) : Timestamp.valueOf(ts);
    }

    private ShowtimeAllocation mapRow(ResultSet rs) throws Exception {
        ShowtimeAllocation a = new ShowtimeAllocation();
        a.setId(rs.getLong("id"));
        a.setMovieId(rs.getLong("movie_id"));
        a.setBranchId(rs.getLong("branch_id"));
        a.setAllocatedQuantity(rs.getInt("allocated_quantity"));
        a.setCreatedQuantity(rs.getInt("created_quantity"));
        a.setStatus(rs.getString("status"));
        a.setNote(rs.getString("note"));
        long cb = rs.getLong("created_by");
        a.setCreatedBy(rs.wasNull() ? null : cb);
        long ub = rs.getLong("updated_by");
        a.setUpdatedBy(rs.wasNull() ? null : ub);
        Timestamp allocatedAt = rs.getTimestamp("allocated_at");
        if (allocatedAt != null) a.setAllocatedAt(allocatedAt.toLocalDateTime());
        Timestamp completedAt = rs.getTimestamp("completed_at");
        if (completedAt != null) a.setCompletedAt(completedAt.toLocalDateTime());
        Timestamp lastWarn = rs.getTimestamp("last_warning_at");
        if (lastWarn != null) a.setLastWarningAt(lastWarn.toLocalDateTime());
        java.sql.Date reminderReleaseDate = rs.getDate("reminder_release_date");
        if (reminderReleaseDate != null) {
            a.setReminderReleaseDate(reminderReleaseDate.toLocalDate());
        }
        Timestamp scheduleReminderSentAt = rs.getTimestamp("schedule_reminder_sent_at");
        if (scheduleReminderSentAt != null) {
            a.setScheduleReminderSentAt(scheduleReminderSentAt.toLocalDateTime());
        }
        Timestamp scheduleUrgentReminderSentAt =
                rs.getTimestamp("schedule_urgent_reminder_sent_at");
        if (scheduleUrgentReminderSentAt != null) {
            a.setScheduleUrgentReminderSentAt(scheduleUrgentReminderSentAt.toLocalDateTime());
        }
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) a.setCreatedAt(createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) a.setUpdatedAt(updatedAt.toLocalDateTime());
        return a;
    }

    private ReminderCandidate mapReminderCandidate(ResultSet rs) throws SQLException {
        java.sql.Date releaseDate = rs.getDate("release_date");
        java.sql.Date reminderReleaseDate = rs.getDate("reminder_release_date");
        Timestamp normalSentAt = rs.getTimestamp("schedule_reminder_sent_at");
        Timestamp urgentSentAt = rs.getTimestamp("schedule_urgent_reminder_sent_at");
        return new ReminderCandidate(
                rs.getLong("id"),
                rs.getLong("movie_id"),
                rs.getLong("branch_id"),
                rs.getInt("allocated_quantity"),
                rs.getInt("created_quantity"),
                rs.getString("movie_title"),
                rs.getString("branch_name"),
                rs.getString("movie_status"),
                releaseDate == null ? null : releaseDate.toLocalDate(),
                reminderReleaseDate == null ? null : reminderReleaseDate.toLocalDate(),
                normalSentAt == null ? null : normalSentAt.toLocalDateTime(),
                urgentSentAt == null ? null : urgentSentAt.toLocalDateTime());
    }

    public record ReminderCandidate(long allocationId, long movieId, long branchId,
                                    int allocatedQuantity, int createdQuantity,
                                    String movieTitle, String branchName, String movieStatus,
                                    LocalDate releaseDate, LocalDate reminderReleaseDate,
                                    LocalDateTime normalSentAt, LocalDateTime urgentSentAt) { }

    /** Audit trail entry — public DTO. */
    public static class ShowtimeAllocationHistory {
        private Long id;
        private Long allocationId;
        private String eventType;
        private String oldValue;
        private String newValue;
        private Long actorUserId;
        private String note;
        private LocalDateTime createdAt;

        public Long id() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long allocationId() { return allocationId; }
        public void setAllocationId(Long allocationId) { this.allocationId = allocationId; }
        public String eventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String oldValue() { return oldValue; }
        public void setOldValue(String oldValue) { this.oldValue = oldValue; }
        public String newValue() { return newValue; }
        public void setNewValue(String newValue) { this.newValue = newValue; }
        public Long actorUserId() { return actorUserId; }
        public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
        public String note() { return note; }
        public void setNote(String note) { this.note = note; }
        public LocalDateTime createdAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}
