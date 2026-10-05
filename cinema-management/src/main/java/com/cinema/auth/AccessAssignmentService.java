package com.cinema.auth;

import com.cinema.common.ServiceException;

/** Service for role and branch scope assignment. */
public class AccessAssignmentService {
    /** Req 15.3/13.4 — audit mọi thay đổi phạm vi chi nhánh của nhân viên. */
    private final com.cinema.audit.AuditService audit = new com.cinema.audit.AuditService();
    private final UserDAO userDao;
    private final StaffBranchAssignmentDAO assignmentDao;
    private BranchDAO branchDao;
    private RoleDAO roleDao;

    public AccessAssignmentService(UserDAO userDAO, StaffBranchAssignmentDAO assignmentDAO) {
        this.userDao = userDAO;
        this.assignmentDao = assignmentDAO;
        // BranchDAO/RoleDAO optional — nếu chưa init được (DB down lúc boot)
        // thì lazy-init trong assignBranch() để tránh văng app lúc khởi động.
        try {
            this.branchDao = new BranchDAO();
            this.roleDao = new RoleDAO();
        } catch (Exception e) {
            // BranchDAO/RoleDAO sẽ được khởi tạo muộn khi cần dùng.
        }
    }

    /**
     * Assign a branch to a staff member.
     * Validates user, branch, and role; prevents duplicate ACTIVE assignments.
     */
    public StaffBranchAssignment assignBranch(Long staffUserId, Long branchId, Long adminUserId) throws Exception {
        User staffUser = userDao.findById(staffUserId)
            .orElseThrow(() -> new ServiceException.NotFound("Người dùng không tồn tại"));

        // Chỉ Manager/Staff cần scope chi nhánh; Admin xem toàn hệ thống,
        // Customer thì không gán vào staff_branch_assignment được.
        if (staffUser.role != Role.BRANCH_MANAGER && staffUser.role != Role.BRANCH_STAFF) {
            throw new ServiceException.Validation(
                "Chỉ có thể gán chi nhánh cho Branch Manager hoặc Branch Staff");
        }

        User admin = userDao.findById(adminUserId)
            .orElseThrow(() -> new ServiceException.NotFound("Admin không tồn tại"));

        if (admin.role != Role.ADMIN) {
            throw new ServiceException.Forbidden("Chỉ Admin mới có thể gán chi nhánh");
        }

        if (branchDao == null) {
            // Fallback lazy-init khi constructor gặp lỗi (vd. thiếu DB config).
            branchDao = new BranchDAO();
        }
        if (!branchDao.exists(branchId)) {
            throw new ServiceException.NotFound("Chi nhánh không tồn tại");
        }

        // DB có UNIQUE(partial) cho assignment ACTIVE nhưng check trước ở service
        // để trả 409 rõ ràng thay vì 500 do SQL exception.
        if (assignmentDao.hasActiveAssignment(staffUserId, branchId)) {
            throw new ServiceException.Conflict(
                "Người dùng đã được gán chi nhánh này");
        }

        StaffBranchAssignment assignment = new StaffBranchAssignment(
            staffUserId, branchId, adminUserId);
        assignmentDao.insert(assignment);

        // Req 13.4/15.3 — thay đổi scope có hiệu lực ở request kế tiếp, ghi audit
        audit.recordSafely(adminUserId, "ASSIGN_BRANCH", "STAFF_ASSIGNMENT", assignment.id(),
            null, "{\"staffUserId\":" + staffUserId + ",\"branchId\":" + branchId
                + ",\"status\":\"ACTIVE\"}",
            com.cinema.audit.AuditService.SUCCESS);

        return assignment;
    }

    /**
     * End a staff assignment to a branch.
     */
    public void endAssignment(Long assignmentId, Long adminUserId) throws Exception {
        User admin = userDao.findById(adminUserId)
            .orElseThrow(() -> new ServiceException.NotFound("Admin không tồn tại"));

        if (admin.role != Role.ADMIN) {
            throw new ServiceException.Forbidden("Chỉ Admin mới có thể kết thúc assignment");
        }

        // DAO endAssignment() đã set status=ENDED + ended_at=NOW() trong cùng
        // transaction; service chỉ audit thêm diff cũ → mới.
        assignmentDao.endAssignment(assignmentId);

        audit.recordSafely(adminUserId, "END_ASSIGNMENT", "STAFF_ASSIGNMENT", assignmentId,
            "{\"status\":\"ACTIVE\"}", "{\"status\":\"ENDED\"}",
            com.cinema.audit.AuditService.SUCCESS);
    }
}
