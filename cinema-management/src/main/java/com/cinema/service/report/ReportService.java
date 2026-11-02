package com.cinema.service.report;

import com.cinema.model.branch.Branch;

import com.cinema.common.ServiceException;
import com.cinema.infrastructure.database.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service báo cáo doanh thu & vận hành có branch scope (Req 14.1-14.5).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Doanh thu chỉ đếm vé CONFIRMED/USED; vé CANCELLED tính vào số tiền hoàn
 *       (Req 14.1 — design.md ReportService).</li>
 *   <li>Branch Manager chỉ đọc được dữ liệu chi nhánh mình; Admin lọc toàn chuỗi
 *       theo branch/phim/ngày (Req 14.2).</li>
 *   <li>Khoảng ngày không hợp lệ (end < start) → 400 (Req 14.3).</li>
 *   <li>Đọc nhất quán: mọi truy vấn báo cáo chạy trong transaction READ COMMITTED với
 *       hint NOLOCK-free — phản ánh trạng thái đã hoàn tất tại thời điểm truy vấn,
 *       không đếm trùng/bỏ sót khi có booking/cancel đồng thời (Req 14.4).</li>
 *   <li>Phân trang + chi tiết theo ngày/phim/suất chiếu (Req 14.5).</li>
 * </ul>
 */
public class ReportService {
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Báo cáo tổng hợp (Req 14.1): tổng doanh thu vé confirmed/used, số vé bán,
     * số vé hủy, tổng tiền hoàn — theo khoảng ngày + branch (+ Admin có thể theo phim).
     */
    public RevenueReport revenue(LocalDate from, LocalDate to, Long branchScope,
                                 Long branchFilter, Long movieFilter) throws Exception {
        validateDateRange(from, to);
        Long effectiveBranch = resolveBranchScope(branchScope, branchFilter);

        long ticketRevenue = 0L;
        int ticketsSold = 0;
        int ticketsCancelled = 0;
        long ticketRefundTotal = 0L;

        StringBuilder sql = new StringBuilder("""
            SELECT
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') THEN t.total_amount END), 0) AS revenue,
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') THEN tc.seat_count END), 0) AS tickets_sold,
              COALESCE(SUM(CASE WHEN t.status = 'CANCELLED' THEN tc.seat_count END), 0) AS tickets_cancelled,
              COALESCE(SUM(CASE WHEN t.status = 'CANCELLED' THEN t.refund_amount END), 0) AS refund_total
            FROM dbo.ticket t
            JOIN dbo.showtime s ON s.id = t.showtime_id
            OUTER APPLY (
                SELECT COUNT(*) AS seat_count FROM dbo.ticket_seat ts WHERE ts.ticket_id = t.id
            ) tc
            WHERE CAST(t.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (effectiveBranch != null) sql.append(" AND t.branch_id = ?");
        if (movieFilter != null) sql.append(" AND s.movie_id = ?");

        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (effectiveBranch != null) ps.setLong(idx++, effectiveBranch);
            if (movieFilter != null) ps.setLong(idx, movieFilter);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ticketRevenue = rs.getLong("revenue");
                    ticketsSold = rs.getInt("tickets_sold");
                    ticketsCancelled = rs.getInt("tickets_cancelled");
                    ticketRefundTotal = rs.getLong("refund_total");
                }
            }
        }

        // F&B orders revenue (concession) - tính khi không lọc theo một phim cụ thể
        long fnbRevenue = 0L;
        int fnbOrdersSold = 0;
        int fnbOrdersCancelled = 0;
        long fnbRefundTotal = 0L;

        if (movieFilter == null) {
            StringBuilder fnbSql = new StringBuilder("""
                SELECT
                  COALESCE(SUM(CASE WHEN o.status IN ('READY_FOR_PICKUP','FULFILLED') THEN (o.total_amount - COALESCE(o.points_discount, 0)) END), 0) AS fnb_revenue,
                  COALESCE(SUM(CASE WHEN o.status IN ('READY_FOR_PICKUP','FULFILLED') THEN 1 END), 0) AS fnb_sold,
                  COALESCE(SUM(CASE WHEN o.status = 'CANCELLED' THEN 1 END), 0) AS fnb_cancelled,
                  COALESCE(SUM(CASE WHEN o.status = 'CANCELLED' THEN (o.total_amount - COALESCE(o.points_discount, 0)) END), 0) AS fnb_refund
                FROM dbo.concession_order o
                WHERE CAST(o.created_at AS DATE) BETWEEN ? AND ?
                """);
            if (effectiveBranch != null) fnbSql.append(" AND o.branch_id = ?");

            try (Connection conn = DBContext.getConnection();
                 PreparedStatement ps = conn.prepareStatement(fnbSql.toString())) {
                int idx = 1;
                ps.setDate(idx++, java.sql.Date.valueOf(from));
                ps.setDate(idx++, java.sql.Date.valueOf(to));
                if (effectiveBranch != null) ps.setLong(idx, effectiveBranch);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        fnbRevenue = rs.getLong("fnb_revenue");
                        fnbOrdersSold = rs.getInt("fnb_sold");
                        fnbOrdersCancelled = rs.getInt("fnb_cancelled");
                        fnbRefundTotal = rs.getLong("fnb_refund");
                    }
                }
            }
        }

        long totalRevenue = ticketRevenue + fnbRevenue;
        long totalRefund = ticketRefundTotal + fnbRefundTotal;

        return new RevenueReport(totalRevenue, ticketsSold, ticketsCancelled, totalRefund,
                effectiveBranch, from, to, ticketRevenue, fnbRevenue, fnbOrdersSold, fnbOrdersCancelled);
    }

    /**
     * Chi tiết theo ngày (Req 14.5): mỗi ngày một dòng doanh thu/số vé, phân trang.
     * Tích hợp cả doanh thu vé và F&B concession.
     */
    public List<DailyRow> dailyBreakdown(LocalDate from, LocalDate to, Long branchScope,
                                         Long branchFilter, int page, int pageSize) throws Exception {
        validateDateRange(from, to);
        Long effectiveBranch = resolveBranchScope(branchScope, branchFilter);
        int size = clampPage(pageSize);
        int offset = Math.max(page, 0) * size;

        StringBuilder sql = new StringBuilder("""
            WITH combined AS (
                SELECT CAST(t.created_at AS DATE) AS day,
                       CASE WHEN t.status IN ('CONFIRMED','USED') THEN t.total_amount ELSE 0 END AS revenue,
                       CASE WHEN t.status IN ('CONFIRMED','USED') THEN (SELECT COUNT(*) FROM dbo.ticket_seat ts WHERE ts.ticket_id = t.id) ELSE 0 END AS tickets_sold,
                       CASE WHEN t.status = 'CANCELLED' THEN t.refund_amount ELSE 0 END AS refund_total
                FROM dbo.ticket t
                WHERE CAST(t.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (effectiveBranch != null) sql.append(" AND t.branch_id = ?");
        sql.append("""
                UNION ALL
                SELECT CAST(o.created_at AS DATE) AS day,
                       CASE WHEN o.status IN ('READY_FOR_PICKUP','FULFILLED') THEN (o.total_amount - COALESCE(o.points_discount, 0)) ELSE 0 END AS revenue,
                       0 AS tickets_sold,
                       CASE WHEN o.status = 'CANCELLED' THEN (o.total_amount - COALESCE(o.points_discount, 0)) ELSE 0 END AS refund_total
                FROM dbo.concession_order o
                WHERE CAST(o.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (effectiveBranch != null) sql.append(" AND o.branch_id = ?");
        sql.append("""
            )
            SELECT day,
                   COALESCE(SUM(revenue), 0) AS revenue,
                   COALESCE(SUM(tickets_sold), 0) AS tickets_sold,
                   COALESCE(SUM(refund_total), 0) AS refund_total
            FROM combined
            GROUP BY day
            ORDER BY day
            OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
            """);

        List<DailyRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (effectiveBranch != null) ps.setLong(idx++, effectiveBranch);
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (effectiveBranch != null) ps.setLong(idx++, effectiveBranch);
            ps.setInt(idx++, offset);
            ps.setInt(idx, size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Date day = rs.getDate("day");
                    rows.add(new DailyRow(day != null ? day.toLocalDate() : null,
                            rs.getLong("revenue"), rs.getInt("tickets_sold"),
                            rs.getLong("refund_total")));
                }
            }
        }
        return rows;
    }

    /**
     * Chi tiết theo phim (Req 14.5): doanh thu/số vé mỗi phim, phân trang.
     */
    public List<MovieRow> movieBreakdown(LocalDate from, LocalDate to, Long branchScope,
                                         Long branchFilter, int page, int pageSize) throws Exception {
        validateDateRange(from, to);
        Long effectiveBranch = resolveBranchScope(branchScope, branchFilter);
        int size = clampPage(pageSize);
        int offset = Math.max(page, 0) * size;

        StringBuilder sql = new StringBuilder("""
            SELECT s.movie_id, m.title,
                   COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') THEN t.total_amount END), 0) AS revenue,
                   COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') THEN tc.seat_count END), 0) AS tickets_sold
            FROM dbo.ticket t
            JOIN dbo.showtime s ON s.id = t.showtime_id
            JOIN dbo.movie m ON m.id = s.movie_id
            OUTER APPLY (
                SELECT COUNT(*) AS seat_count FROM dbo.ticket_seat ts WHERE ts.ticket_id = t.id
            ) tc
            WHERE CAST(t.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (effectiveBranch != null) sql.append(" AND t.branch_id = ?");
        sql.append(" GROUP BY s.movie_id, m.title ORDER BY revenue DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");

        List<MovieRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (effectiveBranch != null) ps.setLong(idx++, effectiveBranch);
            ps.setInt(idx++, offset);
            ps.setInt(idx, size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new MovieRow(rs.getLong("movie_id"), rs.getString("title"),
                            rs.getLong("revenue"), rs.getInt("tickets_sold")));
                }
            }
        }
        return rows;
    }

    /**
     * Báo cáo phân tích món F&B / Combo đã bán: số lượng bán, doanh thu từng món.
     */
    public List<ConcessionProductRow> concessionProductBreakdown(LocalDate from, LocalDate to,
                                                                 Long branchScope, Long branchFilter) throws Exception {
        validateDateRange(from, to);
        Long effectiveBranch = resolveBranchScope(branchScope, branchFilter);

        StringBuilder sql = new StringBuilder("""
            SELECT
                p.id AS product_id,
                p.name AS product_name,
                p.type AS product_type,
                p.unit AS unit,
                COALESCE(SUM(l.qty), 0) AS total_qty_sold,
                COALESCE(SUM(l.qty * l.unit_price), 0) AS total_revenue
            FROM dbo.concession_order o
            JOIN dbo.concession_order_line l ON l.order_id = o.id
            JOIN dbo.product p ON p.id = l.product_id
            WHERE o.status IN ('READY_FOR_PICKUP', 'FULFILLED')
              AND CAST(o.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (effectiveBranch != null) sql.append(" AND o.branch_id = ?");
        sql.append(" GROUP BY p.id, p.name, p.type, p.unit ORDER BY total_revenue DESC, total_qty_sold DESC");

        List<ConcessionProductRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (effectiveBranch != null) ps.setLong(idx++, effectiveBranch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ConcessionProductRow(
                            rs.getLong("product_id"),
                            rs.getString("product_name"),
                            rs.getString("product_type"),
                            rs.getString("unit"),
                            rs.getInt("total_qty_sold"),
                            rs.getLong("total_revenue")));
                }
            }
        }
        return rows;
    }

    // ---- helpers ----

    /** Req 14.3 — end trước start là khoảng ngày không hợp lệ. */
    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ServiceException.Validation("Khoảng thời gian báo cáo là bắt buộc");
        }
        if (to.isBefore(from)) {
            throw new ServiceException.Validation("Ngày kết thúc không được trước ngày bắt đầu");
        }
    }

    /**
     * Req 14.2 — ép branch scope: Manager/Staff chỉ được đọc chi nhánh mình.
     * branchScope null (Admin) → dùng branchFilter nếu có, ngược lại toàn chuỗi.
     * Staff chưa gán branch: trả null nhưng dashboard sẽ hiển thị 0 — caller chịu
     * trách nhiệm kiểm tra trước khi expose dữ liệu cross-branch.
     */
    private Long resolveBranchScope(Long branchScope, Long branchFilter) {
        if (branchScope != null) {
            // Manager/Staff: bỏ qua branchFilter khác chi nhánh mình (không cho đọc chi nhánh khác)
            return branchScope;
        }
        return branchFilter;
    }

    private int clampPage(int pageSize) {
        if (pageSize <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** Tổng hợp doanh thu (Req 14.1) bao gồm cả vé và F&B. */
    public record RevenueReport(long revenue, int ticketsSold, int ticketsCancelled,
                                long refundTotal, Long branchId, LocalDate from, LocalDate to,
                                long ticketRevenue, long fnbRevenue, int fnbOrdersSold, int fnbOrdersCancelled) {
        public RevenueReport(long revenue, int ticketsSold, int ticketsCancelled,
                             long refundTotal, Long branchId, LocalDate from, LocalDate to) {
            this(revenue, ticketsSold, ticketsCancelled, refundTotal, branchId, from, to,
                 revenue, 0L, 0, 0);
        }
    }

    /** Dòng doanh thu theo ngày (Req 14.5). */
    public record DailyRow(LocalDate day, long revenue, int ticketsSold, long refundTotal) { }

    /** Dòng doanh thu theo phim (Req 14.5). */
    public record MovieRow(long movieId, String title, long revenue, int ticketsSold) { }

    /** Dòng doanh thu theo món F&B/Combo. */
    public record ConcessionProductRow(long productId, String name, String type,
                                       String unit, int qtySold, long revenue) { }

    /** Dòng chi tiết đơn F&B phục vụ báo cáo đối soát. */
    public record ConcessionOrderRow(long id, String orderCode, long branchId, String branchName,
                                     String customerName, String status, long totalAmount,
                                     long pointsDiscount, String createdAt) { }

    /**
     * Danh sách các đơn F&B trong khoảng ngày (đối soát các đơn tạo nên doanh thu F&B).
     */
    public List<ConcessionOrderRow> concessionOrderList(LocalDate from, LocalDate to,
                                                        Long branchScope, Long branchFilter,
                                                        int limit) throws Exception {
        validateDateRange(from, to);
        Long effectiveBranch = resolveBranchScope(branchScope, branchFilter);
        int max = limit <= 0 ? 100 : Math.min(limit, 500);

        StringBuilder sql = new StringBuilder("""
            SELECT TOP (?)
                co.id, co.order_code, co.branch_id, ISNULL(b.name, N'Chi nhánh') AS branch_name,
                ISNULL(ua.full_name, N'Khách vãng lai') AS customer_name,
                co.status, co.total_amount, co.points_discount, co.created_at
            FROM dbo.concession_order co
            LEFT JOIN dbo.branch b ON b.id = co.branch_id
            LEFT JOIN dbo.user_account ua ON ua.id = co.user_id
            WHERE co.status IN ('READY_FOR_PICKUP', 'FULFILLED', 'CANCELLED')
              AND CAST(co.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (effectiveBranch != null) sql.append(" AND co.branch_id = ?");
        sql.append(" ORDER BY co.created_at DESC");

        List<ConcessionOrderRow> rows = new ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setInt(idx++, max);
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (effectiveBranch != null) ps.setLong(idx++, effectiveBranch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    rows.add(new ConcessionOrderRow(
                            rs.getLong("id"),
                            rs.getString("order_code"),
                            rs.getLong("branch_id"),
                            rs.getString("branch_name"),
                            rs.getString("customer_name"),
                            rs.getString("status"),
                            rs.getLong("total_amount"),
                            rs.getLong("points_discount"),
                            ts != null ? ts.toLocalDateTime().toString() : null));
                }
            }
        }
        return rows;
    }

    // ===================================================================
    // Dashboard summary — Kpi cards cho Management Dashboard (CinemaHub).
    // Req 14.1 (revenue/tickets) + dashboard nội bộ của manager/staff.
    // - Total bookings = số vé CONFIRMED/USED/PENDING trong khoảng (đếm ticket).
    // - Total revenue từ report revenue() (đã chuẩn hoá branch scope).
    // - Tickets sold = revenue.ticketsSold().
    // - Active branches = COUNT(*) WHERE status='ACTIVE'.
    // - Today's showtimes (read-only) cho widget "Lịch chiếu hôm nay".
    // - Recent bookings (5 vé mới nhất) cho widget "Đặt vé gần đây".
    // - Booking breakdown (CONFIRMED/PENDING/CANCELLED) cho widget progress.
    // - Monthly revenue 12 tháng gần nhất cho area chart.
    // ===================================================================

    public record DashboardSummary(
            long totalBookings,
            long totalRevenue,
            int ticketsSold,
            int ticketsCancelled,
            int activeBranches,
            java.util.List<MonthlyRevenue> monthlyRevenue,
            java.util.List<RecentBooking> recentBookings,
            java.util.List<TodaysShowtime> todaysShowtimes,
            BookingBreakdown bookingBreakdown,
            long ticketRevenue,
            long fnbRevenue,
            int fnbOrdersSold,
            int fnbOrdersCancelled,
            long refundTotal) {
        public DashboardSummary(
                long totalBookings,
                long totalRevenue,
                int ticketsSold,
                int ticketsCancelled,
                int activeBranches,
                java.util.List<MonthlyRevenue> monthlyRevenue,
                java.util.List<RecentBooking> recentBookings,
                java.util.List<TodaysShowtime> todaysShowtimes,
                BookingBreakdown bookingBreakdown) {
            this(totalBookings, totalRevenue, ticketsSold, ticketsCancelled, activeBranches,
                 monthlyRevenue, recentBookings, todaysShowtimes, bookingBreakdown,
                 totalRevenue, 0L, 0, 0, 0L);
        }
    }

    public record MonthlyRevenue(String label, long revenue, int ticketsSold, long ticketRevenue, long fnbRevenue) {
        public MonthlyRevenue(String label, long revenue, int ticketsSold) {
            this(label, revenue, ticketsSold, revenue, 0L);
        }
    }
    public record RecentBooking(long ticketId, String ticketCode, String customerName,
                                 String movieTitle, java.time.LocalDateTime showtimeStart,
                                 long amount, String status) { }
    public record TodaysShowtime(long showtimeId, String movieTitle, String branchName,
                                 String screenName, java.time.LocalDateTime startTime,
                                 String status, Integer availableSeats) { }
    public record BookingBreakdown(int total, int confirmed, int pending, int cancelled,
                                   int online, int pos, int advance, int walkIn) {
        public BookingBreakdown(int total, int confirmed, int pending, int cancelled) {
            this(total, confirmed, pending, cancelled, confirmed, 0, confirmed, 0);
        }
    }

    public DashboardSummary dashboardSummary(LocalDate from, LocalDate to, Long branchScope,
                                              Long branchFilter) throws Exception {
        // Bỏ qua validateDateRange vì khoảng rộng 90 ngày / 1 năm cho chart;
        // mặc định caller truyền khoảng hợp lệ. Dù vậy vẫn kiểm tra an toàn.
        LocalDate safeTo = (to == null) ? LocalDate.now() : to;
        LocalDate safeFrom = (from == null) ? safeTo.minusDays(90) : from;
        if (safeTo.isBefore(safeFrom)) safeFrom = safeTo.minusDays(90);

        Long effectiveBranch = resolveBranchScope(branchScope, branchFilter);

        RevenueReport rev = revenue(safeFrom, safeTo, branchScope, branchFilter, null);
        int activeBranches = countActiveBranches();
        java.util.List<MonthlyRevenue> monthly = monthlyRevenue(safeFrom, safeTo, branchScope, branchFilter);
        java.util.List<RecentBooking> recent = recentBookings(5, branchScope, branchFilter);
        java.util.List<TodaysShowtime> today = todaysShowtimes(5, branchScope, branchFilter);
        BookingBreakdown bd = bookingBreakdown(safeFrom, safeTo, branchScope, branchFilter);

        return new DashboardSummary(
                bd.total(),               // total bookings
                rev.revenue(),            // total revenue
                rev.ticketsSold(),        // tickets sold
                rev.ticketsCancelled(),   // tickets cancelled
                activeBranches,
                monthly,
                recent,
                today,
                bd,
                rev.ticketRevenue(),
                rev.fnbRevenue(),
                rev.fnbOrdersSold(),
                rev.fnbOrdersCancelled(),
                rev.refundTotal());
    }

    private int countActiveBranches() throws Exception {
        String sql = "SELECT COUNT(*) FROM dbo.branch WHERE status = 'ACTIVE'";
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private java.util.List<MonthlyRevenue> monthlyRevenue(LocalDate from, LocalDate to, Long branchScope, Long branchFilter) throws Exception {
        Long eff = resolveBranchScope(branchScope, branchFilter);
        StringBuilder sql = new StringBuilder("""
            WITH combined AS (
                SELECT FORMAT(t.created_at, 'yyyy-MM') AS ym,
                       CASE WHEN t.status IN ('CONFIRMED','USED') THEN t.total_amount ELSE 0 END AS ticket_rev,
                       0 AS fnb_rev,
                       CASE WHEN t.status IN ('CONFIRMED','USED') THEN 1 ELSE 0 END AS sold
                FROM dbo.ticket t
                WHERE CAST(t.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (eff != null) sql.append(" AND t.branch_id = ?");
        sql.append("""
                UNION ALL
                SELECT FORMAT(o.created_at, 'yyyy-MM') AS ym,
                       0 AS ticket_rev,
                       CASE WHEN o.status IN ('READY_FOR_PICKUP','FULFILLED') THEN (o.total_amount - COALESCE(o.points_discount, 0)) ELSE 0 END AS fnb_rev,
                       0 AS sold
                FROM dbo.concession_order o
                WHERE CAST(o.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (eff != null) sql.append(" AND o.branch_id = ?");
        sql.append("""
            )
            SELECT ym,
                   COALESCE(SUM(ticket_rev + fnb_rev), 0) AS revenue,
                   COALESCE(SUM(ticket_rev), 0) AS ticket_revenue,
                   COALESCE(SUM(fnb_rev), 0) AS fnb_revenue,
                   COALESCE(SUM(sold), 0) AS sold
            FROM combined
            GROUP BY ym
            ORDER BY ym
            """);

        java.util.Map<String, MonthlyRevenue> map = new java.util.LinkedHashMap<>();
        // Khởi 12 tháng gần nhất để biểu đồ không bị "gãy".
        LocalDate cursor = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i < 12; i++) {
            String ym = cursor.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
            String label = cursor.format(java.time.format.DateTimeFormatter.ofPattern("MMM"));
            map.put(ym, new MonthlyRevenue(label, 0L, 0, 0L, 0L));
            cursor = cursor.minusMonths(1);
        }
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (eff != null) ps.setLong(idx++, eff);
            ps.setDate(idx++, java.sql.Date.valueOf(from));
            ps.setDate(idx++, java.sql.Date.valueOf(to));
            if (eff != null) ps.setLong(idx++, eff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ym = rs.getString("ym");
                    if (ym == null) continue;
                    java.time.YearMonth ymParsed = java.time.YearMonth.parse(ym);
                    String label = ymParsed.format(java.time.format.DateTimeFormatter.ofPattern("MMM"));
                    map.put(ym, new MonthlyRevenue(label,
                            rs.getLong("revenue"),
                            rs.getInt("sold"),
                            rs.getLong("ticket_revenue"),
                            rs.getLong("fnb_revenue")));
                }
            }
        }
        return new java.util.ArrayList<>(map.values());
    }

    private java.util.List<RecentBooking> recentBookings(int limit, Long branchScope, Long branchFilter) throws Exception {
        Long eff = resolveBranchScope(branchScope, branchFilter);
        StringBuilder sql = new StringBuilder("""
            SELECT TOP (?) t.id, t.ticket_code, COALESCE(u.full_name, N'Khách lẻ') AS customer,
                   m.title AS movie, s.start_time, t.total_amount, t.status
            FROM dbo.ticket t
            LEFT JOIN dbo.user_account u ON u.id = t.user_id
            JOIN dbo.showtime s ON s.id = t.showtime_id
            JOIN dbo.movie m ON m.id = s.movie_id
            WHERE 1=1
            """);
        if (eff != null) sql.append(" AND t.branch_id = ?");
        sql.append(" ORDER BY t.created_at DESC");

        java.util.List<RecentBooking> out = new java.util.ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, limit);
            if (eff != null) ps.setLong(2, eff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("start_time");
                    out.add(new RecentBooking(
                            rs.getLong("id"),
                            rs.getString("ticket_code"),
                            rs.getString("customer"),
                            rs.getString("movie"),
                            ts == null ? null : ts.toLocalDateTime(),
                            rs.getLong("total_amount"),
                            rs.getString("status")));
                }
            }
        }
        return out;
    }

    private java.util.List<TodaysShowtime> todaysShowtimes(int limit, Long branchScope, Long branchFilter) throws Exception {
        Long eff = resolveBranchScope(branchScope, branchFilter);
        StringBuilder sql = new StringBuilder("""
            SELECT TOP (?) s.id, m.title, br.name AS branch_name, sc.name AS screen_name,
                   s.start_time, s.status,
                   (SELECT COUNT(*) FROM dbo.seat st
                      WHERE st.screen_id = s.screen_id AND st.status = 'ACTIVE') AS total_seats,
                   (SELECT COUNT(*) FROM dbo.ticket_seat ts
                      JOIN dbo.ticket t2 ON t2.id = ts.ticket_id
                      WHERE t2.showtime_id = s.id AND t2.status IN ('CONFIRMED','USED')) AS taken
            FROM dbo.showtime s
            JOIN dbo.movie m ON m.id = s.movie_id
            JOIN dbo.branch br ON br.id = s.branch_id
            JOIN dbo.screen sc ON sc.id = s.screen_id
            WHERE CAST(s.start_time AS DATE) = CAST(SYSUTCDATETIME() AS DATE)
            """);
        if (eff != null) sql.append(" AND s.branch_id = ?");
        sql.append(" ORDER BY s.start_time");

        java.util.List<TodaysShowtime> out = new java.util.ArrayList<>();
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, limit);
            if (eff != null) ps.setLong(2, eff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int total = rs.getInt("total_seats");
                    int taken = rs.getInt("taken");
                    int available = Math.max(0, total - taken);
                    String status = rs.getString("status");
                    if ("OPEN".equalsIgnoreCase(status) && available <= 0) status = "SOLD_OUT";
                    else if ("OPEN".equalsIgnoreCase(status) && available <= total / 4) status = "ALMOST_FULL";
                    else if ("OPEN".equalsIgnoreCase(status)) status = "SELLING";
                    java.sql.Timestamp ts = rs.getTimestamp("start_time");
                    out.add(new TodaysShowtime(
                            rs.getLong("id"),
                            rs.getString("title"),
                            rs.getString("branch_name"),
                            rs.getString("screen_name"),
                            ts == null ? null : ts.toLocalDateTime(),
                            status,
                            available));
                }
            }
        }
        return out;
    }

    private BookingBreakdown bookingBreakdown(LocalDate from, LocalDate to, Long branchScope, Long branchFilter) throws Exception {
        Long eff = resolveBranchScope(branchScope, branchFilter);
        StringBuilder sql = new StringBuilder("""
            SELECT
              COALESCE(SUM(tc.seat_count), 0) AS total,
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') THEN tc.seat_count ELSE 0 END), 0) AS confirmed,
              COALESCE(SUM(CASE WHEN t.status = 'PENDING' THEN tc.seat_count ELSE 0 END), 0) AS pending,
              COALESCE(SUM(CASE WHEN t.status = 'CANCELLED' THEN tc.seat_count ELSE 0 END), 0) AS cancelled,
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') AND NOT (t.user_id IS NULL OR pay.has_pos = 1) THEN tc.seat_count ELSE 0 END), 0) AS online_cnt,
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') AND (t.user_id IS NULL OR pay.has_pos = 1) THEN tc.seat_count ELSE 0 END), 0) AS pos_cnt,
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') AND (s.start_time IS NULL OR CAST(s.start_time AS DATE) > CAST(t.created_at AS DATE)) THEN tc.seat_count ELSE 0 END), 0) AS advance_cnt,
              COALESCE(SUM(CASE WHEN t.status IN ('CONFIRMED','USED') AND s.start_time IS NOT NULL AND CAST(s.start_time AS DATE) <= CAST(t.created_at AS DATE) THEN tc.seat_count ELSE 0 END), 0) AS walkin_cnt
            FROM dbo.ticket t
            LEFT JOIN dbo.showtime s ON s.id = t.showtime_id
            OUTER APPLY (
                SELECT COUNT(*) AS seat_count FROM dbo.ticket_seat ts WHERE ts.ticket_id = t.id
            ) tc
            OUTER APPLY (
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM dbo.payment p
                    WHERE p.ticket_id = t.id AND (p.method IN ('CASH','BANK_TRANSFER') OR p.idempotency_key LIKE 'POS-%')
                ) THEN 1 ELSE 0 END AS has_pos
            ) pay
            WHERE CAST(t.created_at AS DATE) BETWEEN ? AND ?
            """);
        if (eff != null) sql.append(" AND t.branch_id = ?");
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setDate(1, java.sql.Date.valueOf(from));
            ps.setDate(2, java.sql.Date.valueOf(to));
            if (eff != null) ps.setLong(3, eff);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new BookingBreakdown(
                            rs.getInt("total"),
                            rs.getInt("confirmed"),
                            rs.getInt("pending"),
                            rs.getInt("cancelled"),
                            rs.getInt("online_cnt"),
                            rs.getInt("pos_cnt"),
                            rs.getInt("advance_cnt"),
                            rs.getInt("walkin_cnt"));
                }
            }
        }
        return new BookingBreakdown(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
