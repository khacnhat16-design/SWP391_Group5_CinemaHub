package com.cinema.showtime;

import com.cinema.common.SerializationUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho business rules của ShowtimeAllocation — phần pure math/status
 * mapping (không cần DB).
 *
 * <p>Covers test cases 1..3 trong yêu cầu:
 * <ul>
 *   <li>Case 1: allocated = 5, created = 5 → COMPLETED.</li>
 *   <li>Case 2: allocated = 5, created = 4 → IN_PROGRESS (không COMPLETED).</li>
 *   <li>Case 3: allocated = 5, created = 6 → OVER_ALLOCATED.</li>
 * </ul>
 */
class ShowtimeAllocationServiceTest {

    @Test
    void case1_completedWhenCreatedEqualsAllocated() {
        String status = ShowtimeAllocationService.computeStatus(5, 5);
        assertEquals(ShowtimeAllocation.STATUS_COMPLETED, status,
                "created == allocated phải là COMPLETED");
    }

    @Test
    void case2_inProgressWhenCreatedLessThanAllocated() {
        String status = ShowtimeAllocationService.computeStatus(5, 4);
        assertEquals(ShowtimeAllocation.STATUS_IN_PROGRESS, status,
                "created < allocated phải là IN_PROGRESS");
        String zero = ShowtimeAllocationService.computeStatus(5, 0);
        assertEquals(ShowtimeAllocation.STATUS_PENDING, zero,
                "created == 0 phải trở về PENDING");
    }

    @Test
    void case3_overAllocatedWhenCreatedGreaterThanAllocated() {
        String status = ShowtimeAllocationService.computeStatus(5, 6);
        assertEquals(ShowtimeAllocation.STATUS_OVER_ALLOCATED, status,
                "created > allocated phải là OVER_ALLOCATED");
    }

    @Test
    void zeroAllocatedIsPendingOnlyWhenNoShowtimesExist() {
        String zeroAlloc = ShowtimeAllocationService.computeStatus(0, 0);
        assertEquals(ShowtimeAllocation.STATUS_PENDING, zeroAlloc);
        assertEquals(ShowtimeAllocation.STATUS_OVER_ALLOCATED,
                ShowtimeAllocationService.computeStatus(0, 1));
    }

    @Test
    void cancellationRecalculationReturnsToCompletedOrInProgress() {
        assertEquals(ShowtimeAllocation.STATUS_COMPLETED,
                ShowtimeAllocationService.computeStatus(5, 5));
        assertEquals(ShowtimeAllocation.STATUS_IN_PROGRESS,
                ShowtimeAllocationService.computeStatus(5, 4));
    }

    @Test
    void negativeAllocatedIsTreatedAsPending() {
        // Phòng hờ: validateQuantity chặn âm nhưng computeStatus phải defensive
        String neg = ShowtimeAllocationService.computeStatus(-1, 0);
        assertEquals(ShowtimeAllocation.STATUS_PENDING, neg);
    }

    @Test
    void zeroCreatedAlwaysReturnsToPendingAfterCancellation() {
        assertEquals(ShowtimeAllocation.STATUS_PENDING,
                ShowtimeAllocationService.computeStatus(4, 0));
    }

    @Test
    void allocationUpdateNotificationShowsCurrentRemainingQuantity() {
        assertEquals(
                "Admin đã cập nhật số suất phân bổ của phim Test tại chi nhánh Central từ 1 lên 3. "
                        + "Chi nhánh đã tạo 1 suất và còn cần tạo 2 suất.",
                ShowtimeAllocationService.formatAllocationUpdateNotificationBody(
                        "Test", "Central", 1, 3, 1));
    }

    @Test
    void allocationUpdateNotificationExplainsWhenCreatedQuantityExceedsNewAllocation() {
        assertEquals(
                "Admin đã cập nhật số suất phân bổ của phim Test tại chi nhánh Central từ 3 lên 1. "
                        + "Chi nhánh đã tạo 2 suất, vượt 1 suất so với phân bổ mới.",
                ShowtimeAllocationService.formatAllocationUpdateNotificationBody(
                        "Test", "Central", 3, 1, 2));
    }

    // ---- Entity helpers ----

    @Test
    void entityRemainingAndOverAllocatedFlags() {
        ShowtimeAllocation a = new ShowtimeAllocation();
        a.setAllocatedQuantity(4);
        a.setCreatedQuantity(4);
        assertEquals(0, a.remainingQuantity());
        assertTrue(a.isCompleted());
        assertFalse(a.isOverAllocated());

        a.setCreatedQuantity(3);
        assertEquals(1, a.remainingQuantity());
        assertFalse(a.isCompleted());
        assertFalse(a.isOverAllocated());

        a.setCreatedQuantity(6);
        assertEquals(-2, a.remainingQuantity());
        assertFalse(a.isCompleted());
        assertTrue(a.isOverAllocated());

        a.setAllocatedQuantity(0);
        a.setCreatedQuantity(0);
        assertFalse(a.isCompleted());
    }

    @Test
    void apiSerializationIncludesServerCalculatedRemainingQuantity() {
        ShowtimeAllocation allocation = new ShowtimeAllocation();
        allocation.setAllocatedQuantity(5);
        allocation.setCreatedQuantity(6);
        String json = SerializationUtil.toJson(allocation);
        assertTrue(json.contains("\"remainingQuantity\":-1"));
    }

    @Test
    void validStatusSetIncludesAllFourStatuses() {
        assertTrue(ShowtimeAllocation.VALID_STATUSES.contains(
                ShowtimeAllocation.STATUS_PENDING));
        assertTrue(ShowtimeAllocation.VALID_STATUSES.contains(
                ShowtimeAllocation.STATUS_IN_PROGRESS));
        assertTrue(ShowtimeAllocation.VALID_STATUSES.contains(
                ShowtimeAllocation.STATUS_COMPLETED));
        assertTrue(ShowtimeAllocation.VALID_STATUSES.contains(
                ShowtimeAllocation.STATUS_OVER_ALLOCATED));
        assertEquals(4, ShowtimeAllocation.VALID_STATUSES.size());
    }

    @Test
    void reminderStageUsesInclusiveNormalAndUrgentBoundaries() {
        LocalDate businessDate = LocalDate.of(2026, 10, 4);
        assertEquals(ShowtimeAllocationService.ReminderStage.NORMAL,
                reminderStage(businessDate, 7, "PUBLISHED", 5, 0));
        assertEquals(ShowtimeAllocationService.ReminderStage.NORMAL,
                reminderStage(businessDate, 3, "PUBLISHED", 5, 4));
        assertEquals(ShowtimeAllocationService.ReminderStage.URGENT,
                reminderStage(businessDate, 2, "PUBLISHED", 5, 0));
        assertEquals(ShowtimeAllocationService.ReminderStage.URGENT,
                reminderStage(businessDate, 1, "PUBLISHED", 5, 4));
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, 8, "PUBLISHED", 5, 0));
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, 0, "PUBLISHED", 5, 0));
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, -1, "PUBLISHED", 5, 0));
    }

    @Test
    void reminderStageRequiresPublishedMovieAndRemainingQuota() {
        LocalDate businessDate = LocalDate.of(2026, 10, 4);
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, 2, "DRAFT", 5, 0));
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, 2, "PUBLISHED", 0, 0));
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, 2, "PUBLISHED", 5, 5));
        assertEquals(ShowtimeAllocationService.ReminderStage.NONE,
                reminderStage(businessDate, 2, "PUBLISHED", 5, 6));
    }

    private ShowtimeAllocationService.ReminderStage reminderStage(LocalDate businessDate,
                                                                   int daysRemaining,
                                                                   String status,
                                                                   int allocated,
                                                                   int created) {
        return ShowtimeAllocationService.reminderStage(
                businessDate.plusDays(daysRemaining), businessDate, status, allocated, created);
    }
}
