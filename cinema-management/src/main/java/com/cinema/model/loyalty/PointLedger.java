package com.cinema.model.loyalty;

import java.time.LocalDateTime;

/** Bản ghi giao dịch điểm cộng/trừ (Req 17.5 — lịch sử hiển thị cho khách). */
public class PointLedger {
    private Long id;
    private long userId;
    private int delta;
    private int balanceAfter;
    private String refType;
    private Long refId;
    private Long staffId;
    private Long branchId;
    private LocalDateTime createdAt;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public long userId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public int delta() { return delta; }
    public void setDelta(int delta) { this.delta = delta; }
    public int balanceAfter() { return balanceAfter; }
    public void setBalanceAfter(int balanceAfter) { this.balanceAfter = balanceAfter; }
    public String refType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public Long refId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public Long staffId() { return staffId; }
    public void setStaffId(Long staffId) { this.staffId = staffId; }
    public Long branchId() { return branchId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
