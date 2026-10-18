package com.cinema.listener;

import com.cinema.dao.booking.SeatHoldDAO;
import com.cinema.dao.booking.TicketDAO;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.dao.notification.NotificationDAO;
import com.cinema.dao.screen.ScreenDAO;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.service.booking.BookingService;
import com.cinema.service.notification.NotificationService;
import com.cinema.service.showtime.ShowtimeService;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listener vòng đời ứng dụng (design.md AppContextListener): khởi động/dừng
 * {@link ExpiryScheduler} khi webapp deploy/undeploy.
 *
 * <p>Đăng ký các sweep task định kỳ 30 giây (idempotent, mỗi task tự mở transaction ngắn):
 * <ol>
 *   <li>Hold ghế + vé PENDING hết hạn (Req 7.4, 8.6, 9.4) — {@link BookingService#sweepExpiredHolds()}.</li>
 *   <li>Showtime qua end+buffer → ENDED (Req 4.6) — {@link ShowtimeService#endElapsedShowtimes()}.</li>
 *   <li>Reminder suất chiếu 60 phút (Req 23.3) — {@link NotificationService#sendShowtimeReminders()}.</li>
 * </ol>
 *
 * <p>Lỗi khởi tạo scheduler không làm sập webapp — chỉ log (Req 15.4).
 *
 * <p><b>Refactor cho swp (post-MVC):</b> dùng constructor 2-arg của BookingService
 * (chỉ SeatHoldDAO + TicketDAO) — sweep task chỉ cần các DAO liên quan đến expiry,
 * không cần PricingService/PaymentService. Pricing và Payment được xử lý trong
 * controller scope, không thuộc sweep job.
 */
public class AppContextListener implements ServletContextListener {
    private static final Logger logger = Logger.getLogger(AppContextListener.class.getName());
    private static final String SCHEDULER_ATTR = "cinema.expiryScheduler";

    private ExpiryScheduler scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            scheduler = new ExpiryScheduler(buildSweepTasks());
            scheduler.start();
            sce.getServletContext().setAttribute(SCHEDULER_ATTR, scheduler);
            logger.info("AppContextListener — ExpiryScheduler started");
        } catch (Exception e) {
            // Req 15.4 — scheduler lỗi không được chặn ứng dụng khởi động
            logger.log(Level.SEVERE, "Không khởi động được ExpiryScheduler", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();
        ExpiryScheduler active = scheduler;
        Object attr = ctx.getAttribute(SCHEDULER_ATTR);
        if (attr instanceof ExpiryScheduler fromContext) {
            active = fromContext;
        }
        if (active != null) {
            active.stop();
            logger.info("AppContextListener — ExpiryScheduler stopped");
        }
        ctx.removeAttribute(SCHEDULER_ATTR);
    }

    /**
     * Dựng object graph service tối thiểu cho các sweep task. Dùng chung DAO instance
     * (stateless) — mỗi service chỉ cần các dependency nó thực sự gọi trong sweep.
     *
     * <p>Đã loại bỏ PricingService/PaymentService so với bản aws gốc vì:
     * <ul>
     *   <li>PricingService chưa được refactor sang cấu trúc swp (thiếu dao.pricing.*).</li>
     *   <li>PaymentService constructor swp yêu cầu WalletService — không cần cho sweep.</li>
     *   <li>BookingService swp dùng 2-arg constructor cho scope expiry (chỉ DAO).</li>
     * </ul>
     */
    private List<ExpiryScheduler.SweepTask> buildSweepTasks() {
        // DAO (stateless, tái sử dụng)
        ShowtimeDAO showtimeDao = new ShowtimeDAO();
        ScreenDAO screenDao = new ScreenDAO();
        MovieDAO movieDao = new MovieDAO();
        BranchDAO branchDao = new BranchDAO();
        NotificationDAO notificationDao = new NotificationDAO();

        // Service cho sweep task
        ShowtimeService showtimeService = new ShowtimeService(showtimeDao, screenDao, movieDao, branchDao);
        NotificationService notificationService = new NotificationService(notificationDao);
        // BookingService 2-arg: chỉ inject SeatHoldDAO + TicketDAO — sweep chỉ expire
        // hold + ticket PENDING, không cần PricingService/PaymentService.
        BookingService bookingService = new BookingService(new SeatHoldDAO(), new TicketDAO());

        // Danh sách task định kỳ mà ExpiryScheduler sẽ chạy mỗi 30s. Mỗi task
        // best-effort: nếu fail không ảnh hưởng task khác.
        return List.of(
                new ExpiryScheduler.NamedTask("hold-and-ticket-expiry", bookingService::sweepExpiredHolds),
                new ExpiryScheduler.NamedTask("showtime-end", showtimeService::endElapsedShowtimes),
                new ExpiryScheduler.NamedTask("showtime-reminder", () -> notificationService.sendShowtimeReminders())
        );
    }
}
