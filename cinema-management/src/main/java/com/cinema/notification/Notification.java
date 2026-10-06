package com.cinema.notification;

import java.time.LocalDateTime;

/** Entity thông báo trong hệ thống (Req 23). */
public class Notification {
    /** Loại thông báo giao dịch — vẫn gửi khi tài khoản LOCKED (Req 23.6). */
    public static final String TYPE_TICKET_CONFIRMED = "TICKET_CONFIRMED";
    public static final String TYPE_TICKET_CANCELLED = "TICKET_CANCELLED";
    public static final String TYPE_REFUND = "REFUND";
    /** Loại nhắc suất chiếu (Req 23.3). */
    public static final String TYPE_SHOWTIME_REMINDER = "SHOWTIME_REMINDER";
    public static final String TYPE_SHOWTIME_SCHEDULE_REMINDER = "SHOWTIME_SCHEDULE_REMINDER";
    public static final String TYPE_SHOWTIME_SCHEDULE_URGENT = "SHOWTIME_SCHEDULE_URGENT";
    public static final String TYPE_SHOWTIME_ALLOCATION_UPDATED = "SHOWTIME_ALLOCATION_UPDATED";
    /** Loại tiếp thị — ngừng gửi khi tài khoản LOCKED (Req 23.6). */
    public static final String TYPE_MARKETING = "MARKETING";

    /** Loại thông báo F&B (Req 21.x). */
    public static final String TYPE_FNB_CONFIRMED = "FNB_CONFIRMED";
    public static final String TYPE_FNB_READY = "FNB_READY";
    public static final String TYPE_FNB_CANCELLED = "FNB_CANCELLED";
    public static final String TYPE_FNB_REFUNDED = "FNB_REFUNDED";

    private Long id;
    private long userId;
    private String type;
    private String title;
    private String body;
    private boolean read;
    private LocalDateTime createdAt;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public long userId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String type() { return type; }
    public void setType(String type) { this.type = type; }
    public String title() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String body() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean read() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public LocalDateTime createdAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Thông báo giao dịch (confirmed/cancel/refund/reminder) — luôn gửi kể cả tài khoản
     * LOCKED vì liên quan hoàn tiền đang chờ xử lý (Req 23.6).
     */
    public boolean isTransactional() {
        return !TYPE_MARKETING.equals(type);
    }
}
