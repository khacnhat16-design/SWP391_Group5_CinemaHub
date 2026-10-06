package com.cinema.showtime;

import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Data access for showtime table (Req 4.1-4.7, 6.1-6.4). */
public class ShowtimeDAO {
    /** Table hint khóa chống race khi tạo lịch đồng thời (Req 4.3, design.md). */
    private static final String LOCK_HINT = "WITH (UPDLOCK, HOLDLOCK, ROWLOCK)";

    private static final String BASE_COLUMNS = """
        id, movie_id, screen_id, branch_id, start_time, end_time,
        cleaning_buffer_min, status, version
        """;

    private static final String AVAILABLE_SEATS_COLUMN = """
        (SELECT COUNT(*) FROM dbo.showtime_seat ss
          JOIN dbo.seat se ON se.id = ss.seat_id AND se.status = 'ACTIVE'
          WHERE ss.showtime_id = s.id
            AND (ss.status = 'AVAILABLE'
                 OR (ss.status = 'HOLD' AND ss.hold_expires_at <= SYSUTCDATETIME()))
        ) AS available_seats
        """;

    /** Insert showtime; sets generated id back on the entity. */
    public void insert(Showtime showtime) throws Exception {
        String sql = """
            INSERT INTO dbo.showtime (movie_id, screen_id, branch_id, start_time, end_time,
                                      cleaning_buffer_min, status, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """;

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, showtime.movieId());
            ps.setLong(2, showtime.screenId());
            ps.setLong(3, showtime.branchId());
            ps.setTimestamp(4, Timestamp.valueOf(showtime.startTime()));
            ps.setTimestamp(5, Timestamp.valueOf(showtime.endTime()));
            ps.setInt(6, showtime.cleaningBufferMin());
            ps.setString(7, showtime.status());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    showtime.setId(rs.getLong(1));
                }
            }
        }
    }

    public Optional<Showtime> findById(Long id) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.showtime WHERE id = ?";

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Tìm lịch chiếu giao thoa trên cùng screen, có khóa hàng (Req 4.2, 4.3):
     * newStart < existingEnd + buffer AND newEnd + buffer > existingStart.
     * Phải gọi trong transaction (autoCommit=false) để hint UPDLOCK/HOLDLOCK có hiệu lực
     * — dùng {@link #findOverlappingLocked(Connection, ...)} cho luồng tạo đồng thời.
     */
    public List<Showtime> findOverlapping(long screenId, LocalDateTime newStart,
                                          LocalDateTime newEnd, int bufferMin,
                                          Long excludeShowtimeId) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<Showtime> result = findOverlappingLocked(conn, screenId, newStart, newEnd,
                        bufferMin, excludeShowtimeId);
                conn.commit();
                return result;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * Variant nhận connection từ caller (transaction đang mở) và khóa các row showtime
     * của screen bằng UPDLOCK/HOLDLOCK/ROWLOCK — hai request tạo đồng thời sẽ serialize,
     * đúng một request thắng (Req 4.3).
     */
    public List<Showtime> findOverlappingLocked(Connection conn, long screenId,
                                                LocalDateTime newStart, LocalDateTime newEnd,
                                                int bufferMin, Long excludeShowtimeId) throws Exception {
        String sql = "SELECT " + BASE_COLUMNS + " FROM dbo.showtime " + LOCK_HINT + """
             WHERE screen_id = ? AND status <> 'CANCELLED'
               AND (? < DATEADD(minute, cleaning_buffer_min, end_time))
               AND (DATEADD(minute, ?, ?) > start_time)
            """ + (excludeShowtimeId != null ? " AND id <> ?" : "");

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, screenId);
            ps.setTimestamp(2, Timestamp.valueOf(newStart));
            ps.setInt(3, bufferMin);
            ps.setTimestamp(4, Timestamp.valueOf(newEnd));
            if (excludeShowtimeId != null) {
                ps.setLong(5, excludeShowtimeId);
            }
            List<Showtime> overlaps = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    overlaps.add(mapRow(rs));
                }
            }
            return overlaps;
        }
    }

    /** Lịch chiếu tương lai (OPEN, start > now) của một screen — dùng chặn sửa/xóa. */
    public boolean hasFutureShowtimes(long screenId) throws Exception {
        String sql = """
            SELECT COUNT(*) FROM dbo.showtime
            WHERE screen_id = ? AND status = 'OPEN' AND start_time > SYSUTCDATETIME()
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, screenId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Branch has an open screening that has not yet finished (Req 1.4). */
    public boolean hasUnfinishedShowtimesForBranch(long branchId) throws Exception {
        String sql = """
            SELECT COUNT(*) FROM dbo.showtime
            WHERE branch_id = ? AND status = 'OPEN' AND end_time > SYSUTCDATETIME()
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Lịch chiếu có vé confirmed/hold chưa hết hạn? (Req 4.5 — chặn sửa/xóa). */
    public boolean hasTicketsOrActiveHolds(long showtimeId) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return hasTicketsOrActiveHolds(conn, showtimeId);
        }
    }

    public boolean hasTicketsOrActiveHolds(Connection conn, long showtimeId) throws Exception {
        String sql = """
            SELECT COUNT(*) FROM dbo.showtime_seat ss
            WHERE ss.showtime_id = ?
              AND (ss.status = 'SOLD'
                   OR (ss.status = 'HOLD' AND ss.hold_expires_at > SYSUTCDATETIME()))
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Discovery cho Guest/Customer (Req 6.1, 6.4): OPEN, branch ACTIVE, phim PUBLISHED,
     * start_time ở tương lai; lọc theo branch/phim/ngày (tất cả optional).
     * Kèm số ghế còn trống (không đếm hold chưa hết hạn — Req 8.6).
     */
    public List<Showtime> discover(Long branchId, Long movieId, LocalDate date, int offset, int limit) throws Exception {
        StringBuilder sql = new StringBuilder("""
            SELECT s.id, s.movie_id, s.screen_id, s.branch_id, s.start_time, s.end_time,
                   s.cleaning_buffer_min, s.status, s.version,
                   m.title AS movie_title, sc.name AS screen_name, b.name AS branch_name,
            """).append(AVAILABLE_SEATS_COLUMN).append("""
            FROM dbo.showtime s
            JOIN dbo.movie m ON m.id = s.movie_id
            JOIN dbo.screen sc ON sc.id = s.screen_id
            JOIN dbo.branch b ON b.id = s.branch_id
            WHERE s.status = 'OPEN' AND b.status = 'ACTIVE' AND m.status = 'PUBLISHED'
              AND s.start_time > SYSUTCDATETIME()
            """);
        if (branchId != null) sql.append(" AND s.branch_id = ?");
        if (movieId != null) sql.append(" AND s.movie_id = ?");
        if (date != null) sql.append(" AND CAST(s.start_time AS DATE) = ?");
        sql.append(" ORDER BY s.start_time OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");

        List<Showtime> showtimes = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (branchId != null) ps.setLong(idx++, branchId);
            if (movieId != null) ps.setLong(idx++, movieId);
            if (date != null) ps.setDate(idx++, java.sql.Date.valueOf(date));
            ps.setInt(idx++, offset);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Showtime showtime = mapRow(rs);
                    showtime.setMovieTitle(rs.getString("movie_title"));
                    showtime.setScreenName(rs.getString("screen_name"));
                    showtime.setBranchName(rs.getString("branch_name"));
                    showtime.setAvailableSeats(rs.getInt("available_seats"));
                    showtimes.add(showtime);
                }
            }
        }
        return showtimes;
    }

    /**
     * Paginated search for showtimes in the management table (Admin/Manager).
     * Returns lightweight result with branch/movie/screen info joined.
     */
    public List<Showtime> search(ShowtimeQuery q) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.movie_id, s.screen_id, s.branch_id, s.start_time, s.end_time,
                       s.cleaning_buffer_min, s.status, s.version,
                       m.title AS movie_title, sc.name AS screen_name, b.name AS branch_name,
                """).append(AVAILABLE_SEATS_COLUMN).append("""
                FROM dbo.showtime s
                JOIN dbo.movie m ON m.id = s.movie_id
                JOIN dbo.screen sc ON sc.id = s.screen_id
                JOIN dbo.branch b ON b.id = s.branch_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (q.branchId != null) {
            sql.append(" AND s.branch_id = ?");
            params.add(q.branchId);
        }
        if (q.movieId != null) {
            sql.append(" AND s.movie_id = ?");
            params.add(q.movieId);
        }
        if (q.status != null && !q.status.isBlank()) {
            sql.append(" AND s.status = ?");
            params.add(q.status.trim());
        }
        if (q.date != null) {
            sql.append(" AND CAST(s.start_time AS DATE) = ?");
            params.add(java.sql.Date.valueOf(q.date));
        }
        sql.append(" ORDER BY ").append(whitelistOrderBy(q.sortBy, q.sortDir));
        sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        params.add(q.offset);
        params.add(q.limit);

        List<Showtime> showtimes = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Showtime showtime = mapRow(rs);
                    showtime.setMovieTitle(rs.getString("movie_title"));
                    showtime.setScreenName(rs.getString("screen_name"));
                    showtime.setBranchName(rs.getString("branch_name"));
                    showtime.setAvailableSeats(rs.getInt("available_seats"));
                    showtimes.add(showtime);
                }
            }
        }
        return showtimes;
    }

    /** Count showtimes matching the same filters (ignoring pagination). */
    public long countSearch(ShowtimeQuery q) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dbo.showtime s WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (q.branchId != null) {
            sql.append(" AND s.branch_id = ?");
            params.add(q.branchId);
        }
        if (q.movieId != null) {
            sql.append(" AND s.movie_id = ?");
            params.add(q.movieId);
        }
        if (q.status != null && !q.status.isBlank()) {
            sql.append(" AND s.status = ?");
            params.add(q.status.trim());
        }
        if (q.date != null) {
            sql.append(" AND CAST(s.start_time AS DATE) = ?");
            params.add(java.sql.Date.valueOf(q.date));
        }
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    private static String whitelistOrderBy(String sortBy, String sortDir) {
        String col;
        if (sortBy == null) {
            col = "start_time";
        } else {
            switch (sortBy.trim().toLowerCase()) {
                case "start_time" -> col = "start_time";
                case "end_time" -> col = "end_time";
                case "status" -> col = "status";
                case "id" -> col = "id";
                default -> col = "start_time";
            }
        }
        String dir = (sortDir != null && "asc".equalsIgnoreCase(sortDir.trim())) ? "ASC" : "DESC";
        return col + " " + dir + ", id DESC";
    }

    /** Filter/sort/pagination parameters for showtime listing. */
    public static final class ShowtimeQuery {
        public Long branchId;
        public Long movieId;
        public String status;
        public LocalDate date;
        public String sortBy;
        public String sortDir;
        public int offset;
        public int limit;
    }

    /** Trạng thái ghế của một lịch chiếu (Req 6.3): seatId → status/hold info. */
    public List<ShowtimeSeat> seatStatus(long showtimeId) throws Exception {
        String sql = """
            SELECT ss.seat_id,
                   CASE
                       WHEN ss.hold_id IS NOT NULL
                            AND sh.status = 'ACTIVE'
                            AND ss.hold_expires_at > SYSUTCDATETIME() THEN 'HOLD'
                       WHEN ss.status = 'HOLD'
                            AND (ss.hold_expires_at IS NULL
                                 OR ss.hold_expires_at <= SYSUTCDATETIME()) THEN 'AVAILABLE'
                       ELSE ss.status
                   END AS seat_status,
                   ss.hold_id, ss.hold_expires_at,
                   sh.user_id AS hold_user_id,
                   se.row_label, se.col_no, se.seat_type
            FROM dbo.showtime_seat ss
            JOIN dbo.seat se ON se.id = ss.seat_id AND se.status = 'ACTIVE'
            LEFT JOIN dbo.seat_hold sh ON sh.id = ss.hold_id
            WHERE ss.showtime_id = ?
            ORDER BY se.row_label, se.col_no
            """;
        List<ShowtimeSeat> seats = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp expires = rs.getTimestamp("hold_expires_at");
                    Long holdId = rs.getObject("hold_id") != null ? rs.getLong("hold_id") : null;
                    Long holdUserId = rs.getObject("hold_user_id") != null
                            ? rs.getLong("hold_user_id") : null;
                    seats.add(new ShowtimeSeat(
                            rs.getLong("seat_id"),
                            rs.getString("row_label"),
                            rs.getInt("col_no"),
                            rs.getString("seat_type"),
                            rs.getString("seat_status"),
                            holdId,
                            expires != null ? expires.toLocalDateTime() : null,
                            holdUserId));
                }
            }
        }
        return seats;
    }

    /** Sinh showtime_seat AVAILABLE cho toàn bộ ghế ACTIVE của screen khi tạo lịch. */
    public void initializeSeats(Connection conn, long showtimeId, long screenId) throws Exception {
        String sql = """
            INSERT INTO dbo.showtime_seat (showtime_id, seat_id, status)
            SELECT ?, se.id, 'AVAILABLE' FROM dbo.seat se
            WHERE se.screen_id = ? AND se.status = 'ACTIVE'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            ps.setLong(2, screenId);
            ps.executeUpdate();
        }
    }

    /** Chuyển OPEN → ENDED khi đã qua end_time + buffer (Req 4.6). Trả về số dòng cập nhật. */
    public int endElapsedShowtimes() throws Exception {
        String sql = """
            UPDATE dbo.showtime
            SET status = 'ENDED', version = version + 1
            WHERE status = 'OPEN'
              AND DATEADD(minute, cleaning_buffer_min, end_time) <= SYSUTCDATETIME()
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    /** Cập nhật thời gian lịch chiếu (Req 4.4) với optimistic version. */
    public boolean updateTime(Connection conn, long showtimeId, LocalDateTime newStart,
                              LocalDateTime newEnd, int expectedVersion) throws Exception {
        String sql = """
            UPDATE dbo.showtime
            SET start_time = ?, end_time = ?, version = version + 1
            WHERE id = ? AND version = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(newStart));
            ps.setTimestamp(2, Timestamp.valueOf(newEnd));
            ps.setLong(3, showtimeId);
            ps.setInt(4, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    /** Cập nhật trạng thái (ENDED/CANCELLED) với optimistic version. */
    public boolean updateStatus(long showtimeId, String status, int expectedVersion) throws Exception {
        try (Connection conn = DBContext.getConnection()) {
            return updateStatus(conn, showtimeId, status, expectedVersion);
        }
    }

    public boolean updateStatus(Connection conn, long showtimeId, String status,
                                int expectedVersion) throws Exception {
        String sql = "UPDATE dbo.showtime SET status = ?, version = version + 1 "
                + "WHERE id = ? AND version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, showtimeId);
            ps.setInt(3, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    public boolean restore(Connection conn, long showtimeId, LocalDateTime endTime,
                           int expectedVersion) throws Exception {
        String sql = """
            UPDATE dbo.showtime
            SET status = 'OPEN', end_time = ?, version = version + 1
            WHERE id = ? AND version = ? AND status = 'CANCELLED'
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(endTime));
            ps.setLong(2, showtimeId);
            ps.setInt(3, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    private Showtime mapRow(ResultSet rs) throws Exception {
        Showtime showtime = new Showtime();
        showtime.setId(rs.getLong("id"));
        showtime.setMovieId(rs.getLong("movie_id"));
        showtime.setScreenId(rs.getLong("screen_id"));
        showtime.setBranchId(rs.getLong("branch_id"));
        Timestamp start = rs.getTimestamp("start_time");
        if (start != null) showtime.setStartTime(start.toLocalDateTime());
        Timestamp end = rs.getTimestamp("end_time");
        if (end != null) showtime.setEndTime(end.toLocalDateTime());
        showtime.setCleaningBufferMin(rs.getInt("cleaning_buffer_min"));
        showtime.setStatus(rs.getString("status"));
        showtime.setVersion(rs.getInt("version"));
        return showtime;
    }

    /** Trạng thái một ghế trong lịch chiếu (Req 6.3). */
    public record ShowtimeSeat(long seatId, String rowLabel, int colNo, String seatType,
                               String status, Long holdId, LocalDateTime holdExpiresAt,
                               Long holdUserId) { }
}
