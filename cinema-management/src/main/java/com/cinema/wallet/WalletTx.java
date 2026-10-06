package com.cinema.wallet;

import java.time.LocalDateTime;

/** Bản ghi giao dịch ví (Req 18.7 — lịch sử hiển thị số dư sau mỗi giao dịch). */
public class WalletTx {
    public static final String TYPE_TOPUP = "TOPUP";
    public static final String TYPE_SPEND = "SPEND";
    public static final String TYPE_REFUND = "REFUND";

    private Long id;
    private long userId;
    private String type;
    private long amount;
    private long balanceAfter;
    private String refType;
    private Long refId;
    private String idempotencyKey;
    private LocalDateTime createdAt;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public long userId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String type() { return type; }
    public void setType(String type) { this.type = type; }
    public long amount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public long balanceAfter() { return balanceAfter; }
    public void setBalanceAfter(long balanceAfter) { this.balanceAfter = balanceAfter; }
    public String refType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public Long refId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public String idempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
