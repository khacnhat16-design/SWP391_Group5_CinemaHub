package com.cinema.branch;

import com.cinema.common.RequestValidator;
import com.cinema.common.ServiceException;
import com.cinema.common.ValidationErrors;
import com.cinema.showtime.ShowtimeDAO;

import java.util.logging.Logger;

/** Service for branch management (Admin-only). */
public class BranchService {
    private static final Logger logger = Logger.getLogger(BranchService.class.getName());
    /** Req 15.3/11.2 — audit mọi thay đổi trạng thái branch. */
    private final com.cinema.audit.AuditService audit = new com.cinema.audit.AuditService();
    private final BranchDAO branchDao;
    private final ShowtimeDAO showtimeDao;

    public BranchService(BranchDAO branchDAO) {
        this(branchDAO, new ShowtimeDAO());
    }

    public BranchService(BranchDAO branchDAO, ShowtimeDAO showtimeDAO) {
        this.branchDao = branchDAO;
        this.showtimeDao = showtimeDAO;
    }

    /**
     * Create a new branch with validation.
     * Normalizes name (whitespace trimming) and enforces case-insensitive uniqueness.
     */
    public Branch createBranch(String name, String address, String phone) throws Exception {
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
        // findByNameNormalized đã so sánh LOWER(name) trong SQL, đảm bảo không trùng
        // khi user gõ "Galaxy" vs "galaxy" — UNIQUE index ở DB là lớp bảo vệ thứ hai.
        if (branchDao.findByNameNormalized(normalizedName).isPresent()) {
            throw new ServiceException.Conflict("Tên chi nhánh đã tồn tại");
        }

        Branch branch = new Branch(normalizedName, address, phone);
        branchDao.insert(branch);

        logger.info("Created branch: " + branch.id());
        audit.recordSafely(null, "CREATE_BRANCH", "BRANCH", branch.id(), null,
                com.cinema.common.SerializationUtil.toJson(branch),
                com.cinema.audit.AuditService.SUCCESS);
        return branch;
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
                com.cinema.audit.AuditService.SUCCESS);
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
                com.cinema.audit.AuditService.SUCCESS);
        return branch;
    }

    /** Compatibility alias for callers using the earlier checked-deactivation method. */
    public Branch deactivateBranchWithShowtimeCheck(Long branchId) throws Exception {
        return deactivateBranch(branchId);
    }

}
