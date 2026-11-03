package com.cinema.model.complaint;

import java.time.LocalDateTime;

/** Complaint entity (RSC-4). */
public class Complaint {

    public static final String CATEGORY_STAFF = "STAFF";
    public static final String CATEGORY_FACILITY = "FACILITY";
    public static final String CATEGORY_PAYMENT = "PAYMENT";
    public static final String CATEGORY_OTHER = "OTHER";

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final java.util.Set<String> VALID_CATEGORIES =
            java.util.Set.of(CATEGORY_STAFF, CATEGORY_FACILITY, CATEGORY_PAYMENT, CATEGORY_OTHER);
    public static final java.util.Set<String> VALID_STATUSES =
            java.util.Set.of(STATUS_NEW, STATUS_IN_PROGRESS, STATUS_RESOLVED, STATUS_REJECTED);

    private Long id;
    private Long userId;
    private Long branchId;
    private String branchName;
    private String userFullName;
    private String userEmail;
    private String category;
    private String subject;
    private String message;
    private Long relatedTicketId;
    private String status;
    private String managerReply;
    private Long repliedBy;
    private LocalDateTime repliedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Complaint() {}

    public Long id() { return id; }
    public Long userId() { return userId; }
    public Long branchId() { return branchId; }
    public String branchName() { return branchName; }
    public String userFullName() { return userFullName; }
    public String userEmail() { return userEmail; }
    public String category() { return category; }
    public String subject() { return subject; }
    public String message() { return message; }
    public Long relatedTicketId() { return relatedTicketId; }
    public String status() { return status; }
    public String managerReply() { return managerReply; }
    public Long repliedBy() { return repliedBy; }
    public LocalDateTime repliedAt() { return repliedAt; }
    public LocalDateTime resolvedAt() { return resolvedAt; }
    public LocalDateTime createdAt() { return createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setBranchId(Long branchId) { this.branchId = branchId; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public void setUserFullName(String userFullName) { this.userFullName = userFullName; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public void setCategory(String category) { this.category = category; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setMessage(String message) { this.message = message; }
    public void setRelatedTicketId(Long relatedTicketId) { this.relatedTicketId = relatedTicketId; }
    public void setStatus(String status) { this.status = status; }
    public void setManagerReply(String managerReply) { this.managerReply = managerReply; }
    public void setRepliedBy(Long repliedBy) { this.repliedBy = repliedBy; }
    public void setRepliedAt(LocalDateTime repliedAt) { this.repliedAt = repliedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
