package com.cinema.booking;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity vé (Req 7, 8, 11, 12).
 * Chức năng: Quản lý vòng đời vé và lưu snapshot giá ghế (PENDING -> CONFIRMED -> USED / CANCELLED).
 */
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

    // Danh sách ghế kèm giá snapshot (ticket_seat)
    private List<TicketSeat> seats = new ArrayList<>();

    public Long id() { return id; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String ticketCode() { return ticketCode; }
    public String getTicketCode() { return ticketCode; }
    public void setTicketCode(String ticketCode) { this.ticketCode = ticketCode; }

    public long showtimeId() { return showtimeId; }
    public long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(long showtimeId) { this.showtimeId = showtimeId; }

    public long branchId() { return branchId; }
    public long getBranchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }

    public Long userId() { return userId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String status() { return status; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long totalAmount() { return totalAmount; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }

    public String voucherCode() { return voucherCode; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }

    public Long refundAmount() { return refundAmount; }
    public Long getRefundAmount() { return refundAmount; }
    public void setRefundAmount(Long refundAmount) { this.refundAmount = refundAmount; }

    public Long holdId() { return holdId; }
    public Long getHoldId() { return holdId; }
    public void setHoldId(Long holdId) { this.holdId = holdId; }

    public LocalDateTime createdAt() { return createdAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime confirmedAt() { return confirmedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }

    public LocalDateTime cancelledAt() { return cancelledAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    public LocalDateTime usedAt() { return usedAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }

    public Long usedBy() { return usedBy; }
    public Long getUsedBy() { return usedBy; }
    public void setUsedBy(Long usedBy) { this.usedBy = usedBy; }

    public int pointsEarned() { return pointsEarned; }
    public int getPointsEarned() { return pointsEarned; }
    public void setPointsEarned(int pointsEarned) { this.pointsEarned = pointsEarned; }

    public int version() { return version; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public List<TicketSeat> seats() { return seats; }
    public List<TicketSeat> getSeats() { return seats; }
    public void setSeats(List<TicketSeat> seats) { this.seats = seats; }

    /**
     * Ghế + Giá snapshot lưu tại thời điểm bán (Bảo toàn lịch sử giá vé, không bị ảnh hưởng khi bảng giá thay đổi).
     */
    public record TicketSeat(long seatId, String rowLabel, int colNo, String seatType,
                             String ticketType, long price) {
        public TicketSeat(long seatId, String rowLabel, int colNo, String seatType, long price) {
            this(seatId, rowLabel, colNo, seatType, null, price);
        }
    }
}