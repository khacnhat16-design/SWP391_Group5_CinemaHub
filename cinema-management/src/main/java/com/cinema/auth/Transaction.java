package com.cinema.auth;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction represents a purchase (ticket booking or F&B order).
 * Used for customer purchase history display.
 */
public class Transaction {
    private Long id;
    private Long customerId;
    private String type; // TICKET_BOOKING, F&B_ORDER
    private BigDecimal amount;
    private String status; // CONFIRMED, USED, CANCELLED, etc.
    private Integer pointsEarned;
    private LocalDateTime createdAt;
    private String description; // Movie/Seats or F&B items

    public Transaction() {}

    public Transaction(Long customerId, String type, BigDecimal amount, String status,
                       Integer pointsEarned, LocalDateTime createdAt, String description) {
        this.customerId = customerId;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.pointsEarned = pointsEarned;
        this.createdAt = createdAt;
        this.description = description;
    }

    // -----------------------------------------------------------------------
    // Accessors — DAO/JSP dùng để bind ResultSet / hiển thị.
    // -----------------------------------------------------------------------
    public Long id() { return id; }
    public Long customerId() { return customerId; }
    public String type() { return type; }
    public BigDecimal amount() { return amount; }
    public String status() { return status; }
    public Integer pointsEarned() { return pointsEarned; }
    public LocalDateTime createdAt() { return createdAt; }
    public String description() { return description; }

    // -----------------------------------------------------------------------
    // Mutators — DAO gọi khi load row.
    // -----------------------------------------------------------------------
    public void setId(Long id) { this.id = id; }
}
