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
