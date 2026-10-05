package com.cinema.movie;

import java.time.LocalDate;

/** Movie entity for the centralized catalog (Admin-managed, Req 2.1-2.6). */
public class Movie {
    /** Trạng thái vòng đời phim theo CK_movie_status. */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** Phân loại độ tuổi hợp lệ theo CK_movie_rating (Req 2.6). */
    public static final java.util.Set<String> VALID_RATINGS = java.util.Set.of("P", "C13", "C16", "C18");

    private Long id;
    private String title;
    private Integer durationMin;
    private String genre;
    private String rating; // P, C13, C16, C18
    private LocalDate releaseDate;
    private LocalDate endDate;
    private String posterUrl;
    private String description;
    private String author; // Tác giả kịch bản hoặc đạo diễn chính; optional metadata cho UI detail.
    private String status; // Lifecycle: DRAFT (chưa công chiếu) → PUBLISHED (đang bán vé) → ARCHIVED (ngừng chiếu)
    private int version;

    /** Average customer rating (1.0–5.0, 1 decimal). Null = chưa tổng hợp. */
    private Double averageRating;
    /** Số review của khách hàng. Null = chưa tổng hợp. */
    private Long reviewCount;

    public Movie() {
        this.status = STATUS_DRAFT;
        this.version = 0;
    }

    // -----------------------------------------------------------------------
    // Accessors — dùng để JSP/JS gọi qua record-style API (read-only fields).
    // -----------------------------------------------------------------------
    public Long id() { return id; }
    public String title() { return title; }
    public Integer durationMin() { return durationMin; }
    public String genre() { return genre; }
    public String rating() { return rating; }
    public LocalDate releaseDate() { return releaseDate; }
    public LocalDate endDate() { return endDate; }
    public String posterUrl() { return posterUrl; }
    public String description() { return description; }
    public String author() { return author; }
    public String status() { return status; }
    public int version() { return version; }
    public Double averageRating() { return averageRating; }
    public Long reviewCount() { return reviewCount; }

    // -----------------------------------------------------------------------
    // Mutators — DAO dùng để bind dữ liệu từ ResultSet vào record sau khi load.
    // -----------------------------------------------------------------------
    public void setId(Long id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }
    public void setGenre(String genre) { this.genre = genre; }
    public void setRating(String rating) { this.rating = rating; }
    public void setReleaseDate(LocalDate releaseDate) { this.releaseDate = releaseDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public void setPosterUrl(String posterUrl) { this.posterUrl = posterUrl; }
    public void setDescription(String description) { this.description = description; }
    public void setAuthor(String author) { this.author = author; }
    public void setStatus(String status) { this.status = status; }
    public void setVersion(int version) { this.version = version; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }
    public void setReviewCount(Long reviewCount) { this.reviewCount = reviewCount; }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    /** Phim đang trong khoảng ngày chiếu hiệu lực (Req 2.5). */
    public boolean isInEffectiveDateRange(LocalDate date) {
        return date != null && releaseDate != null && endDate != null
                && !date.isBefore(releaseDate) && !date.isAfter(endDate);
    }

    /** Branch Manager/Staff chỉ chọn được phim PUBLISHED và còn hiệu lực (Req 2.5). */
    public boolean isSelectableForScheduling(LocalDate date) {
        return isPublished() && isInEffectiveDateRange(date);
    }
}
