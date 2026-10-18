package com.cinema.model.auth;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction represents a ticket purchase.
 * Used for customer purchase history display.
 */
public class Transaction {
    private Long id;
    private Long customerId;
    private String type;
    private BigDecimal amount;
    private String status;
    private LocalDateTime createdAt;
    private String description;

    public Transaction() {}

    public Transaction(Long id, Long customerId, String type, BigDecimal amount, String status,
                       LocalDateTime createdAt, String description) {
        this.id = id;
        this.customerId = customerId;
        this.type = type;
        this.amount = amount;
        this.status = status;
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
    public LocalDateTime createdAt() { return createdAt; }
    public String description() { return description; }

    // -----------------------------------------------------------------------
    // Mutators — DAO gọi khi load row.
    // -----------------------------------------------------------------------
    public void setId(Long id) { this.id = id; }
}
