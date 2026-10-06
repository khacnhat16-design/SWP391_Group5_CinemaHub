package com.cinema.showtime;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/** Per-movie, per-branch showtime quota assigned by an administrator. */
public class ShowtimeAllocation {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_OVER_ALLOCATED = "OVER_ALLOCATED";
    public static final Set<String> VALID_STATUSES = Set.of(
            STATUS_PENDING, STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_OVER_ALLOCATED);

    private Long id;
    private Long movieId;
    private Long branchId;
    private String movieTitle;
    private String branchName;
    private int allocatedQuantity;
    private int createdQuantity;
    private String status = STATUS_PENDING;
    private String note;
    private LocalDate reminderReleaseDate;
    private LocalDateTime allocatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime scheduleReminderSentAt;
    private LocalDateTime scheduleUrgentReminderSentAt;

    public Long id() { return id; }
    public Long movieId() { return movieId; }
    public Long branchId() { return branchId; }
    public String movieTitle() { return movieTitle; }
    public String branchName() { return branchName; }
    public int allocatedQuantity() { return allocatedQuantity; }
    public int createdQuantity() { return createdQuantity; }
    public int remainingQuantity() { return allocatedQuantity - createdQuantity; }
    @JsonProperty("remainingQuantity")
    public int getRemainingQuantity() { return remainingQuantity(); }
    public String status() { return status; }
    public String note() { return note; }
    public LocalDate reminderReleaseDate() { return reminderReleaseDate; }
    public LocalDateTime allocatedAt() { return allocatedAt; }
    public LocalDateTime completedAt() { return completedAt; }
    public LocalDateTime scheduleReminderSentAt() { return scheduleReminderSentAt; }
    public LocalDateTime scheduleUrgentReminderSentAt() { return scheduleUrgentReminderSentAt; }
    public boolean isCompleted() {
        return allocatedQuantity > 0 && allocatedQuantity == createdQuantity;
    }
    public boolean isOverAllocated() { return createdQuantity > allocatedQuantity; }

    public void setId(Long id) { this.id = id; }
    public void setMovieId(Long movieId) { this.movieId = movieId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public void setAllocatedQuantity(int allocatedQuantity) { this.allocatedQuantity = allocatedQuantity; }
    public void setCreatedQuantity(int createdQuantity) { this.createdQuantity = createdQuantity; }
    public void setStatus(String status) { this.status = status; }
    public void setNote(String note) { this.note = note; }
    public void setReminderReleaseDate(LocalDate reminderReleaseDate) {
        this.reminderReleaseDate = reminderReleaseDate;
    }
    public void setAllocatedAt(LocalDateTime allocatedAt) { this.allocatedAt = allocatedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public void setScheduleReminderSentAt(LocalDateTime value) { this.scheduleReminderSentAt = value; }
    public void setScheduleUrgentReminderSentAt(LocalDateTime value) {
        this.scheduleUrgentReminderSentAt = value;
    }
}
