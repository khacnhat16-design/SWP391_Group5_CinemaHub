package com.cinema.model.voucher;

import java.time.LocalDateTime;

/**
 * Voucher don gian, de hoc.
 * Chi 2 loai: PERCENT (10 = giam 10%) va FIXED (50000 = giam 50k).
 */
public class Voucher {
    private Long id;
    private String code;          // vd: WELCOME10 (viet HOA, khong dau cach)
    private String discountType;  // PERCENT | FIXED
    private long discountValue;   // 10 = 10% | 50000 = 50k
    private long minOrderAmount;  // don toi thieu moi duoc ap dung (0 = khong gioi han)
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private int maxUses;          // tong luot dung toi da
    private int usedCount;        // da dung bao nhieu luot
    private String status;        // ACTIVE | INACTIVE

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String code() { return code; }
    public void setCode(String code) { this.code = code == null ? null : code.trim().toUpperCase(); }
    public String discountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }
    public long discountValue() { return discountValue; }
    public void setDiscountValue(long discountValue) { this.discountValue = discountValue; }
    public long minOrderAmount() { return minOrderAmount; }
    public void setMinOrderAmount(long minOrderAmount) { this.minOrderAmount = minOrderAmount; }
    public LocalDateTime validFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }
    public LocalDateTime validTo() { return validTo; }
    public void setValidTo(LocalDateTime validTo) { this.validTo = validTo; }
    public int maxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int usedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isActive() { return "ACTIVE".equals(status); }

    /** Con han su dung khong? */
    public boolean isInDate(LocalDateTime now) {
        if (validFrom != null && now.isBefore(validFrom)) return false;
        if (validTo != null && now.isAfter(validTo)) return false;
        return true;
    }

    /** Con luot dung khong? */
    public boolean hasQuota() { return usedCount < maxUses; }

    /**
     * Tinh so tien duoc giam tren tong don.
     * Giam khong vuot qua tong don.
     */
    public long discountFor(long orderTotal) {
        if (orderTotal < minOrderAmount) return 0;
        long d = "PERCENT".equals(discountType)
                ? orderTotal * discountValue / 100
                : discountValue;
        if (d < 0) d = 0;
        if (d > orderTotal) d = orderTotal;
        return d;
    }
}
