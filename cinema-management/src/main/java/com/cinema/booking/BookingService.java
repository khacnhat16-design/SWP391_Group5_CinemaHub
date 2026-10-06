package com.cinema.booking;

import com.cinema.common.ServiceException;
import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service Đặt vé — Phụ trách bởi Người 4 (Nhất).
 * Chức năng 2: Giữ ghế 10 phút và chống tranh chấp đồng thời (Concurrency Control).
 * Chức năng 3: Snapshot giá vé, tạo vé PENDING, xác nhận đặt vé (CONFIRMED) và tra cứu vé cá nhân.
 * Chức năng 4: Hủy vé theo chính sách hoàn tiền bậc thang (Tiered Refund Policy) và Soát vé Check-in.
 */
public class BookingService {
    private static final Logger logger = Logger.getLogger(BookingService.class.getName());
    public static final ZoneId BUSINESS_TZ = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SeatHoldDAO seatHoldDao;
    private final TicketDAO ticketDao;

    public BookingService() {
        this(new SeatHoldDAO(), new TicketDAO());
    }

    public BookingService(SeatHoldDAO seatHoldDao, TicketDAO ticketDao) {
        this.seatHoldDao = seatHoldDao;
        this.ticketDao = ticketDao;
    }

    /**
     * Báo giá trước khi giữ ghế (Snapshot Pricing Quote).
     */
    public Map<String, Object> quoteOrder(long showtimeId, List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return Map.of("baseTotal", 0L, "tierDiscount", 0L, "voucherDiscount", 0L, "payable", 0L);
        }

        long total = 0;
        try (Connection conn = DBContext.getConnection()) {
            for (Long seatId : seatIds) {
                String seatType = getSeatType(conn, seatId);
                total += calculateSeatPrice(seatType);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Không lấy được giá chuẩn, sử dụng giá mặc định", e);
            total = seatIds.size() * 75_000L;
        }

        Map<String, Object> quote = new HashMap<>();
        quote.put("baseTotal", total);
        quote.put("tierDiscount", 0L);
        quote.put("voucherDiscount", 0L);
        quote.put("payable", total);
        return quote;
    }

    /**
     * Chức năng 2: Giữ ghế 10 phút (Seat Hold Concurrency Control).
     */
    public HoldResult holdSeats(long showtimeId, List<Long> seatIds, Long userId) {
        if (seatIds == null || seatIds.isEmpty()) {
            return HoldResult.rejected("Vui lòng chọn ít nhất một ghế");
        }

        List<Long> orderedSeatIds = new ArrayList<>(new TreeSet<>(seatIds));

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<SeatHoldDAO.SeatRow> lockedSeats = new ArrayList<>();

                for (Long seatId : orderedSeatIds) {
                    SeatHoldDAO.SeatRow row = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                    if (row == null) {
                        conn.rollback();
                        return HoldResult.rejected("Ghế không tồn tại hoặc không thuộc suất chiếu này");
                    }

                    boolean isExpiredHold = row.holdExpiresAt != null &&
                            LocalDateTime.now(ZoneOffset.UTC).isAfter(row.holdExpiresAt);

                    if (!"AVAILABLE".equalsIgnoreCase(row.status) && !isExpiredHold) {
                        conn.rollback();
                        return HoldResult.rejected("Ghế " + row.rowLabel + row.colNo + " không còn trống");
                    }
                    lockedSeats.add(row);
                }

                long holdId = seatHoldDao.insertHold(conn, showtimeId, userId);
                LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(SeatHoldDAO.HOLD_MINUTES);

                for (SeatHoldDAO.SeatRow row : lockedSeats) {
                    seatHoldDao.markSeatHeld(conn, showtimeId, row.seatId, holdId, expiresAt);
                }

                long branchId = queryBranchId(conn, showtimeId);
                long totalAmount = 0;
                List<Ticket.TicketSeat> snapshotSeats = new ArrayList<>();

                for (SeatHoldDAO.SeatRow row : lockedSeats) {
                    long price = calculateSeatPrice(row.seatType);
                    totalAmount += price;
                    snapshotSeats.add(new Ticket.TicketSeat(
                            row.seatId, row.rowLabel, row.colNo, row.seatType, "ADULT", price
                    ));
                }

                Ticket ticket = new Ticket();
                ticket.setTicketCode(generateTicketCode());
                ticket.setShowtimeId(showtimeId);
                ticket.setBranchId(branchId);
                ticket.setUserId(userId);
                ticket.setStatus(Ticket.STATUS_PENDING);
                ticket.setTotalAmount(totalAmount);
                ticket.setHoldId(holdId);
                ticket.setCreatedAt(LocalDateTime.now());
                ticket.setSeats(snapshotSeats);

                long ticketId = ticketDao.insert(conn, ticket);
                ticket.setId(ticketId);
                ticketDao.insertSeats(conn, ticketId, snapshotSeats);

                conn.commit();
                logger.info("Giữ thành công " + orderedSeatIds.size() + " ghế (holdId=" + holdId + ")");
                return HoldResult.accepted(String.valueOf(holdId));

            } catch (Exception ex) {
                conn.rollback();
                logger.log(Level.SEVERE, "Lỗi transaction khi giữ ghế", ex);
                return HoldResult.rejected("Lỗi hệ thống khi xử lý giữ ghế");
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Lỗi kết nối cơ sở dữ liệu khi giữ ghế", e);
            return HoldResult.rejected("Lỗi kết nối cơ sở dữ liệu");
        }
    }

    /**
     * Chức năng 3: Xác nhận đặt vé (PENDING -> CONFIRMED).
     */
    public Ticket confirmBooking(long holdId, Long actorUserId) throws Exception {
        sweepExpiredHolds();

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                SeatHoldDAO.HoldRow hold = seatHoldDao.lockHold(conn, holdId);
                if (hold == null) {
                    throw new ServiceException.NotFound("Không tìm thấy thông tin giữ ghế");
                }

                if (actorUserId == null || hold.userId == null || !actorUserId.equals(hold.userId)) {
                    throw new ServiceException.Forbidden("Bạn không có quyền xác nhận phiên đặt vé này");
                }

                if (!"ACTIVE".equalsIgnoreCase(hold.status) || 
                        (hold.expiresAt != null && LocalDateTime.now(ZoneOffset.UTC).isAfter(hold.expiresAt))) {
                    throw new ServiceException.Conflict("Thời hạn giữ ghế 10 phút đã hết hạn, vui lòng chọn lại ghế");
                }

                Ticket ticket = ticketDao.findByHoldIdLocked(conn, holdId)
                        .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy thông tin vé tương ứng"));

                if (!Ticket.STATUS_PENDING.equalsIgnoreCase(ticket.getStatus())) {
                    throw new ServiceException.Conflict("Vé đã được xử lý hoặc không ở trạng thái chờ xác nhận");
                }

                boolean ticketUpdated = ticketDao.confirmGuarded(
                        conn, ticket.getId(), ticket.getTotalAmount(), ticket.getVoucherCode(), 0
                );
                if (!ticketUpdated) {
                    throw new ServiceException.Conflict("Không thể xác nhận vé (trạng thái vé đã bị thay đổi)");
                }

                List<Long> seatIds = seatHoldDao.findSeatIdsOfHold(conn, holdId);
                if (seatIds.isEmpty()) {
                    throw new ServiceException.Conflict("Phiên giữ ghế không còn ghế hợp lệ");
                }
                for (Long seatId : seatIds) {
                    seatHoldDao.markSeatSold(conn, hold.showtimeId, seatId);
                }

                seatHoldDao.updateHoldStatus(conn, holdId, "CONFIRMED", "ACTIVE");

                List<Ticket.TicketSeat> seats = ticketDao.findSeats(conn, ticket.getId());
                ticket.setSeats(seats);
                ticket.setStatus(Ticket.STATUS_CONFIRMED);
                ticket.setConfirmedAt(LocalDateTime.now());

                conn.commit();
                logger.info("Xác nhận thành công vé " + ticket.getTicketCode() + " (holdId=" + holdId + ")");
                return ticket;

            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /**
     * Chức năng 4: Hủy vé kèm chính sách hoàn tiền bậc thang (Tiered Refund Policy).
     * >= 24h: hoàn 100%
     * 2h - 24h: hoàn 50%
     * < 2h: hoàn 0%
     */
    public Ticket cancelTicket(long ticketId, Long actorCustomerId, Long actorStaffBranchId) throws Exception {
        sweepExpiredHolds();

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Ticket ticket = ticketDao.findByIdLocked(conn, ticketId)
                        .orElseThrow(() -> new ServiceException.NotFound("Vé không tồn tại"));

                if (actorStaffBranchId != null) {
                    if (ticket.getBranchId() != actorStaffBranchId) {
                        throw new ServiceException.Forbidden("Vi phạm phạm vi chi nhánh: vé thuộc chi nhánh khác");
                    }
                } else if (actorCustomerId != null) {
                    if (ticket.getUserId() == null || !actorCustomerId.equals(ticket.getUserId())) {
                        throw new ServiceException.Forbidden("Chỉ chủ vé mới được hủy vé");
                    }
                } else {
                    throw new ServiceException.Forbidden("Không có quyền hủy vé");
                }

                if (Ticket.STATUS_PENDING.equals(ticket.getStatus())) {
                    throw new ServiceException.BusinessRule("TICKET_NOT_CONFIRMED",
                            "Vé chưa thanh toán — không thể hủy theo chính sách hoàn tiền");
                }
                if (Ticket.STATUS_USED.equals(ticket.getStatus())) {
                    throw new ServiceException.BusinessRule("TICKET_USED",
                            "Vé đã được soát — không thể hủy hoặc hoàn tiền");
                }
                if (Ticket.STATUS_CANCELLED.equals(ticket.getStatus())) {
                    throw new ServiceException.Conflict("Vé đã bị hủy trước đó");
                }

                LocalDateTime showtimeStart = queryShowtimeStartTime(conn, ticket.getShowtimeId());
                long refundRate = refundRatePercent(Instant.now(), showtimeStart);
                long refundAmount = ticket.getTotalAmount() * refundRate / 100;

                List<Ticket.TicketSeat> ticketSeats = ticketDao.findSeats(conn, ticketId);
                if (ticketSeats.isEmpty()) {
                    throw new ServiceException.Conflict("Vé không có ghế để giải phóng");
                }

                if (!ticketDao.cancelGuarded(conn, ticketId, refundAmount)) {
                    throw new ServiceException.Conflict("Vé vừa được xử lý bởi một yêu cầu khác");
                }

                for (Ticket.TicketSeat seat : ticketSeats) {
                    seatHoldDao.releaseSeat(conn, ticket.getShowtimeId(), seat.seatId());
                }

                conn.commit();
                logger.info("Cancelled ticket " + ticketId + " refund " + refundAmount + " (" + refundRate + "%)");

                ticket.setStatus(Ticket.STATUS_CANCELLED);
                ticket.setRefundAmount(refundAmount);
                ticket.setSeats(ticketSeats);
                return ticket;

            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /**
     * Chức năng 4: Soát vé (Check-in) một lần tại đúng chi nhánh.
     * Concurrency guarded row lock: 2 nhân viên quét cùng lúc chỉ 1 người thành công.
     */
    public Ticket validateTicket(String ticketCode, long validatorStaffId, long validatorBranchId) throws Exception {
        if (ticketCode == null || ticketCode.isBlank()) {
            throw new ServiceException.Validation("Mã vé là bắt buộc");
        }
        if (validatorBranchId <= 0) {
            throw new ServiceException.Forbidden("Tài khoản chưa được gán chi nhánh");
        }
        sweepExpiredHolds();

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Ticket ticket = ticketDao.findByCodeLocked(conn, ticketCode.trim().toUpperCase())
                        .orElseThrow(() -> new ServiceException.NotFound("Mã vé không tồn tại"));

                if (ticket.getBranchId() != validatorBranchId) {
                    throw new ServiceException.Forbidden("Vé thuộc chi nhánh khác — không thể soát tại chi nhánh này");
                }

                switch (ticket.getStatus()) {
                    case Ticket.STATUS_PENDING -> throw new ServiceException.BusinessRule(
                            "TICKET_PENDING", "Vé chưa thanh toán — không thể soát vé");
                    case Ticket.STATUS_CANCELLED -> throw new ServiceException.BusinessRule(
                            "TICKET_CANCELLED", "Vé đã bị hủy — không thể soát vé");
                    case Ticket.STATUS_USED -> throw new ServiceException.Conflict("Vé đã được sử dụng trước đó");
                    default -> { /* CONFIRMED */ }
                }

                ShowtimeInfo showtime = queryShowtimeInfo(conn, ticket.getShowtimeId());
                if (showtime != null) {
                    if ("CANCELLED".equalsIgnoreCase(showtime.status)) {
                        throw new ServiceException.BusinessRule("SHOWTIME_CANCELLED",
                                "Suất chiếu đã bị hủy — không thể soát vé");
                    }
                    LocalDateTime now = LocalDateTime.now(BUSINESS_TZ);
                    if (showtime.endTime != null && now.isAfter(showtime.endTime)) {
                        throw new ServiceException.BusinessRule("SHOWTIME_ENDED",
                                "Suất chiếu đã kết thúc — không thể soát vé");
                    }
                    if (showtime.startTime != null && isTooEarlyForCheckIn(now, showtime.startTime)) {
                        throw new ServiceException.BusinessRule("TOO_EARLY",
                                "Suất chiếu chưa bắt đầu — chỉ soát trước giờ chiếu tối đa 30 phút");
                    }
                }

                if (!ticketDao.useGuarded(conn, ticket.getId(), validatorStaffId)) {
                    throw new ServiceException.Conflict("Vé vừa được soát bởi một yêu cầu khác");
                }

                conn.commit();
                logger.info("Ticket " + ticket.getTicketCode() + " validated by staff " + validatorStaffId);

                ticket.setStatus(Ticket.STATUS_USED);
                ticket.setUsedAt(LocalDateTime.now());
                ticket.setUsedBy(validatorStaffId);
                ticket.setSeats(ticketDao.findSeats(conn, ticket.getId()));
                return ticket;

            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /**
     * Chức năng 4: Tra cứu thông tin vé chi tiết phục vụ màn hình Check-in / Soát vé.
     */
    public Map<String, Object> lookupTicket(String ticketCode, Long validatorBranchId) throws Exception {
        if (ticketCode == null || ticketCode.isBlank()) {
            throw new ServiceException.Validation("Mã vé là bắt buộc");
        }
        String sql = """
            SELECT TOP 1 t.id, t.ticket_code, COALESCE(u.full_name, N'Khách lẻ') AS customer_name,
                   u.email AS customer_email, m.title AS movie_title,
                   sc.name AS screen_name, s.start_time AS showtime_start, s.end_time AS showtime_end,
                   b.name AS branch_name, t.branch_id, t.total_amount, t.refund_amount, t.status,
                   t.created_at, t.confirmed_at, t.used_at, t.used_by
            FROM dbo.ticket t
            LEFT JOIN dbo.user_account u ON u.id = t.user_id
            JOIN dbo.showtime s ON s.id = t.showtime_id
            JOIN dbo.movie m ON m.id = s.movie_id
            JOIN dbo.screen sc ON sc.id = s.screen_id
            JOIN dbo.branch b ON b.id = t.branch_id
            WHERE t.ticket_code = ?
            """;
        try (Connection conn = DBContext.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ticketCode.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ServiceException.NotFound("Không tìm thấy thông tin vé với mã: " + ticketCode);
                }
                long branchId = rs.getLong("branch_id");
                if (validatorBranchId != null && validatorBranchId > 0 && branchId != validatorBranchId) {
                    throw new ServiceException.Forbidden("Vé thuộc chi nhánh khác — không thể tra cứu tại chi nhánh này");
                }
                Map<String, Object> map = new HashMap<>();
                map.put("ticketId", rs.getLong("id"));
                map.put("ticketCode", rs.getString("ticket_code"));
                map.put("customerName", rs.getString("customer_name"));
                map.put("customerEmail", rs.getString("customer_email"));
                map.put("movieTitle", rs.getString("movie_title"));
                map.put("screenName", rs.getString("screen_name"));
                map.put("showtimeStart", rs.getTimestamp("showtime_start"));
                map.put("showtimeEnd", rs.getTimestamp("showtime_end"));
                map.put("branchName", rs.getString("branch_name"));
                map.put("branchId", branchId);
                map.put("totalAmount", rs.getLong("total_amount"));
                map.put("refundAmount", rs.getObject("refund_amount"));
                map.put("status", rs.getString("status"));
                map.put("createdAt", rs.getTimestamp("created_at"));
                map.put("confirmedAt", rs.getTimestamp("confirmed_at"));
                map.put("usedAt", rs.getTimestamp("used_at"));
                map.put("usedBy", rs.getObject("used_by"));

                List<Ticket.TicketSeat> seats = ticketDao.findSeats(conn, rs.getLong("id"));
                List<Map<String, Object>> seatList = new ArrayList<>();
                for (Ticket.TicketSeat s : seats) {
                    seatList.add(Map.of(
                            "seatId", s.seatId(),
                            "label", s.rowLabel() + s.colNo(),
                            "seatType", s.seatType() != null ? s.seatType() : "STANDARD",
                            "price", s.price()
                    ));
                }
                map.put("seats", seatList);
                return map;
            }
        }
    }

    /**
     * Tra cứu danh sách vé đã mua của khách hàng (Chức năng 3).
     */
    public List<Ticket> listMyTickets(Long userId) throws SQLException {
        if (userId == null) return Collections.emptyList();

        try (Connection conn = DBContext.getConnection()) {
            List<Ticket> tickets = ticketDao.findByUser(userId);
            if (tickets.isEmpty()) return tickets;

            List<Long> ticketIds = tickets.stream().map(Ticket::getId).toList();
            Map<Long, List<Ticket.TicketSeat>> seatsByTicket = ticketDao.findSeatsByTickets(conn, ticketIds);

            for (Ticket t : tickets) {
                t.setSeats(seatsByTicket.getOrDefault(t.getId(), Collections.emptyList()));
            }
            return tickets;
        }
    }

    /**
     * Tra cứu vé theo mã vé (Ticket Code).
     */
    public Ticket getTicketByCode(String ticketCode, Long actorUserId) throws Exception {
        if (ticketCode == null || ticketCode.isBlank()) {
            throw new ServiceException.Validation("Mã vé không được để trống");
        }

        try (Connection conn = DBContext.getConnection()) {
            Ticket ticket = ticketDao.findByCodeLocked(conn, ticketCode.trim())
                    .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy vé với mã: " + ticketCode));

            if (actorUserId != null && ticket.getUserId() != null && !actorUserId.equals(ticket.getUserId())) {
                throw new ServiceException.Forbidden("Bạn không có quyền xem vé này");
            }

            ticket.setSeats(ticketDao.findSeats(conn, ticket.getId()));
            return ticket;
        }
    }

    /**
     * Giải phóng ghế khi khách hàng bỏ chọn hoặc chủ động hủy giữ (Chức năng 2).
     */
    public boolean releaseHold(long showtimeId, List<Long> seatIds, Long userId) {
        if (seatIds == null || seatIds.isEmpty())
            return false;

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (Long seatId : seatIds) {
                    seatHoldDao.releaseSeat(conn, showtimeId, seatId);
                }
                conn.commit();
                return true;
            } catch (Exception ex) {
                conn.rollback();
                logger.log(Level.WARNING, "Lỗi khi giải phóng ghế", ex);
                return false;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Lỗi kết nối cơ sở dữ liệu khi nhả ghế", e);
            return false;
        }
    }

    /**
     * Quét và tự động giải phóng tất cả các ghế bị giữ quá 10 phút chưa thanh toán.
     */
    public int sweepExpiredHolds() {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int count = seatHoldDao.expireElapsedHolds(conn);
                ticketDao.cancelExpiredPendingTickets(conn);
                conn.commit();
                return count;
            } catch (Exception ex) {
                conn.rollback();
                return 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ---- Policy Helpers for Tests & Business Rules ----

    /**
     * Tính tỷ lệ hoàn tiền theo bậc thang dựa trên múi giờ Việt Nam (+7).
     * >= 24h: hoàn 100%
     * 2h - 24h: hoàn 50%
     * < 2h hoặc đã chiếu: hoàn 0%
     */
    public static long refundRatePercent(Instant now, LocalDateTime showtimeStart) {
        if (showtimeStart == null) return 0;
        LocalDateTime businessNow = LocalDateTime.ofInstant(now, BUSINESS_TZ);
        Duration until = Duration.between(businessNow, showtimeStart);
        if (until.isNegative()) return 0;
        if (until.toHours() >= 24) return 100;
        if (until.toHours() >= 2) return 50;
        return 0;
    }

    /**
     * Chỉ cho phép soát vé trước giờ chiếu tối đa 30 phút.
     */
    public static boolean isTooEarlyForCheckIn(LocalDateTime now, LocalDateTime showtimeStart) {
        if (showtimeStart == null) return false;
        return now.isBefore(showtimeStart.minusMinutes(30));
    }

    private LocalDateTime queryShowtimeStartTime(Connection conn, long showtimeId) {
        String sql = "SELECT start_time FROM dbo.showtime WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp("start_time");
                    if (ts != null) return ts.toLocalDateTime();
                }
            }
        } catch (SQLException ignored) { }
        return null;
    }

    private ShowtimeInfo queryShowtimeInfo(Connection conn, long showtimeId) {
        String sql = "SELECT status, start_time, end_time FROM dbo.showtime WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    Timestamp start = rs.getTimestamp("start_time");
                    Timestamp end = rs.getTimestamp("end_time");
                    return new ShowtimeInfo(
                            status,
                            start != null ? start.toLocalDateTime() : null,
                            end != null ? end.toLocalDateTime() : null
                    );
                }
            }
        } catch (SQLException ignored) { }
        return null;
    }

    public static class ShowtimeInfo {
        public final String status;
        public final LocalDateTime startTime;
        public final LocalDateTime endTime;
        public ShowtimeInfo(String status, LocalDateTime startTime, LocalDateTime endTime) {
            this.status = status;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    private long calculateSeatPrice(String seatType) {
        if (seatType == null) return 75_000L;
        return switch (seatType.toUpperCase()) {
            case "VIP" -> 90_000L;
            case "COUPLE" -> 150_000L;
            default -> 75_000L;
        };
    }

    private String getSeatType(Connection conn, long seatId) {
        String sql = "SELECT seat_type FROM dbo.seat WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, seatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("seat_type");
            }
        } catch (SQLException ignored) { }
        return "STANDARD";
    }

    private long queryBranchId(Connection conn, long showtimeId) {
        String sql = "SELECT branch_id FROM dbo.showtime WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, showtimeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("branch_id");
            }
        } catch (SQLException ignored) { }
        return 1L;
    }

    private String generateTicketCode() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String randPart = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "TK-" + datePart + "-" + randPart;
    }
}
