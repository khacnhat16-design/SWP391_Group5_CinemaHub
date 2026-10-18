package com.cinema.service.auth;

import com.cinema.dao.auth.BranchDAO;
import com.cinema.dao.auth.RoleDAO;
import com.cinema.dao.auth.StaffBranchAssignmentDAO;
import com.cinema.dao.auth.UserDAO;
import com.cinema.model.auth.AccessScope;
import com.cinema.model.auth.Role;
import com.cinema.model.auth.StaffBranchAssignment;
import com.cinema.model.auth.User;
import com.cinema.service.audit.AuditService;
import com.cinema.service.audit.AuditService;

import com.cinema.common.ServiceException;

/** Service for role and branch scope assignment. */
public class AccessAssignmentService {
    /** Req 15.3/13.4 — audit mọi thay đổi phạm vi chi nhánh của nhân viên. */
    private final com.cinema.service.audit.AuditService audit = new com.cinema.service.audit.AuditService();
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
     *
     * @deprecated Use {@link #assignBranchWithScope(Long, Long, Long, AccessScope)} — old
     *     method assumes actor is Admin. Kept for backward compat với admin-only code path.
     */
    @Deprecated
    public StaffBranchAssignment assignBranch(Long staffUserId, Long branchId, Long adminUserId) throws Exception {
        User admin = userDao.findById(adminUserId)
            .orElseThrow(() -> new ServiceException.NotFound("Admin không tồn tại"));
        return assignBranchWithScope(staffUserId, branchId, adminUserId,
                AccessScope.forRole(Role.ADMIN, java.util.Set.of()));
    }

    /**
     * Assign branch theo scope — Admin hoặc BRM cùng chi nhánh.
     * - Admin: gán được BRM + STAFF cho bất kỳ chi nhánh.
     * - BRM: chỉ gán STAFF (không được gán BRM khác vào chi nhánh của mình).
     */
    public StaffBranchAssignment assignBranchWithScope(Long staffUserId, Long branchId,
                                                      Long actorUserId, AccessScope scope) throws Exception {
        if (!scope.includesBranch(branchId)) {
            throw new ServiceException.Forbidden("Không thuộc chi nhánh " + branchId);
        }
        User staffUser = userDao.findById(staffUserId)
            .orElseThrow(() -> new ServiceException.NotFound("Người dùng không tồn tại"));
        if (staffUser.role != Role.BRANCH_MANAGER && staffUser.role != Role.BRANCH_STAFF) {
            throw new ServiceException.Validation(
                "Chỉ có thể gán chi nhánh cho Branch Manager hoặc Branch Staff");
        }
        // BRM không được gán BRM khác
        if (scope.role() == Role.BRANCH_MANAGER && staffUser.role == Role.BRANCH_MANAGER) {
            throw new ServiceException.Forbidden(
                "Bạn không thể thay đổi Branch Manager. Liên hệ Admin.");
        }
        if (branchDao == null) {
            branchDao = new BranchDAO();
        }
        if (!branchDao.exists(branchId)) {
            throw new ServiceException.NotFound("Chi nhánh không tồn tại");
        }
        if (assignmentDao.hasActiveAssignment(staffUserId, branchId)) {
            throw new ServiceException.Conflict("Người dùng đã được gán chi nhánh này");
        }
        StaffBranchAssignment assignment = new StaffBranchAssignment(
            staffUserId, branchId, actorUserId);
        assignmentDao.insert(assignment);
        audit.recordSafely(actorUserId, "ASSIGN_BRANCH", "STAFF_ASSIGNMENT", assignment.id(),
            null, "{\"staffUserId\":" + staffUserId + ",\"branchId\":" + branchId
                + ",\"status\":\"ACTIVE\"}",
            com.cinema.service.audit.AuditService.SUCCESS);
        return assignment;
    }

    /**
     * End a staff assignment to a branch.
     *
     * @deprecated Use {@link #endAssignmentWithScope} for scope-aware checks.
     */
    @Deprecated
    public void endAssignment(Long assignmentId, Long adminUserId) throws Exception {
        User admin = userDao.findById(adminUserId)
            .orElseThrow(() -> new ServiceException.NotFound("Admin không tồn tại"));
        endAssignmentWithScope(assignmentId, adminUserId,
                AccessScope.forRole(Role.ADMIN, java.util.Set.of()));
    }

    /**
     * End assignment theo scope — Admin hoặc BRM cùng chi nhánh.
     * BRM không được kết thúc assignment BRM (chỉ Admin).
     */
    public void endAssignmentWithScope(Long assignmentId, Long actorUserId, AccessScope scope)
            throws Exception {
        // Tìm assignment để check branch + role.
        var ctxOpt = assignmentDao.findAssignmentContext(assignmentId);
        if (ctxOpt.isEmpty()) {
            throw new ServiceException.NotFound("Phân công không tồn tại");
        }
        var ctx = ctxOpt.get();
        long branchId = ((Number) ctx.get("branchId")).longValue();
        String roleCode = (String) ctx.get("roleCode");
        if (!scope.includesBranch(branchId)) {
            throw new ServiceException.Forbidden(
                    "Bạn chỉ có thể thao tác trên chi nhánh của mình");
        }
        if (scope.role() == Role.BRANCH_MANAGER && "BRANCH_MANAGER".equals(roleCode)) {
            throw new ServiceException.Forbidden(
                    "Bạn không thể bỏ gán Branch Manager. Liên hệ Admin.");
        }
        assignmentDao.endAssignment(assignmentId);
        audit.recordSafely(actorUserId, "END_ASSIGNMENT", "STAFF_ASSIGNMENT", assignmentId,
            "{\"status\":\"ACTIVE\"}", "{\"status\":\"ENDED\"}",
            com.cinema.service.audit.AuditService.SUCCESS);
    }
}
