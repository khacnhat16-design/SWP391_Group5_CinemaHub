package com.cinema.showtime;

import com.cinema.branch.BranchDAO;
import com.cinema.common.ServiceException;
import com.cinema.movie.Movie;
import com.cinema.movie.MovieDAO;
import com.cinema.screen.Screen;
import com.cinema.screen.ScreenDAO;
import com.cinema.util.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;

/**
 * Service xếp lịch chiếu với kiểm tra xung đột (Req 4.1-4.7) và discovery (Req 6.1-6.4).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Tạo lịch: phòng thuộc chi nhánh người thao tác, phim PUBLISHED trong khoảng ngày,
 *       start ở tương lai; end = start + duration + buffer (Req 4.1).</li>
 *   <li>Xung đột giao thoa cùng screen (kể cả buffer) → 409 kèm chi tiết lịch xung đột (Req 4.2).</li>
 *   <li>Hai request đồng thời: SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK) trong transaction
 *       serialize các request — đúng một thắng (Req 4.3).</li>
 *   <li>Sửa thời gian: kiểm lại toàn bộ điều kiện như tạo mới, loại trừ chính nó (Req 4.4).</li>
 *   <li>Lịch có vé/hold không thể sửa/xóa (Req 4.5); qua end+buffer auto ENDED (Req 4.6);
 *       ENDED/CANCELLED chặn hold mới (Req 4.7).</li>
 * </ul>
 */
public class ShowtimeService {
    private static final Logger logger = Logger.getLogger(ShowtimeService.class.getName());
    /** Req 15.3/11.2 — audit mọi state transition của showtime. */
    private final com.cinema.audit.AuditService audit = new com.cinema.audit.AuditService();
    public static final int DEFAULT_CLEANING_BUFFER_MIN = 15;
    private static final int DISCOVERY_PAGE_SIZE = 50;

    private final ShowtimeDAO showtimeDao;
    private final ScreenDAO screenDao;
    private final MovieDAO movieDao;
    private final BranchDAO branchDao;
    private final TransactionTemplate tx = new TransactionTemplate();

    public ShowtimeService(ShowtimeDAO showtimeDao, ScreenDAO screenDao,
                           MovieDAO movieDao, BranchDAO branchDao) {
        this.showtimeDao = showtimeDao;
        this.screenDao = screenDao;
        this.movieDao = movieDao;
        this.branchDao = branchDao;
    }

    /**
     * Tạo lịch chiếu (Req 4.1-4.3). Toàn bộ check xung đột + insert + khởi tạo ghế
     * chạy trong MỘT transaction với row-lock để hai request đồng thời không tạo trùng.
     *
     * @param actorBranchScope branchId hiệu lực của người thao tác (Admin: null)
     */
    public Showtime create(Long actorBranchScope, long movieId, long screenId,
                           LocalDateTime startTime, Integer bufferMin) {
        int buffer = bufferMin != null && bufferMin > 0 ? bufferMin : DEFAULT_CLEANING_BUFFER_MIN;

        // Validate tham chiếu (screen/movie/branch) TRƯỚC khi vào transaction để
        // fail-fast với lỗi rõ ràng thay vì giữ connection lock rồi mới ném.
        Screen screen = requireScreenInScope(actorBranchScope, screenId);
        Movie movie = requirePublishedMovie(movieId, startTime.toLocalDate());
        requireBranchActive(screen.branchId());
        if (!startTime.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new ServiceException.Validation("Thời gian bắt đầu phải ở tương lai");
        }
        LocalDateTime endTime = startTime.plusMinutes(movie.durationMin());

        return tx.execute(conn -> {
            // Khóa các showtime cùng screen — request đồng thời phải chờ (Req 4.3)
            List<Showtime> overlaps = showtimeDao.findOverlappingLocked(
                    conn, screenId, startTime, endTime, buffer, null);
            if (!overlaps.isEmpty()) {
                throw conflictWith(overlaps);
            }

            Showtime showtime = new Showtime();
            showtime.setMovieId(movieId);
            showtime.setScreenId(screenId);
            showtime.setBranchId(screen.branchId());
            showtime.setStartTime(startTime);
            showtime.setEndTime(endTime);
            showtime.setCleaningBufferMin(buffer);
            showtime.setStatus(Showtime.STATUS_OPEN);

            String insertSql = """
                INSERT INTO dbo.showtime (movie_id, screen_id, branch_id, start_time, end_time,
                                          cleaning_buffer_min, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """;
            long showtimeId;
            try (var ps = conn.prepareStatement(insertSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, movieId);
                ps.setLong(2, screenId);
                ps.setLong(3, screen.branchId());
                ps.setTimestamp(4, java.sql.Timestamp.valueOf(startTime));
                ps.setTimestamp(5, java.sql.Timestamp.valueOf(endTime));
                ps.setInt(6, buffer);
                ps.setString(7, Showtime.STATUS_OPEN);
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) throw new ServiceException.Conflict("Không tạo được lịch chiếu");
                    showtimeId = rs.getLong(1);
                }
            }
            showtime.setId(showtimeId);

            // Khởi tạo showtime_seat hàng loạt cho toàn bộ ghế ACTIVE trong phòng.
            // Bước này phải nằm trong cùng transaction với insert showtime: nếu
            // seat init fail, showtime cũng rollback tránh trạng thái "showtime
            // tồn tại nhưng không có ghế".
            showtimeDao.initializeSeats(conn, showtimeId, screenId);

            logger.info("Created showtime " + showtimeId + " (movie " + movieId
                    + ", screen " + screenId + ", start " + startTime + ")");
            // Audit trong cùng transaction — rollback chung nếu giao dịch thất bại (Req 15.2)
            audit.record(conn, null, "CREATE_SHOWTIME", "SHOWTIME", showtimeId, null,
                    "{\"branchId\":" + screen.branchId() + ",\"movieId\":" + movieId
                            + ",\"screenId\":" + screenId + ",\"startTime\":\"" + startTime
                            + "\",\"status\":\"OPEN\"}",
                    com.cinema.audit.AuditService.SUCCESS);
            return showtime;
        });
    }

    /**
     * Cập nhật thời gian lịch chiếu (Req 4.4, 4.5): kiểm lại toàn bộ điều kiện,
     * loại trừ chính lịch đang sửa; chặn khi đã có vé/hold.
     */
    public Showtime updateTime(Long actorBranchScope, long showtimeId, LocalDateTime newStart) throws Exception {
        Showtime existing = requireShowtimeInScope(actorBranchScope, showtimeId);

        if (!Showtime.STATUS_OPEN.equals(existing.status())) {
            throw new ServiceException.Conflict("Chỉ lịch chiếu đang mở bán mới có thể đổi thời gian");
        }
        if (!newStart.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new ServiceException.Validation("Thời gian bắt đầu phải ở tương lai");
        }
        if (showtimeDao.hasTicketsOrActiveHolds(showtimeId)) {
            throw new ServiceException.BusinessRule("SHOWTIME_HAS_TICKETS",
                    "Lịch chiếu đã có vé/giữ chỗ — hãy xử lý vé (hủy/hoàn tiền/dời lịch) trước khi đổi giờ");
        }

        Movie movie = requirePublishedMovie(existing.movieId(), newStart.toLocalDate());
        LocalDateTime newEnd = newStart.plusMinutes(movie.durationMin());
        LocalDateTime oldStart = existing.startTime();

        return tx.execute(conn -> {
            List<Showtime> overlaps = showtimeDao.findOverlappingLocked(
                    conn, existing.screenId(), newStart, newEnd,
                    existing.cleaningBufferMin(), showtimeId);
            if (!overlaps.isEmpty()) {
                throw conflictWith(overlaps);
            }
            if (!showtimeDao.updateTime(conn, showtimeId, newStart, newEnd, existing.version())) {
                throw new ServiceException.Conflict(
                        "Lịch chiếu đã bị người khác thay đổi — vui lòng tải lại dữ liệu mới nhất");
            }
            existing.setStartTime(newStart);
            existing.setEndTime(newEnd);
            existing.setVersion(existing.version() + 1);
            logger.info("Updated showtime " + showtimeId + " start → " + newStart);
            audit.record(conn, null, "UPDATE_SHOWTIME_TIME", "SHOWTIME", showtimeId,
                    "{\"startTime\":\"" + oldStart + "\"}",
                    "{\"startTime\":\"" + newStart + "\",\"endTime\":\"" + newEnd + "\"}",
                    com.cinema.audit.AuditService.SUCCESS);
            return existing;
        });
    }

    /** Hủy lịch chiếu (Req 4.5): chặn khi đã có vé/hold chưa hết hạn. */
    public Showtime cancel(Long actorBranchScope, long showtimeId) throws Exception {
        Showtime existing = requireShowtimeInScope(actorBranchScope, showtimeId);

        if (!Showtime.STATUS_OPEN.equals(existing.status())) {
            throw new ServiceException.Conflict("Lịch chiếu không ở trạng thái mở bán");
        }
        if (showtimeDao.hasTicketsOrActiveHolds(showtimeId)) {
            throw new ServiceException.BusinessRule("SHOWTIME_HAS_TICKETS",
                    "Lịch chiếu đã có vé/giữ chỗ — hãy xử lý vé liên quan trước khi hủy lịch");
        }
        if (!showtimeDao.updateStatus(showtimeId, Showtime.STATUS_CANCELLED, existing.version())) {
            throw new ServiceException.Conflict(
                    "Lịch chiếu đã bị người khác thay đổi — vui lòng tải lại dữ liệu mới nhất");
        }
        existing.setStatus(Showtime.STATUS_CANCELLED);
        logger.info("Cancelled showtime " + showtimeId);
        audit.recordSafely(null, "CANCEL_SHOWTIME", "SHOWTIME", showtimeId,
                "{\"status\":\"OPEN\"}", "{\"status\":\"CANCELLED\"}",
                com.cinema.audit.AuditService.SUCCESS);
        return existing;
    }

    /** Reopen a cancelled showtime when its screen, movie, and time are still valid. */
    public Showtime restore(Long actorBranchScope, long showtimeId) throws Exception {
        Showtime existing = requireShowtimeInScope(actorBranchScope, showtimeId);
        if (!Showtime.STATUS_CANCELLED.equals(existing.status())) {
            throw new ServiceException.Conflict("Chỉ lịch chiếu đã hủy mới có thể khôi phục");
        }
        if (!existing.startTime().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new ServiceException.Conflict("Không thể khôi phục lịch chiếu đã bắt đầu");
        }
        return tx.execute(conn -> {
            List<Showtime> overlaps = showtimeDao.findOverlappingLocked(
                    conn, existing.screenId(), existing.startTime(), existing.endTime(),
                    existing.cleaningBufferMin(), showtimeId);
            if (!overlaps.isEmpty()) {
                throw conflictWith(overlaps);
            }
            if (!showtimeDao.updateStatus(conn, showtimeId, Showtime.STATUS_OPEN, existing.version())) {
                throw new ServiceException.Conflict(
                        "Lịch chiếu đã bị người khác thay đổi — vui lòng tải lại dữ liệu mới nhất");
            }
            existing.setStatus(Showtime.STATUS_OPEN);
            return existing;
        });
    }

    /**
     * Lifecycle (Req 4.6, 4.7): chuyển OPEN → ENDED khi qua end+buffer (lazy — gọi ở đầu
     * mỗi truy vấn liên quan; scheduler 30s sẽ bổ sung ở task 11.1). Lịch ENDED/CANCELLED
     * không nhận hold mới — BookingService (task 7.1) kiểm qua {@link #requireBookable}.
     */
    public void endElapsedShowtimes() {
        try {
            int ended = showtimeDao.endElapsedShowtimes();
            if (ended > 0) logger.info("Auto-ended " + ended + " elapsed showtime(s)");
        } catch (Exception e) {
            // Best-effort sweep: nếu DB chậm hoặc lock timeout, KHÔNG throw để tránh
            // làm fail cả request API. Lần gọi sau sẽ tự retry.
            logger.warning("Failed to sweep elapsed showtimes: " + e.getMessage());
        }
    }

    /** Kiểm tra lịch chiếu còn nhận hold/booking (Req 4.6, 4.7) — trả showtime hợp lệ. */
    public Showtime requireBookable(long showtimeId) throws Exception {
        endElapsedShowtimes();
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
        if (!Showtime.STATUS_OPEN.equals(showtime.status())) {
            throw new ServiceException.Conflict(Showtime.STATUS_CANCELLED.equals(showtime.status())
                    ? "Suất chiếu đã bị hủy, không thể đặt vé"
                    : "Suất chiếu đã kết thúc, không thể đặt vé");
        }
        LocalDateTime cutoff = showtime.bookingCutoff();
        if (cutoff != null && LocalDateTime.now(ZoneOffset.UTC).isAfter(cutoff)) {
            throw new ServiceException.Conflict("Suất chiếu đã qua thời hạn đặt vé (kết thúc + buffer dọn phòng)");
        }
        return showtime;
    }

    /**
     * Đọc showtime cho luồng hủy vé/soát vé (Req 11.1, 12.6): KHÔNG yêu cầu OPEN —
     * vé CONFIRMED của suất đã ENDED vẫn hủy/soát được theo chính sách riêng.
     */
    public Showtime requireBookableForCancel(long showtimeId) throws Exception {
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
        if (Showtime.STATUS_CANCELLED.equals(showtime.status())) {
            // Suất bị hủy — vé vẫn xử lý hủy/hoàn bình thường, không chặn ở đây
            logger.info("Showtime " + showtimeId + " cancelled — refund flow allowed");
        }
        return showtime;
    }

    /** Đọc showtime theo id (không kiểm trạng thái) — dùng nội bộ cross-domain. */
    public Showtime findById(long showtimeId) throws Exception {
        return showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
    }

    /** Discovery công khai (Req 6.1, 6.2, 6.4): Guest không cần đăng nhập. */
    public List<Showtime> discover(Long branchId, Long movieId, LocalDate date) throws Exception {
        endElapsedShowtimes();
        // Req 6.4 — chi nhánh INACTIVE không trả suất chiếu (query đã ép b.status='ACTIVE')
        return showtimeDao.discover(branchId, movieId, date, 0, DISCOVERY_PAGE_SIZE);
    }

    /** Chi tiết suất chiếu kèm trạng thái từng ghế (Req 6.3). */
    public ShowtimeDetail getDetail(long showtimeId) throws Exception {
        endElapsedShowtimes();
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
        List<ShowtimeDAO.ShowtimeSeat> seats = showtimeDao.seatStatus(showtimeId);
        return new ShowtimeDetail(showtime, seats);
    }

    // ---- helpers ----

    private Screen requireScreenInScope(Long actorBranchScope, long screenId) {
        try {
            Screen screen = screenDao.findById(screenId)
                    .orElseThrow(() -> new ServiceException.NotFound("Phòng chiếu không tồn tại"));
            if (actorBranchScope != null && actorBranchScope != screen.branchId()) {
                throw new ServiceException.Forbidden(
                        "Vi phạm phạm vi chi nhánh: không thể xếp lịch cho phòng của chi nhánh khác");
            }
            if (!"ACTIVE".equals(screen.status())) {
                throw new ServiceException.BusinessRule("SCREEN_INACTIVE",
                        "Phòng chiếu đã vô hiệu hóa, không thể xếp lịch mới");
            }
            return screen;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không kiểm tra được phòng chiếu", e);
        }
    }

    private Showtime requireShowtimeInScope(Long actorBranchScope, long showtimeId) {
        try {
            Showtime showtime = showtimeDao.findById(showtimeId)
                    .orElseThrow(() -> new ServiceException.NotFound("Lịch chiếu không tồn tại"));
            if (actorBranchScope != null && actorBranchScope != showtime.branchId()) {
                throw new ServiceException.Forbidden(
                        "Vi phạm phạm vi chi nhánh: không thể thao tác lịch chiếu của chi nhánh khác");
            }
            return showtime;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không kiểm tra được lịch chiếu", e);
        }
    }

    /** Req 4.1 — phim phải PUBLISHED và ngày chiếu nằm trong khoảng hiệu lực. */
    private Movie requirePublishedMovie(long movieId, LocalDate showDate) {
        try {
            Movie movie = movieDao.findById(movieId)
                    .orElseThrow(() -> new ServiceException.NotFound("Phim không tồn tại"));
            if (!Movie.STATUS_PUBLISHED.equals(movie.status())) {
                throw new ServiceException.BusinessRule("MOVIE_NOT_PUBLISHED",
                        "Chỉ phim đã phát hành (PUBLISHED) mới được xếp lịch");
            }
            if (showDate.isBefore(movie.releaseDate()) || showDate.isAfter(movie.endDate())) {
                throw new ServiceException.BusinessRule("MOVIE_OUT_OF_WINDOW",
                        "Ngày chiếu nằm ngoài khoảng hiệu lực của phim ("
                                + movie.releaseDate() + " → " + movie.endDate() + ")");
            }
            return movie;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không kiểm tra được phim", e);
        }
    }

    /** Req 1.6 — branch INACTIVE chặn tạo lịch chiếu mới. */
    private void requireBranchActive(long branchId) {
        try {
            var branch = branchDao.findById(branchId)
                    .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
            if (!branch.isActive()) {
                throw new ServiceException.BusinessRule("BRANCH_INACTIVE",
                        "Chi nhánh đã ngừng hoạt động, không thể tạo lịch chiếu mới");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không kiểm tra được chi nhánh", e);
        }
    }

    /** Req 4.2 — thông báo xung đột kèm chi tiết lịch chiếu giao thoa. */
    private ServiceException.Conflict conflictWith(List<Showtime> overlaps) {
        StringBuilder detail = new StringBuilder("Trùng lịch chiếu với: ");
        for (Showtime overlap : overlaps) {
            detail.append("[showtime ").append(overlap.id())
                    .append(" ").append(overlap.startTime()).append("→").append(overlap.endTime())
                    .append("] ");
        }
        detail.append("— vui lòng chọn thời gian khác");
        return new ServiceException.Conflict(detail.toString().trim());
    }

    /** Chi tiết suất chiếu cho màn hình đặt vé (Req 6.3). */
    public record ShowtimeDetail(Showtime showtime, List<ShowtimeDAO.ShowtimeSeat> seats) { }
}
