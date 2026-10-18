package com.cinema.listener;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduler quét dọn dữ liệu hết hạn (design.md ExpiryScheduler — Req 4.6, 7.4, 8.6,
 * 9.4, 20.6, 21.7, 23.3). Chạy trên một ScheduledExecutorService daemon, chu kỳ 30 giây.
 *
 * <p>Mỗi tác vụ chạy trong try/catch riêng: một sweep lỗi không chặn các sweep còn lại
 * và không ảnh hưởng transaction request đang chạy (mỗi sweep tự mở transaction ngắn,
 * idempotent). Scheduler dừng sạch khi ứng dụng shutdown qua {@link #stop()}.
 */
public final class ExpiryScheduler {
    private static final Logger logger = Logger.getLogger(ExpiryScheduler.class.getName());

    /** Chu kỳ quét mặc định (design.md: scheduler quét mỗi 30 giây). */
    public static final long DEFAULT_INTERVAL_SECONDS = 30;

    private final List<SweepTask> tasks;
    private final long intervalSeconds;
    private ScheduledExecutorService executor;

    public ExpiryScheduler(List<SweepTask> tasks) {
        this(tasks, DEFAULT_INTERVAL_SECONDS);
    }

    public ExpiryScheduler(List<SweepTask> tasks, long intervalSeconds) {
        this.tasks = List.copyOf(tasks);
        this.intervalSeconds = intervalSeconds > 0 ? intervalSeconds : DEFAULT_INTERVAL_SECONDS;
    }

    /** Khởi động scheduler (gọi từ AppContextListener.contextInitialized). */
    public void start() {
        if (executor != null) {
            logger.warning("ExpiryScheduler đã chạy — bỏ qua start()");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cinema-expiry-scheduler");
            thread.setDaemon(true); // không giữ JVM sống khi shutdown
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepAll,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        logger.info("ExpiryScheduler started — interval " + intervalSeconds
                + "s, " + tasks.size() + " task(s)");
    }

    /** Dừng scheduler (gọi từ AppContextListener.contextDestroyed). */
    public void stop() {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
        logger.info("ExpiryScheduler stopped");
    }

    /** Chạy một lượt tất cả sweep — mỗi task cô lập lỗi. */
    void sweepAll() {
        for (SweepTask task : tasks) {
            try {
                task.run();
            } catch (Exception e) {
                // Req 15.4 / design.md Monitoring — scheduler lỗi không ảnh hưởng luồng chính
                logger.log(Level.WARNING, "Sweep task '" + task.name() + "' failed", e);
            }
        }
    }

    /** Một tác vụ quét dọn định kỳ. */
    @FunctionalInterface
    public interface SweepTask {
        void run() throws Exception;

        /** Tên task cho logging. */
        default String name() {
            return getClass().getSimpleName();
        }
    }

    /** SweepTask có tên (tiện đăng ký từ listener). */
    public record NamedTask(String name, ThrowingRunnable body) implements SweepTask {
        @Override
        public void run() throws Exception {
            body.run();
        }

        @Override
        public String name() {
            return name;
        }
    }

    /** Body có thể ném exception. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
