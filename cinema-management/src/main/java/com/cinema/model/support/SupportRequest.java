package com.cinema.model.support;

import java.time.LocalDateTime;

/** Support request entity (RSC-3). */
public class SupportRequest {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_ANSWERED = "ANSWERED";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;
    private Long userId;
    private String userFullName;
    private String userEmail;
    private String subject;
    private String message;
    private String status;
    private String managerReply;
    private Long repliedBy;
    private LocalDateTime repliedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public SupportRequest() {}

    public Long id() { return id; }
    public Long userId() { return userId; }
    public String userFullName() { return userFullName; }
    public String userEmail() { return userEmail; }
    public String subject() { return subject; }
    public String message() { return message; }
    public String status() { return status; }
    public String managerReply() { return managerReply; }
    public Long repliedBy() { return repliedBy; }
    public LocalDateTime repliedAt() { return repliedAt; }
    public LocalDateTime createdAt() { return createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setUserFullName(String userFullName) { this.userFullName = userFullName; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setMessage(String message) { this.message = message; }
    public void setStatus(String status) { this.status = status; }
    public void setManagerReply(String managerReply) { this.managerReply = managerReply; }
    public void setRepliedBy(Long repliedBy) { this.repliedBy = repliedBy; }
    public void setRepliedAt(LocalDateTime repliedAt) { this.repliedAt = repliedAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
