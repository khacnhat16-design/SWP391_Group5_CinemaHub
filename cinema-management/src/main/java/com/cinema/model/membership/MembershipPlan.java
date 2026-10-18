package com.cinema.model.membership;

import java.time.LocalDateTime;

/**
 * VIP membership plan — gói thành viên VIP.
 * Plan khai báo giá + thời hạn. Subscription tham chiếu plan này.
 */
public class MembershipPlan {
    public static final String DURATION_6_MONTHS = "6_MONTHS";
    public static final String DURATION_1_YEAR = "1_YEAR";
    public static final String DURATION_LIFETIME = "LIFETIME";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    private long id;
    private String code;
    private String name;
    private String description;
    private long price;
    private String duration;
    private Integer durationDays;
    private String status;
    private int sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public long id() { return id; }
    public void setId(long id) { this.id = id; }
    public String code() { return code; }
    public void setCode(String code) { this.code = code; }
    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public String description() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long price() { return price; }
    public void setPrice(long price) { this.price = price; }
    public String duration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    public Integer durationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int sortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
