package com.cinema.service.branch;

import com.cinema.dao.branch.BranchDAO;
import com.cinema.model.branch.Branch;
import com.cinema.service.branch.BranchService;

import com.cinema.common.ServiceException;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.dao.auth.StaffBranchAssignmentDAO;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchServiceTest {

    @Test
    void createBranchRequiresAdminId() {
        BranchService service = new BranchService(new TestBranchDAO(null), new ShowtimeDAO());

        ServiceException.Validation error = assertThrows(
                ServiceException.Validation.class,
                () -> service.createBranch("Cinema", "Address", "0900000000"));

        assertEquals("Thiếu adminId trong session", error.getMessage());
    }

    @Test
    void createBranchRequiresBranchManager() {
        BranchService service = new BranchService(new TestBranchDAO(null), new ShowtimeDAO());

        ServiceException.Validation error = assertThrows(
                ServiceException.Validation.class,
                () -> service.createBranch("Cinema", "Address", "0900000000",
                        null, java.util.List.of(), 1L));

        assertEquals("Cần chọn ít nhất 1 Branch Manager để chi nhánh có thể hoạt động",
                error.getMessage());
    }

    @Test
    void createBranchRequiresAtLeastOneStaffMember() {
        BranchService service = new BranchService(new TestBranchDAO(null), new ShowtimeDAO());

        ServiceException.Validation error = assertThrows(
                ServiceException.Validation.class,
                () -> service.createBranch("Cinema", "Address", "0900000000",
                        5L, java.util.List.of(), 1L));

        assertEquals("Cần chọn ít nhất 1 Branch Staff để chi nhánh có thể hoạt động",
                error.getMessage());
    }

    @Test
    void createBranchRejectsManagerThatIsAlsoStaff() {
        BranchService service = new BranchService(new TestBranchDAO(null), new ShowtimeDAO());

        ServiceException.Validation error = assertThrows(
                ServiceException.Validation.class,
                () -> service.createBranch("Cinema", "Address", "0900000000",
                        5L, java.util.List.of(5L), 1L));

        assertEquals("Branch Manager không được đồng thời là Branch Staff",
                error.getMessage());
    }

    @Test
    void updateBranchPersistsBranchDetails() throws Exception {
        Branch branch = new Branch("Cinema", "Address", "0900000000");
        branch.setId(12L);
        TestBranchDAO branchDao = new TestBranchDAO(branch);
        BranchService service = new BranchService(branchDao, new ShowtimeDAO());

        Branch updated = service.updateBranch(
                12L, " Updated Cinema ", "New Address", "0911111111");

        assertEquals("Updated Cinema", updated.name());
        assertEquals("New Address", updated.address());
        assertEquals("0911111111", updated.phone());
        assertTrue(branchDao.updated);
    }

    @Test
    void deactivateBranchRejectsUnfinishedShowtimesWithoutChangingStatus() {
        Branch branch = new Branch("Cinema", "Address", "0900000000");
        branch.setId(12L);
        TestBranchDAO branchDao = new TestBranchDAO(branch);
        ShowtimeDAO showtimeDao = new ShowtimeDAO() {
            @Override
            public boolean hasUnfinishedShowtimesForBranch(long branchId) {
                return true;
            }
        };

        ServiceException.BusinessRule error = assertThrows(
                ServiceException.BusinessRule.class,
                () -> new BranchService(branchDao, showtimeDao).deactivateBranch(12L));

        assertEquals("BRANCH_HAS_FUTURE_SHOWTIME", error.code());
        assertEquals("ACTIVE", branch.status());
        assertFalse(branchDao.statusUpdated);
    }

    @Test
    void restoreBranchChangesInactiveBranchToActive() throws Exception {
        Branch branch = new Branch("Cinema", "Address", "0900000000");
        branch.setId(12L);
        branch.setStatus("INACTIVE");
        TestBranchDAO branchDao = new TestBranchDAO(branch);
        boolean[] activationRequested = {false};

        StaffBranchAssignmentDAO assignmentDao = new StaffBranchAssignmentDAO() {
            @Override
            public void activateBranchIfStaffed(Long branchId) {
                activationRequested[0] = true;
            }
        };
        Branch restored = new BranchService(branchDao, new ShowtimeDAO(), null, assignmentDao)
                .restoreBranch(12L);

        assertEquals("ACTIVE", restored.status());
        assertTrue(activationRequested[0]);
    }

    @Test
    void restoreBranchRejectsActiveBranch() {
        Branch branch = new Branch("Cinema", "Address", "0900000000");
        branch.setId(12L);
        TestBranchDAO branchDao = new TestBranchDAO(branch);

        ServiceException.BusinessRule error = assertThrows(
                ServiceException.BusinessRule.class,
                () -> new BranchService(branchDao, new ShowtimeDAO()).restoreBranch(12L));

        assertEquals("BRANCH_NOT_INACTIVE", error.code());
        assertFalse(branchDao.statusUpdated);
    }

    @Test
    void restoreBranchRequiresManagerAndStaffAssignments() {
        Branch branch = new Branch("Cinema", "Address", "0900000000");
        branch.setId(12L);
        branch.setStatus("INACTIVE");
        TestBranchDAO branchDao = new TestBranchDAO(branch);
        boolean[] activationRequested = {false};
        StaffBranchAssignmentDAO assignmentDao = new StaffBranchAssignmentDAO() {
            @Override
            public void activateBranchIfStaffed(Long branchId) {
                activationRequested[0] = true;
                throw new ServiceException.BusinessRule("BRANCH_STAFFING_REQUIRED",
                        "Cần có Branch Manager và ít nhất một Branch Staff trước khi mở lại chi nhánh");
            }
        };

        ServiceException.BusinessRule error = assertThrows(
                ServiceException.BusinessRule.class,
                () -> new BranchService(branchDao, new ShowtimeDAO(), null, assignmentDao)
                        .restoreBranch(12L));

        assertEquals("BRANCH_STAFFING_REQUIRED", error.code());
        assertTrue(activationRequested[0]);
        assertFalse(branchDao.statusUpdated);
    }

    private static final class TestBranchDAO extends BranchDAO {
        private final Branch branch;
        private boolean statusUpdated;
        private boolean updated;
        private String requestedStatus;

        private TestBranchDAO(Branch branch) {
            this.branch = branch;
        }

        @Override
        public Optional<Branch> findById(Long id) {
            return Optional.of(branch);
        }

        @Override
        public Optional<Branch> findByNameNormalized(String name) {
            return branch != null && branch.name().equalsIgnoreCase(name)
                    ? Optional.of(branch) : Optional.empty();
        }

        @Override
        public void update(Branch branch) {
            updated = true;
        }

        @Override
        public void updateStatus(Long id, String status) {
            statusUpdated = true;
            requestedStatus = status;
        }
    }
}
