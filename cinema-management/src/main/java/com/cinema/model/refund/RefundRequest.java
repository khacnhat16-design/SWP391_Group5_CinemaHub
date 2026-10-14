package com.cinema.model.refund;

import java.time.LocalDateTime;

/** Customer request to refund a confirmed ticket to a bank account. */
public class RefundRequest {
    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    private long id;
    private long ticketId;
    private long userId;
    private long branchId;
    private String customerFullName;
    private String customerPhone;
    private String customerEmail;
    private String bankName;
    private String bankAccountName;
    private String bankAccountNumber;
    private String ticketCode;
    private long showtimeId;
    private LocalDateTime showtimeStart;
    private long ticketAmount;
    private Status status;
    private String reason;
    private Long managerId;
    private String managerNote;
    private LocalDateTime decidedAt;
    private LocalDateTime emailSentAt;
    private LocalDateTime createdAt;

    public long id() { return id; }
    public void setId(long id) { this.id = id; }
    public long ticketId() { return ticketId; }
    public void setTicketId(long ticketId) { this.ticketId = ticketId; }
    public long userId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public long branchId() { return branchId; }
    public void setBranchId(long branchId) { this.branchId = branchId; }
    public String customerFullName() { return customerFullName; }
    public void setCustomerFullName(String value) { this.customerFullName = value; }
    public String customerPhone() { return customerPhone; }
    public void setCustomerPhone(String value) { this.customerPhone = value; }
    public String customerEmail() { return customerEmail; }
    public void setCustomerEmail(String value) { this.customerEmail = value; }
    public String bankName() { return bankName; }
    public void setBankName(String value) { this.bankName = value; }
    public String bankAccountName() { return bankAccountName; }
    public void setBankAccountName(String value) { this.bankAccountName = value; }
    public String bankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String value) { this.bankAccountNumber = value; }
    public String ticketCode() { return ticketCode; }
    public void setTicketCode(String value) { this.ticketCode = value; }
    public long showtimeId() { return showtimeId; }
    public void setShowtimeId(long value) { this.showtimeId = value; }
    public LocalDateTime showtimeStart() { return showtimeStart; }
    public void setShowtimeStart(LocalDateTime value) { this.showtimeStart = value; }
    public long ticketAmount() { return ticketAmount; }
    public void setTicketAmount(long value) { this.ticketAmount = value; }
    public Status status() { return status; }
    public void setStatus(Status value) { this.status = value; }
    public String reason() { return reason; }
    public void setReason(String value) { this.reason = value; }
    public Long managerId() { return managerId; }
    public void setManagerId(Long value) { this.managerId = value; }
    public String managerNote() { return managerNote; }
    public void setManagerNote(String value) { this.managerNote = value; }
    public LocalDateTime decidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime value) { this.decidedAt = value; }
    public LocalDateTime emailSentAt() { return emailSentAt; }
    public void setEmailSentAt(LocalDateTime value) { this.emailSentAt = value; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
}
