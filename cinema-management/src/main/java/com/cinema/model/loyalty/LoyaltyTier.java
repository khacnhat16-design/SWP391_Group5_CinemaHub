package com.cinema.model.loyalty;

import java.time.LocalDateTime;

/** Cấu hình một hạng thành viên (Req 17.1): ngưỡng điểm tăng dần + quyền lợi giảm giá. */
public class LoyaltyTier {
    private Long id;
    private String tierName;
    private int minPoints;
    private int discountPercent;
    /** Tỷ lệ điểm trên mỗi đồng chi tiêu (schema: point_rate DECIMAL). */
    private double pointRate;
    /** Tỷ lệ đổi điểm: số VND được giảm cho mỗi điểm (Phase 3). */
    private long redemptionRate;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String tierName() { return tierName; }
    public void setTierName(String tierName) { this.tierName = tierName; }
    public int minPoints() { return minPoints; }
    public void setMinPoints(int minPoints) { this.minPoints = minPoints; }
    public int discountPercent() { return discountPercent; }
    public void setDiscountPercent(int discountPercent) { this.discountPercent = discountPercent; }
    public double pointRate() { return pointRate; }
    public void setPointRate(double pointRate) { this.pointRate = pointRate; }
    public long redemptionRate() { return redemptionRate; }
    public void setRedemptionRate(long redemptionRate) { this.redemptionRate = redemptionRate; }
}
