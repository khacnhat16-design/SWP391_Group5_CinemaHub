package com.cinema.auth;

import java.time.LocalDateTime;

/** Staff branch assignment entity for branch-scoped access. */
public class StaffBranchAssignment {
    private Long id;
    private Long userId;
    private Long branchId;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String status; // ACTIVE, ENDED
    private Long assignedBy;

    public StaffBranchAssignment(Long userId, Long branchId, Long assignedBy) {
        this.userId = userId;
        this.branchId = branchId;
        this.assignedBy = assignedBy;
        this.status = "ACTIVE";
        this.effectiveFrom = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Accessors — DAO/JSP dùng để bind ResultSet / hiển thị.
    // -----------------------------------------------------------------------
    public Long id() { return id; }
    public Long userId() { return userId; }
    public Long branchId() { return branchId; }
    public LocalDateTime effectiveFrom() { return effectiveFrom; }
    public LocalDateTime effectiveTo() { return effectiveTo; }
    public String status() { return status; }
    public Long assignedBy() { return assignedBy; }

    // -----------------------------------------------------------------------
    // Mutators — DAO gọi khi load row; controller không gọi trực tiếp.
    // -----------------------------------------------------------------------
    public void setId(Long id) { this.id = id; }
    public void setStatus(String status) { this.status = status; }
    public void setEffectiveTo(LocalDateTime to) { this.effectiveTo = to; }
    public void setEffectiveFrom(LocalDateTime from) { this.effectiveFrom = from; }
}
