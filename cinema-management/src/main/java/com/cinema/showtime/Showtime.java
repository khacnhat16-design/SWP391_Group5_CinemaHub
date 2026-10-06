package com.cinema.showtime;

import java.time.LocalDateTime;

/** Entity lịch chiếu (Req 4). end_time = start_time + thời lượng phim; buffer lưu riêng. */
public class Showtime {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ENDED = "ENDED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;
    private long movieId;
    private long screenId;
    private long branchId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int cleaningBufferMin;
    private String status;
    private int version;

    // Trường mở rộng khi join cho discovery (Req 6.1)
    private String movieTitle;
    private String screenName;
    private String branchName;
    private int availableSeats;
    private long standardPrice;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public long movieId() { return movieId; }
    public void setMovieId(long movieId) { this.movieId = movieId; }
    public long screenId() { return screenId; }
    public void setScreenId(long screenId) { this.screenId = screenId; }
    public long branchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }
    public LocalDateTime startTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime endTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public int cleaningBufferMin() { return cleaningBufferMin; }
    public void setCleaningBufferMin(int cleaningBufferMin) { this.cleaningBufferMin = cleaningBufferMin; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String movieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
    public String screenName() { return screenName; }
    public void setScreenName(String screenName) { this.screenName = screenName; }
    public String branchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public int availableSeats() { return availableSeats; }
    public void setAvailableSeats(int availableSeats) { this.availableSeats = availableSeats; }
    public long standardPrice() { return standardPrice; }
    public void setStandardPrice(long standardPrice) { this.standardPrice = standardPrice; }

    /** Thời điểm không được nhận hold mới (Req 4.6): sau end_time + buffer. */
    public LocalDateTime bookingCutoff() {
        return endTime != null ? endTime.plusMinutes(cleaningBufferMin) : null;
    }
}
