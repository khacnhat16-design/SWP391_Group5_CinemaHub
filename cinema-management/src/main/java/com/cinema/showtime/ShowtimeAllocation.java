package com.cinema.showtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
/**
 * Phân bổ suất chiếu (showtime allocation) — Admin phân bổ tổng số suất chiếu
 * cho từng (Movie, Branch) và Manager phải tạo đủ số suất.
 *
 * <p>Trạng thái:
 * <pre>
 *   allocated = 0, created = 0  → PENDING
 *   0 &lt; created &lt; allocated     → IN_PROGRESS
 *   created = allocated &gt; 0     → COMPLETED
 *   created &gt; allocated         → OVER_ALLOCATED
 * </pre>
 */
public class ShowtimeAllocation {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_OVER_ALLOCATED = "OVER_ALLOCATED";

    public static final java.util.Set<String> VALID_STATUSES = java.util.Set.of(
            STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_OVER_ALLOCATED);

    private Long id;
    private long movieId;
    private long branchId;
    private int allocatedQuantity;
    private int createdQuantity;
    private String status;
    private String note;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime allocatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime lastWarningAt;
    private LocalDate reminderReleaseDate;
    private LocalDateTime scheduleReminderSentAt;
    private LocalDateTime scheduleUrgentReminderSentAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Joined fields for UI
    private String movieTitle;
    private String branchName;

    public ShowtimeAllocation() {}

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }

    public long movieId() { return movieId; }
    public void setMovieId(long movieId) { this.movieId = movieId; }

    public long branchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }

    public int allocatedQuantity() { return allocatedQuantity; }
    public void setAllocatedQuantity(int allocatedQuantity) {
        this.allocatedQuantity = allocatedQuantity;
    }

    public int createdQuantity() { return createdQuantity; }
    public void setCreatedQuantity(int createdQuantity) {
        this.createdQuantity = createdQuantity;
    }

    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String note() { return note; }
    public void setNote(String note) { this.note = note; }

    public Long createdBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long updatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime allocatedAt() { return allocatedAt; }
    public void setAllocatedAt(LocalDateTime allocatedAt) { this.allocatedAt = allocatedAt; }

    public LocalDateTime completedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime lastWarningAt() { return lastWarningAt; }
    public void setLastWarningAt(LocalDateTime lastWarningAt) {
        this.lastWarningAt = lastWarningAt;
    }

    public LocalDate reminderReleaseDate() { return reminderReleaseDate; }
    public void setReminderReleaseDate(LocalDate reminderReleaseDate) {
        this.reminderReleaseDate = reminderReleaseDate;
    }

    public LocalDateTime scheduleReminderSentAt() { return scheduleReminderSentAt; }
    public void setScheduleReminderSentAt(LocalDateTime scheduleReminderSentAt) {
        this.scheduleReminderSentAt = scheduleReminderSentAt;
    }

    public LocalDateTime scheduleUrgentReminderSentAt() { return scheduleUrgentReminderSentAt; }
    public void setScheduleUrgentReminderSentAt(LocalDateTime scheduleUrgentReminderSentAt) {
        this.scheduleUrgentReminderSentAt = scheduleUrgentReminderSentAt;
    }

    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime updatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String movieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String branchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    /**
     * Số suất còn thiếu (allocated - created). Có thể âm khi đã vượt quota.
     */
    @JsonProperty("remainingQuantity")
    public int remainingQuantity() {
        return allocatedQuantity - createdQuantity;
    }

    /**
     * @return {@code true} khi Manager đã tạo đúng bằng phân bổ.
     */
    public boolean isCompleted() {
        return allocatedQuantity > 0 && createdQuantity == allocatedQuantity;
    }

    /**
     * @return {@code true} khi Manager tạo vượt số phân bổ.
     */
    public boolean isOverAllocated() {
        return createdQuantity > allocatedQuantity;
    }
}
