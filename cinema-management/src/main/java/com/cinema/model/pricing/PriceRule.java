package com.cinema.model.pricing;

import java.time.LocalDate;

/**
 * Entity khung giÃ¡ vÃ© (Req 5.1, 7 â€” dynamic pricing theo Movie / Branch / Showtime / TicketType).
 *
 * <p>Má»™t {@code PriceRule} cÃ³ thá»ƒ thuá»™c má»™t trong cÃ¡c scope (Æ°u tiÃªn giáº£m dáº§n):
 * <ul>
 *   <li>{@link #SHOWTIME_SCOPE} â€” {@code showtimeId != null} (showtime-specific).</li>
 *   <li>{@link #MOVIE_BRANCH_SCOPE} â€” {@code movieId != null} and {@code branchId != null}.</li>
 *   <li>{@link #MOVIE_SCOPE} â€” {@code movieId != null}, showtime/branch null (Movie + Time-based).</li>
 *   <li>{@link #BRANCH_SCOPE} â€” {@code branchId != null}, movie/showtime null (Branch default).</li>
 *   <li>{@link #SYSTEM_SCOPE} â€” cáº£ ba null (System default).</li>
 * </ul>
 *
 * <p>Khi cÃ³ nhiá»u rule khá»›p, {@link PricingService} Ä‘i tá»« trÃªn xuá»‘ng dÆ°á»›i Ä‘á»ƒ chá»n giÃ¡
 * (Req 7.4 â€” Æ°u tiÃªn Showtime â†’ Movie+Time â†’ Branch â†’ System).</p>
 */
public class PriceRule {
    private Long id;
    private String seatType;
    private String dayType;
    private String timeSlot;
    private long price;

    /** Override theo Movie / Branch / Showtime / TicketType â€” Ä‘á»u nullable (Req 7). */
    private Long movieId;
    private Long branchId;
    private Long showtimeId;
    /** ADULT / CHILD / STUDENT / VIP â€” null = Ã¡p dá»¥ng má»i ticketType (Req 9). */
    private String ticketType;
    private LocalDate activeFrom;
    private LocalDate activeTo;

    public static final String SCOPE_SHOWTIME = "SHOWTIME";
    public static final String SCOPE_MOVIE_BRANCH = "MOVIE_BRANCH";
    public static final String SCOPE_MOVIE = "MOVIE";
    public static final String SCOPE_BRANCH = "BRANCH";
    public static final String SCOPE_SYSTEM = "SYSTEM";

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String seatType() { return seatType; }
    public void setSeatType(String seatType) { this.seatType = seatType; }
    public String dayType() { return dayType; }
    public void setDayType(String dayType) { this.dayType = dayType; }
    public String timeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }
    public long price() { return price; }
    public void setPrice(long price) { this.price = price; }
    public Long movieId() { return movieId; }
    public void setMovieId(Long movieId) { this.movieId = movieId; }
    public Long branchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public Long showtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }
    public String ticketType() { return ticketType; }
    public void setTicketType(String ticketType) { this.ticketType = ticketType; }
    public LocalDate activeFrom() { return activeFrom; }
    public void setActiveFrom(LocalDate activeFrom) { this.activeFrom = activeFrom; }
    public LocalDate activeTo() { return activeTo; }
    public void setActiveTo(LocalDate activeTo) { this.activeTo = activeTo; }

    /** PhÃ¢n cáº¥p scope Ä‘á»ƒ PricingService Ã¡p dá»¥ng Ä‘Ãºng thá»© tá»± Æ°u tiÃªn (Req 7.4). */
    public String scope() {
        if (showtimeId != null) return SCOPE_SHOWTIME;
        if (movieId != null && branchId != null) return SCOPE_MOVIE_BRANCH;
        if (movieId != null) return SCOPE_MOVIE;
        if (branchId != null) return SCOPE_BRANCH;
        return SCOPE_SYSTEM;
    }
}
