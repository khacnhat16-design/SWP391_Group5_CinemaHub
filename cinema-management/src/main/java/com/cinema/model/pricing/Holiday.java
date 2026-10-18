package com.cinema.model.pricing;

import java.time.LocalDateTime;

/**
 * Entity holiday â€” template ngÃ y lá»… láº·p láº¡i háº±ng nÄƒm (theo MM-DD).
 * DÃ¹ng Ä‘á»ƒ sinh voucher hÃ ng loáº¡t cho tá»«ng nÄƒm (xem HolidayService.generateVouchers).
 *
 * <p>KhÃ¡c Voucher: holiday KHÃ”NG cÃ³ branch/movie scope/valid window cá»¥ thá»ƒ.
 * CÃ¡c trÆ°á»ng Ä‘Ã³ sáº½ Ä‘Æ°á»£c tÃ­nh lÃºc generate voucher.
 */
public class Holiday {
    public static final String TYPE_PERCENT = "PERCENT";
    public static final String TYPE_FIXED = "FIXED";

    private Long id;
    private String name;
    private String monthDay;        // "MM-DD", vÃ­ dá»¥ "04-30"
    private String discountType;
    private long discountValue;
    private long minOrderAmount;
    private int maxUses;
    private boolean refundable;
    private int validDays;
    private boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public String monthDay() { return monthDay; }
    public void setMonthDay(String monthDay) { this.monthDay = monthDay; }
    public String discountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }
    public long discountValue() { return discountValue; }
    public void setDiscountValue(long discountValue) { this.discountValue = discountValue; }
    public long minOrderAmount() { return minOrderAmount; }
    public void setMinOrderAmount(long minOrderAmount) { this.minOrderAmount = minOrderAmount; }
    public int maxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public boolean refundable() { return refundable; }
    public void setRefundable(boolean refundable) { this.refundable = refundable; }
    public int validDays() { return validDays; }
    public void setValidDays(int validDays) { this.validDays = validDays; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long createdBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
