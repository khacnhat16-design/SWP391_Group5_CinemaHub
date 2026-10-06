package com.cinema.showtime;

import com.cinema.common.ServiceException;
import com.cinema.notification.NotificationService;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;

/** Business rules for allocation quotas and schedule-reminder stages. */
public class ShowtimeAllocationService {
    private static final Logger logger = Logger.getLogger(ShowtimeAllocationService.class.getName());

    private final ShowtimeAllocationDAO allocationDao;
    private final NotificationService notificationService;

    public ShowtimeAllocationService(ShowtimeAllocationDAO allocationDao) {
        this(allocationDao, null);
    }

    public ShowtimeAllocationService(ShowtimeAllocationDAO allocationDao,
                                     NotificationService notificationService) {
        this.allocationDao = allocationDao;
        this.notificationService = notificationService;
    }

    public static String computeStatus(int allocated, int created) {
        if (allocated < 0 || created <= 0) return ShowtimeAllocation.STATUS_PENDING;
        if (created > allocated) return ShowtimeAllocation.STATUS_OVER_ALLOCATED;
        if (allocated > 0 && created == allocated) return ShowtimeAllocation.STATUS_COMPLETED;
        return ShowtimeAllocation.STATUS_IN_PROGRESS;
    }

    public static void requireRemainingSlot(int allocated, int created) {
        if (allocated <= 0 || created >= allocated) {
            throw new ServiceException.Conflict("Đã sử dụng hết số suất chiếu được phân bổ");
        }
    }

    public static String formatAllocationUpdateNotificationBody(String movieTitle,
                                                                 String branchName,
                                                                 int oldQuantity,
                                                                 int newQuantity,
                                                                 int createdQuantity) {
        String prefix = "Admin đã cập nhật số suất phân bổ của phim " + movieTitle
                + " tại chi nhánh " + branchName + " từ " + oldQuantity + " lên " + newQuantity
                + ". Chi nhánh đã tạo " + createdQuantity + " suất";
        int remaining = newQuantity - createdQuantity;
        return remaining < 0
                ? prefix + ", vượt " + Math.abs(remaining) + " suất so với phân bổ mới."
                : prefix + " và còn cần tạo " + remaining + " suất.";
    }

    public static ReminderStage reminderStage(LocalDate releaseDate, LocalDate businessDate,
                                               String movieStatus, int allocated, int created) {
        if (releaseDate == null || businessDate == null || !"PUBLISHED".equals(movieStatus)
                || allocated <= created || created < 0) return ReminderStage.NONE;
        long days = ChronoUnit.DAYS.between(businessDate, releaseDate);
        if (days >= 3 && days <= 7) return ReminderStage.NORMAL;
        if (days >= 1 && days <= 2) return ReminderStage.URGENT;
        return ReminderStage.NONE;
    }

    public java.util.List<ShowtimeAllocation> list(String status, Long branchId, Long movieId)
            throws Exception {
        return allocationDao.find(status, branchId, movieId);
    }

    public ShowtimeAllocation create(long movieId, long branchId, int quantity, String note,
                                     Long actorUserId) throws Exception {
        if (movieId <= 0 || branchId <= 0 || quantity < 1) {
            throw new ServiceException.Validation("Phim, chi nhánh và số suất phân bổ hợp lệ là bắt buộc");
        }
        if (allocationDao.existsForMovieAndBranch(movieId, branchId)) {
            throw new ServiceException.Conflict("Phân bổ cho phim và chi nhánh này đã tồn tại");
        }
        ShowtimeAllocation allocation = new ShowtimeAllocation();
        allocation.setMovieId(movieId);
        allocation.setBranchId(branchId);
        allocation.setAllocatedQuantity(quantity);
        allocation.setNote(note);
        allocationDao.insert(allocation, actorUserId);
        if (notificationService != null) {
            notificationService.notifyBranchManagers(branchId,
                    com.cinema.notification.Notification.TYPE_SHOWTIME_ALLOCATION_UPDATED,
                    "Phân bổ suất chiếu mới",
                    "Admin đã tạo phân bổ " + quantity + " suất chiếu cho chi nhánh của bạn.");
        }
        return allocation;
    }

   

    public enum ReminderStage { NONE, NORMAL, URGENT }
}
