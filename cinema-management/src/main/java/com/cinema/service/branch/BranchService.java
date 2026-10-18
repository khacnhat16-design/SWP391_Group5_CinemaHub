package com.cinema.service.branch;

import com.cinema.common.SerializationUtil;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.model.branch.Branch;
import com.cinema.service.audit.AuditService;

import com.cinema.common.RequestValidator;
import com.cinema.common.ServiceException;
import com.cinema.common.ValidationErrors;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.dao.auth.StaffBranchAssignmentDAO;
import com.cinema.dao.auth.UserDAO;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.model.auth.Role;
import com.cinema.model.auth.StaffBranchAssignment;

import java.sql.Connection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.logging.Logger;

/** Service for branch management (Admin-only). */
public class BranchService {
    private static final Logger logger = Logger.getLogger(BranchService.class.getName());
    /** Req 15.3/11.2 — audit mọi thay đổi trạng thái branch. */
    private final com.cinema.service.audit.AuditService audit = new com.cinema.service.audit.AuditService();
    private final BranchDAO branchDao;
    private final ShowtimeDAO showtimeDao;
    private final UserDAO userDao;
    private final StaffBranchAssignmentDAO assignmentDao;

    public BranchService(BranchDAO branchDAO) {
        this(branchDAO, new ShowtimeDAO(), new UserDAO(), new StaffBranchAssignmentDAO());
    }

    public BranchService(BranchDAO branchDAO, ShowtimeDAO showtimeDAO) {
        this(branchDAO, showtimeDAO, new UserDAO(), new StaffBranchAssignmentDAO());
    }

    public BranchService(BranchDAO branchDAO, ShowtimeDAO showtimeDAO, UserDAO userDAO,
                         StaffBranchAssignmentDAO assignmentDAO) {
        this.branchDao = branchDAO;
        this.showtimeDao = showtimeDAO;
        this.userDao = userDAO;
        this.assignmentDao = assignmentDAO;
    }

    /**
     * Create a new branch with validation.
     * Normalizes name (whitespace trimming) and enforces case-insensitive uniqueness.
     */
    public Branch createBranch(String name, String address, String phone) throws Exception {
        return createBranch(name, address, phone, null, List.of(), null);
    }

    public Branch createBranch(String name, String address, String phone,
                               Long managerId, List<Long> staffIds, Long adminId) throws Exception {
        ValidationErrors errors = RequestValidator.builder()
            .required("name", name)
            .required("address", address)
            .required("phone", phone)
            .build();

        if (!errors.isEmpty()) {
            throw new ServiceException.Validation("Tất cả các trường đều bắt buộc");
        }

        // trim() trước khi kiểm tra — name toàn space sẽ bị reject vì isEmpty().
        String normalizedName = name.trim();
        if (normalizedName.isEmpty()) {
            throw new ServiceException.Validation("Tên chi nhánh không được rỗng");
        }
        if (adminId == null) {
            throw new ServiceException.Validation("Thiếu adminId trong session");
        }
        // Branch chỉ được hoạt động khi có BRM và ít nhất một Staff.
        boolean hasManager = managerId != null;
        boolean hasStaff = staffIds != null && !staffIds.isEmpty();
        if (!hasManager) {
            throw new ServiceException.Validation(
                    "Cần chọn ít nhất 1 Branch Manager để chi nhánh có thể hoạt động");
        }
        if (!hasStaff) {
            throw new ServiceException.Validation(
                    "Cần chọn ít nhất 1 Branch Staff để chi nhánh có thể hoạt động");
        }
        List<Long> distinctStaffIds = hasStaff
                ? List.copyOf(new LinkedHashSet<>(staffIds))
                : List.of();
        if (distinctStaffIds.contains(managerId)) {
            throw new ServiceException.Validation(
                    "Branch Manager không được đồng thời là Branch Staff");
        }
        requireActiveUser(managerId, Role.BRANCH_MANAGER, "Branch Manager");
        for (Long staffId : distinctStaffIds) {
            requireActiveUser(staffId, Role.BRANCH_STAFF, "Branch Staff");
        }
        requireActiveUser(adminId, Role.ADMIN, "Admin");

        // findByNameNormalized đã so sánh LOWER(name) trong SQL, đảm bảo không trùng
        // khi user gõ "Galaxy" vs "galaxy" — UNIQUE index ở DB là lớp bảo vệ thứ hai.
        if (branchDao.findByNameNormalized(normalizedName).isPresent()) {
            throw new ServiceException.Conflict("Tên chi nhánh đã tồn tại");
        }
        Branch branch = new Branch(normalizedName, address, phone);
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                branchDao.insert(conn, branch);
                assignmentDao.insert(conn,
                        new StaffBranchAssignment(managerId, branch.id(), adminId));
                for (Long staffId : distinctStaffIds) {
                    assignmentDao.insert(conn,
                            new StaffBranchAssignment(staffId, branch.id(), adminId));
                }
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (Exception rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }

        logger.info("Created branch: " + branch.id());
        audit.recordSafely(adminId, "CREATE_BRANCH", "BRANCH", branch.id(), null,
                com.cinema.common.SerializationUtil.toJson(branch),
                com.cinema.service.audit.AuditService.SUCCESS);
        audit.recordSafely(adminId, "ASSIGN_BRANCH", "STAFF_ASSIGNMENT", null, null,
                "{\"managerUserId\":" + managerId + ",\"branchId\":" + branch.id()
                        + ",\"status\":\"ACTIVE\"}",
                com.cinema.service.audit.AuditService.SUCCESS);
        for (Long staffId : distinctStaffIds) {
            audit.recordSafely(adminId, "ASSIGN_BRANCH", "STAFF_ASSIGNMENT", null, null,
                    "{\"staffUserId\":" + staffId + ",\"branchId\":" + branch.id()
                            + ",\"status\":\"ACTIVE\"}",
                    com.cinema.service.audit.AuditService.SUCCESS);
        }
        return branch;
    }

    private void requireActiveUser(Long userId, Role role, String label) throws Exception {
        var user = userDao.findById(userId)
                .orElseThrow(() -> new ServiceException.NotFound(label + " không tồn tại"));
        if (user.role() != role || !user.isActive()) {
            throw new ServiceException.Validation(label + " phải là tài khoản đang hoạt động");
        }
    }

    /**
     * Update branch information with uniqueness check (excluding self).
     */
    public Branch updateBranch(Long branchId, String name, String address, String phone) throws Exception {
        ValidationErrors errors = RequestValidator.builder()
            .required("name", name)
            .required("address", address)
            .required("phone", phone)
            .build();

        if (!errors.isEmpty()) {
            throw new ServiceException.Validation("Tất cả các trường đều bắt buộc");
        }

        String normalizedName = name.trim();
        if (normalizedName.isEmpty()) {
            throw new ServiceException.Validation("Tên chi nhánh không được rỗng");
        }

        Branch existing = branchDao.findById(branchId)
            .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));

        // Phải loại trừ chính branch đang sửa ra khỏi phép so trùng — nếu không
        // thì update lại với cùng tên cũ cũng sẽ bị reject vì "đã tồn tại".
        var duplicateOpt = branchDao.findByNameNormalized(normalizedName);
        if (duplicateOpt.isPresent() && !duplicateOpt.get().id().equals(branchId)) {
            throw new ServiceException.Conflict("Tên chi nhánh đã tồn tại");
        }

        existing.setName(normalizedName);
        existing.setAddress(address);
        existing.setPhone(phone);
        branchDao.update(existing);

        logger.info("Updated branch: " + branchId);
        audit.recordSafely(null, "UPDATE_BRANCH", "BRANCH", branchId, null,
                com.cinema.common.SerializationUtil.toJson(existing),
                com.cinema.service.audit.AuditService.SUCCESS);
        return existing;
    }

    /** Deactivate branch only after all unfinished showtimes have been canceled or moved. */
    public Branch deactivateBranch(Long branchId) throws Exception {
        Branch branch = branchDao.findById(branchId)
            .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));

        if (showtimeDao.hasUnfinishedShowtimesForBranch(branchId)) {
            throw new ServiceException.BusinessRule("BRANCH_HAS_FUTURE_SHOWTIME",
                "Không thể ngưng chi nhánh khi còn suất chiếu chưa kết thúc. "
                    + "Hãy hủy hoặc chuyển các suất chiếu liên quan trước.");
        }

        branch.setStatus("INACTIVE");
        branchDao.updateStatus(branchId, "INACTIVE");

        logger.info("Deactivated branch: " + branchId);
        audit.recordSafely(null, "DEACTIVATE_BRANCH", "BRANCH", branchId,
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"INACTIVE\"}",
                com.cinema.service.audit.AuditService.SUCCESS);
        return branch;
    }

    /** Restore a previously deactivated branch. */
    public Branch restoreBranch(Long branchId) throws Exception {
        Branch branch = branchDao.findById(branchId)
            .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
        if (!"INACTIVE".equalsIgnoreCase(branch.status())) {
            throw new ServiceException.BusinessRule("BRANCH_NOT_INACTIVE",
                    "Chi nhánh không ở trạng thái ngưng hoạt động");
        }
        assignmentDao.activateBranchIfStaffed(branchId);
        branch.setStatus("ACTIVE");
        logger.info("Restored branch: " + branchId);
        audit.recordSafely(null, "RESTORE_BRANCH", "BRANCH", branchId,
                "{\"status\":\"INACTIVE\"}", "{\"status\":\"ACTIVE\"}",
                com.cinema.service.audit.AuditService.SUCCESS);
        return branch;
    }

    /** Compatibility alias for callers using the earlier checked-deactivation method. */
    public Branch deactivateBranchWithShowtimeCheck(Long branchId) throws Exception {
        return deactivateBranch(branchId);
    }

}
