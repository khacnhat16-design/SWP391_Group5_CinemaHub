package com.cinema.booking;

import java.time.LocalDateTime;

/** Entity vé (Req 7, 8, 11, 12). Trạng thái: PENDING → CONFIRMED → USED/CANCELLED. */
public class Ticket {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_USED = "USED";

    private Long id;
    private String ticketCode;
    private long showtimeId;
    private long branchId;
    private Long userId;
    private String status;
    private long totalAmount;
    private String voucherCode;
    private Long refundAmount;
    private Long holdId;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime usedAt;
    private Long usedBy;
    private int pointsEarned;
    private int version;

    // Ghế kèm giá snapshot (ticket_seat)
    private java.util.List<TicketSeat> seats = new java.util.ArrayList<>();

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String ticketCode() { return ticketCode; }
    public void setTicketCode(String ticketCode) { this.ticketCode = ticketCode; }
    public long showtimeId() { return showtimeId; }
    public void setShowtimeId(long showtimeId) { this.showtimeId = showtimeId; }
    public long branchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }
    public Long userId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long totalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public String voucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
    public Long refundAmount() { return refundAmount; }
    public void setRefundAmount(Long refundAmount) { this.refundAmount = refundAmount; }
    public Long holdId() { return holdId; }
    public void setHoldId(Long holdId) { this.holdId = holdId; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime confirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public LocalDateTime cancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public LocalDateTime usedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
    public Long usedBy() { return usedBy; }
    public void setUsedBy(Long usedBy) { this.usedBy = usedBy; }
    public int pointsEarned() { return pointsEarned; }
    public void setPointsEarned(int pointsEarned) { this.pointsEarned = pointsEarned; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }
    public java.util.List<TicketSeat> seats() { return seats; }
    public void setSeats(java.util.List<TicketSeat> seats) { this.seats = seats; }

    /** Ghế + giá snapshot lưu tại thời điểm bán (Req 5.4 — không hồi tố).
     *  {@code ticketType} = ADULT/CHILD/STUDENT/VIP (Req 9) hoặc null. */
    public record TicketSeat(long seatId, String rowLabel, int colNo, String seatType,
                             String ticketType, long price) {
        /** Compat với code cũ (chưa phân biệt ticket_type). */
        public TicketSeat(long seatId, String rowLabel, int colNo, String seatType, long price) {
            this(seatId, rowLabel, colNo, seatType, null, price);
        }
    }
}
