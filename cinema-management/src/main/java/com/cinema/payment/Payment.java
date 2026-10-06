package com.cinema.payment;

import java.time.LocalDateTime;

/** Entity thanh toán (Req 9) — 4 method, idempotency qua unique key. */
public class Payment {
    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_MOCK_GATEWAY = "MOCK_GATEWAY";
    public static final String METHOD_BANK_TRANSFER = "BANK_TRANSFER";
    public static final String METHOD_WALLET = "WALLET";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_REVIEW_REQUIRED = "REVIEW_REQUIRED";

    private Long id;
    private Long ticketId;
    private Long concessionOrderId;
    private String method;
    private long amount;
    private String status;
    private String idempotencyKey;
    private String providerReference;
    private String hmac;
    private LocalDateTime createdAt;
    private LocalDateTime confirmedAt;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long ticketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long concessionOrderId() { return concessionOrderId; }
    public void setConcessionOrderId(Long concessionOrderId) { this.concessionOrderId = concessionOrderId; }
    public String method() { return method; }
    public void setMethod(String method) { this.method = method; }
    public long amount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String idempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String providerReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public String hmac() { return hmac; }
    public void setHmac(String hmac) { this.hmac = hmac; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime confirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
}
