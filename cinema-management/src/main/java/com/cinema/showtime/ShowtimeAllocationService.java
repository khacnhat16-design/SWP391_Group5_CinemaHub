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

 

   

    public enum ReminderStage { NONE, NORMAL, URGENT }
}
