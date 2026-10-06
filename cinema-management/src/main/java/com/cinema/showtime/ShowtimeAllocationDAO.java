package com.cinema.showtime;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persistence for branch showtime allocations. */
public class ShowtimeAllocationDAO {
    public static Timestamp timestampOrNull(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    public List<ShowtimeAllocation> find(String status, Long branchId, Long movieId) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.movie_id, a.branch_id, m.title AS movie_title,
                       b.name AS branch_name, a.allocated_quantity, a.created_quantity,
                       a.status, a.note, a.reminder_release_date, a.allocated_at, a.completed_at,
                       a.schedule_reminder_sent_at, a.schedule_urgent_reminder_sent_at
                FROM dbo.showtime_allocation a
                JOIN dbo.movie m ON m.id = a.movie_id
                JOIN dbo.branch b ON b.id = a.branch_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND a.status = ?");
            params.add(status);
        }
        if (branchId != null) {
            sql.append(" AND a.branch_id = ?");
            params.add(branchId);
        }
        if (movieId != null) {
            sql.append(" AND a.movie_id = ?");
            params.add(movieId);
        }
        sql.append(" ORDER BY a.allocated_at DESC, a.id DESC");
        try (Connection connection = DBContext.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ShowtimeAllocation> rows = new ArrayList<>();
                while (resultSet.next()) rows.add(map(resultSet));
                return rows;
            }
        }
    }

    public Optional<ShowtimeAllocation> findById(long id) throws Exception {
        String sql = """
                SELECT a.id, a.movie_id, a.branch_id, m.title AS movie_title,
                       b.name AS branch_name, a.allocated_quantity, a.created_quantity,
                       a.status, a.note, a.reminder_release_date, a.allocated_at, a.completed_at,
                       a.schedule_reminder_sent_at, a.schedule_urgent_reminder_sent_at
                FROM dbo.showtime_allocation a
                JOIN dbo.movie m ON m.id = a.movie_id
                JOIN dbo.branch b ON b.id = a.branch_id
                WHERE a.id = ?
                """;
        try (Connection connection = DBContext.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public void insert(ShowtimeAllocation allocation, Long actorUserId) throws Exception {
        String sql = """
                INSERT INTO dbo.showtime_allocation
                    (movie_id, branch_id, allocated_quantity, created_quantity, status,
                     note, created_by, updated_by)
                SELECT ?, ?, ?, COUNT(s.id),
                       CASE
                           WHEN COUNT(s.id) > ? THEN 'OVER_ALLOCATED'
                           WHEN ? > 0 AND COUNT(s.id) = ? THEN 'COMPLETED'
                           WHEN COUNT(s.id) > 0 THEN 'IN_PROGRESS'
                           ELSE 'PENDING'
                       END,
                       ?, ?, ?
                FROM dbo.showtime s
                WHERE s.movie_id = ? AND s.branch_id = ? AND s.status <> 'CANCELLED'
                """;
        try (Connection connection = DBContext.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, allocation.movieId());
            statement.setLong(2, allocation.branchId());
            statement.setInt(3, allocation.allocatedQuantity());
            statement.setInt(4, allocation.allocatedQuantity());
            statement.setInt(5, allocation.allocatedQuantity());
            statement.setInt(6, allocation.allocatedQuantity());
            statement.setString(7, allocation.note());
            setNullableLong(statement, 8, actorUserId);
            setNullableLong(statement, 9, actorUserId);
            statement.setLong(10, allocation.movieId());
            statement.setLong(11, allocation.branchId());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) allocation.setId(keys.getLong(1));
            }
        }
    }

    public boolean existsForMovieAndBranch(long movieId, long branchId) throws Exception {
        String sql = "SELECT 1 FROM dbo.showtime_allocation WHERE movie_id = ? AND branch_id = ?";
        try (Connection connection = DBContext.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, movieId);
            statement.setLong(2, branchId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public boolean consumeSlot(Connection connection, long movieId, long branchId) throws SQLException {
        String sql = """
                UPDATE dbo.showtime_allocation WITH (UPDLOCK, ROWLOCK)
                SET created_quantity = created_quantity + 1,
                    status = CASE
                        WHEN created_quantity + 1 > allocated_quantity THEN 'OVER_ALLOCATED'
                        WHEN allocated_quantity > 0 AND created_quantity + 1 = allocated_quantity THEN 'COMPLETED'
                        ELSE 'IN_PROGRESS'
                    END,
                    completed_at = CASE
                        WHEN allocated_quantity > 0 AND created_quantity + 1 = allocated_quantity
                            THEN SYSUTCDATETIME()
                        ELSE NULL
                    END,
                    updated_at = SYSUTCDATETIME()
                WHERE movie_id = ? AND branch_id = ?
                  AND created_quantity < allocated_quantity
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, movieId);
            statement.setLong(2, branchId);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean releaseSlot(Connection connection, long movieId, long branchId) throws SQLException {
        String sql = """
                UPDATE dbo.showtime_allocation
                SET created_quantity = created_quantity - 1,
                    status = CASE
                        WHEN created_quantity - 1 > allocated_quantity THEN 'OVER_ALLOCATED'
                        WHEN allocated_quantity > 0 AND created_quantity - 1 = allocated_quantity THEN 'COMPLETED'
                        WHEN created_quantity - 1 > 0 THEN 'IN_PROGRESS'
                        ELSE 'PENDING'
                    END,
                    completed_at = CASE
                        WHEN allocated_quantity > 0 AND created_quantity - 1 = allocated_quantity
                            THEN COALESCE(completed_at, SYSUTCDATETIME())
                        ELSE NULL
                    END,
                    updated_at = SYSUTCDATETIME()
                WHERE movie_id = ? AND branch_id = ? AND created_quantity > 0
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, movieId);
            statement.setLong(2, branchId);
            return statement.executeUpdate() == 1;
        }
    }

    public List<ScheduleReminderCandidate> findScheduleReminderCandidates(
                java.time.LocalDate businessDate) throws Exception {
            String sql = """
                    SELECT a.id, a.branch_id, m.title AS movie_title, b.name AS branch_name,
                           m.release_date, a.allocated_quantity, a.created_quantity,
                           a.schedule_reminder_sent_at, a.schedule_urgent_reminder_sent_at,
                           m.status AS movie_status
                    FROM dbo.showtime_allocation a
                    JOIN dbo.movie m ON m.id = a.movie_id
                    JOIN dbo.branch b ON b.id = a.branch_id
                    WHERE m.status = 'PUBLISHED'
                      AND m.release_date BETWEEN ? AND ?
                      AND a.created_quantity < a.allocated_quantity
                    """;
            List<ScheduleReminderCandidate> candidates = new ArrayList<>();
            try (Connection connection = DBContext.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDate(1, java.sql.Date.valueOf(businessDate.plusDays(1)));
                statement.setDate(2, java.sql.Date.valueOf(businessDate.plusDays(7)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        java.sql.Date release = resultSet.getDate("release_date");
                        if (release == null) continue;
                        LocalDateTime normalSent =
                                timestampToLocalDateTime(resultSet.getTimestamp("schedule_reminder_sent_at"));
                        LocalDateTime urgentSent =
                                timestampToLocalDateTime(resultSet.getTimestamp("schedule_urgent_reminder_sent_at"));
                        candidates.add(new ScheduleReminderCandidate(
                                resultSet.getLong("id"), resultSet.getLong("branch_id"),
                                resultSet.getString("movie_title"), resultSet.getString("branch_name"),
                                release.toLocalDate(), resultSet.getInt("allocated_quantity"),
                                resultSet.getInt("created_quantity"), normalSent, urgentSent));
                    }
                }
            }
            return candidates;
    }

    public void markScheduleReminderSent(long allocationId, boolean urgent) throws Exception {
            String column = urgent ? "schedule_urgent_reminder_sent_at" : "schedule_reminder_sent_at";
            String sql = "UPDATE dbo.showtime_allocation SET " + column
                    + " = SYSUTCDATETIME(), updated_at = SYSUTCDATETIME() WHERE id = ?";
            try (Connection connection = DBContext.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, allocationId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Allocation not found while marking reminder");
            }
        }
    }

    public boolean update(long id, int allocatedQuantity, String note, Long actorUserId)
            throws Exception {
        String sql = """
                UPDATE dbo.showtime_allocation
                SET allocated_quantity = ?, note = ?, updated_by = ?,
                    status = CASE
                        WHEN created_quantity > ? THEN 'OVER_ALLOCATED'
                        WHEN ? > 0 AND created_quantity = ? THEN 'COMPLETED'
                        WHEN created_quantity > 0 THEN 'IN_PROGRESS'
                        ELSE 'PENDING'
                    END,
                    completed_at = CASE
                        WHEN ? > 0 AND created_quantity = ? THEN COALESCE(completed_at, SYSUTCDATETIME())
                        ELSE NULL
                    END,
                    updated_at = SYSUTCDATETIME()
                WHERE id = ?
                """;
        try (Connection connection = DBContext.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, allocatedQuantity);
            statement.setString(2, note);
            setNullableLong(statement, 3, actorUserId);
            statement.setInt(4, allocatedQuantity);
            statement.setInt(5, allocatedQuantity);
            statement.setInt(6, allocatedQuantity);
            statement.setInt(7, allocatedQuantity);
            statement.setInt(8, allocatedQuantity);
            statement.setLong(9, id);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deleteIfUnused(long id) throws Exception {
        try (Connection connection = DBContext.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM dbo.showtime_allocation WHERE id = ? AND created_quantity = 0")) {
            statement.setLong(1, id);
            return statement.executeUpdate() == 1;
        }
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) statement.setObject(i + 1, params.get(i));
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, value);
    }

    private static ShowtimeAllocation map(ResultSet resultSet) throws SQLException {
        ShowtimeAllocation allocation = new ShowtimeAllocation();
        allocation.setId(resultSet.getLong("id"));
        allocation.setMovieId(resultSet.getLong("movie_id"));
        allocation.setBranchId(resultSet.getLong("branch_id"));
        allocation.setMovieTitle(resultSet.getString("movie_title"));
        allocation.setBranchName(resultSet.getString("branch_name"));
        allocation.setAllocatedQuantity(resultSet.getInt("allocated_quantity"));
        allocation.setCreatedQuantity(resultSet.getInt("created_quantity"));
        allocation.setStatus(resultSet.getString("status"));
        allocation.setNote(resultSet.getString("note"));
        java.sql.Date releaseDate = resultSet.getDate("reminder_release_date");
        if (releaseDate != null) allocation.setReminderReleaseDate(releaseDate.toLocalDate());
        Timestamp allocatedAt = resultSet.getTimestamp("allocated_at");
        if (allocatedAt != null) allocation.setAllocatedAt(allocatedAt.toLocalDateTime());
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        if (completedAt != null) allocation.setCompletedAt(completedAt.toLocalDateTime());
        Timestamp normalReminder = resultSet.getTimestamp("schedule_reminder_sent_at");
        if (normalReminder != null) allocation.setScheduleReminderSentAt(normalReminder.toLocalDateTime());
        Timestamp urgentReminder = resultSet.getTimestamp("schedule_urgent_reminder_sent_at");
        if (urgentReminder != null) allocation.setScheduleUrgentReminderSentAt(urgentReminder.toLocalDateTime());
        return allocation;
    }

    private static LocalDateTime timestampToLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record ScheduleReminderCandidate(long allocationId, long branchId, String movieTitle,
                                            String branchName, java.time.LocalDate releaseDate,
                                            int allocatedQuantity, int createdQuantity,
                                            LocalDateTime normalReminderSentAt,
                                            LocalDateTime urgentReminderSentAt) { }
}
