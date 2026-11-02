package com.cinema.model.concession;

import java.time.LocalDateTime;

/** Entity đơn bắp nước (Req 20 offline, Req 21 online). */
public class ConcessionOrder {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY_FOR_PICKUP = "READY_FOR_PICKUP";
    public static final String STATUS_FULFILLED = "FULFILLED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED_NO_SHOW = "EXPIRED_NO_SHOW";

    private Long id;
    private String orderCode;
    private long branchId;
    private Long ticketId;
    private Long showtimeId;
    private Long userId;
    private String status;
    private long totalAmount;
    /** Số điểm đã dùng để giảm giá (Phase 3). */
    private int pointsUsed;
    /** Số tiền được giảm từ điểm (Phase 3). */
    private long pointsDiscount;
    private String pickupCode;
    private LocalDateTime pickupDeadline;
    private LocalDateTime createdAt;
    private LocalDateTime fulfilledAt;
    private Long fulfilledByStaffId;
    private String cancelReason;
    private int version;
    private String branchName;
    private String customerName;

    private java.util.List<OrderLine> lines = new java.util.ArrayList<>();

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String orderCode() { return orderCode; }
    public void setOrderCode(String orderCode) { this.orderCode = orderCode; }
    public long branchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }
    public Long ticketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long showtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }
    public Long userId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long totalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    /** Số điểm đã dùng để giảm giá (Phase 3). */
    public int pointsUsed() { return pointsUsed; }
    public void setPointsUsed(int pointsUsed) { this.pointsUsed = pointsUsed; }
    /** Số tiền được giảm từ điểm (Phase 3). */
    public long pointsDiscount() { return pointsDiscount; }
    public void setPointsDiscount(long pointsDiscount) { this.pointsDiscount = pointsDiscount; }
    public String pickupCode() { return pickupCode; }
    public void setPickupCode(String pickupCode) { this.pickupCode = pickupCode; }
    public LocalDateTime pickupDeadline() { return pickupDeadline; }
    public void setPickupDeadline(LocalDateTime pickupDeadline) { this.pickupDeadline = pickupDeadline; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime fulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(LocalDateTime fulfilledAt) { this.fulfilledAt = fulfilledAt; }
    public Long fulfilledByStaffId() { return fulfilledByStaffId; }
    public void setFulfilledByStaffId(Long fulfilledByStaffId) { this.fulfilledByStaffId = fulfilledByStaffId; }
    public String cancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String branchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String customerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public java.util.List<OrderLine> lines() { return lines; }
    public void setLines(java.util.List<OrderLine> lines) { this.lines = lines; }

    /**
     * Tổng tiền thực trả sau khi trừ điểm.
     * = totalAmount - pointsDiscount
     */
    public long amountAfterPointsDiscount() {
        return Math.max(0, totalAmount - pointsDiscount);
    }

    /** Dòng đơn: sản phẩm + số lượng + giá snapshot lúc tạo đơn (Req 19.8). */
    public record OrderLine(long productId, String productName, int qty, long unitPrice) {
        public long lineTotal() { return qty * unitPrice; }
    }
}
