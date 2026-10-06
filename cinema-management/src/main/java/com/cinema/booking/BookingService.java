package com.cinema.booking;

import com.cinema.common.ServiceException;
import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service đặt vé — Phụ trách bởi Người 4 (Nhất).
 * Chức năng 2: Giữ ghế 10 phút và chống tranh chấp đồng thời (Concurrency Control).
 * Chức năng 3: Snapshot giá vé, tạo vé PENDING, xác nhận đặt vé (CONFIRMED) và tra cứu vé cá nhân.
 */
public class BookingService {
    private static final Logger logger = Logger.getLogger(BookingService.class.getName());

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

        Map<String, Object> res = new HashMap<>();
        res.put("baseTotal", total);
        res.put("tierDiscount", 0L);
        res.put("voucherDiscount", 0L);
        res.put("payable", total);
        return res;
    }

    /**
     * Giữ ghế trực tuyến trong 10 phút (Chức năng 2) và tạo Vé PENDING kèm Snapshot giá (Chức năng 3).
     *
     * <p>Chống Race Condition:
     * Sắp xếp seat_id tăng dần và dùng SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
     * trong Transaction để tránh Deadlock và đảm bảo chỉ đúng 1 khách hàng giữ được ghế khi bấm cùng lúc.
     */
    public HoldResult holdSeats(long showtimeId, List<Long> seatIds, Long userId) {
        if (seatIds == null || seatIds.isEmpty()) {
            return HoldResult.rejected("Vui lòng chọn ít nhất một ghế");
        }

        // 1. Sắp xếp danh sách ghế tăng dần để chống Deadlock giữa 2 giao dịch đồng thời
        List<Long> orderedSeatIds = new ArrayList<>(new TreeSet<>(seatIds));

        // 2. Mở Transaction giữ ghế và tạo vé
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<SeatHoldDAO.SeatRow> lockedSeats = new ArrayList<>();

                // Khóa và kiểm tra từng ghế trong DB
                for (Long seatId : orderedSeatIds) {
                    SeatHoldDAO.SeatRow row = seatHoldDao.lockSeat(conn, showtimeId, seatId);
                    if (row == null) {
                        conn.rollback();
                        return HoldResult.rejected("Ghế không tồn tại hoặc không thuộc suất chiếu này");
                    }

                    // Kiểm tra ghế có đang AVAILABLE hoặc hold cũ đã hết hạn (Lazy Expiry) không
                    boolean isExpiredHold = row.holdExpiresAt != null &&
                            LocalDateTime.now(ZoneOffset.UTC).isAfter(row.holdExpiresAt);

                    if (!"AVAILABLE".equalsIgnoreCase(row.status) && !isExpiredHold) {
                        conn.rollback();
                        return HoldResult.rejected("Ghế " + row.rowLabel + row.colNo + " không còn trống");
                    }
                    lockedSeats.add(row);
                }

                // 3. Tạo bản ghi seat_hold mới với thời hạn 10 phút
                long holdId = seatHoldDao.insertHold(conn, showtimeId, userId);
                LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(SeatHoldDAO.HOLD_MINUTES);

                // 4. Đánh dấu các ghế sang trạng thái HOLD
                for (SeatHoldDAO.SeatRow row : lockedSeats) {
                    seatHoldDao.markSeatHeld(conn, showtimeId, row.seatId, holdId, expiresAt);
                }

                // 5. Chức năng 3: Snapshot giá từng ghế và tạo vé PENDING
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

                long ticketId = ticketDao.insert(conn, ticket);
                ticketDao.insertSeats(conn, ticketId, snapshotSeats);

                conn.commit();
                logger.info("Giữ thành công " + lockedSeats.size() + " ghế (holdId=" + holdId 
                        + ", ticketId=" + ticketId + ") cho user " + userId);
                return HoldResult.accepted(String.valueOf(holdId));

            } catch (Exception ex) {
                conn.rollback();
                logger.log(Level.WARNING, "Lỗi khi giữ ghế và tạo vé trong transaction", ex);
                return HoldResult.rejected("Không giữ được ghế — vui lòng thử lại");
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Lỗi kết nối cơ sở dữ liệu khi giữ ghế", e);
            return HoldResult.rejected("Lỗi kết nối cơ sở dữ liệu");
        }
    }

    /**
     * Xác nhận đặt vé trong thời hạn giữ ghế 10 phút (Chức năng 3).
     *
     * <p>Xử lý nguyên tử (Atomic Transaction):
     * 1. Khóa và kiểm tra thời hạn giữ ghế (status='ACTIVE' và expires_at > now).
     * 2. Chuyển trạng thái vé PENDING -> CONFIRMED (Guarded Update).
     * 3. Chuyển trạng thái ghế trong showtime_seat từ HOLD -> SOLD.
     * 4. Cập nhật trạng thái seat_hold sang CONFIRMED.
     */
    public Ticket confirmBooking(long holdId, Long actorUserId) throws Exception {
        sweepExpiredHolds();

        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Khóa bản ghi giữ ghế
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

                // 2. Tìm bản ghi vé PENDING ứng với holdId
                Ticket ticket = ticketDao.findByHoldIdLocked(conn, holdId)
                        .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy thông tin vé tương ứng"));

                if (!Ticket.STATUS_PENDING.equalsIgnoreCase(ticket.getStatus())) {
                    throw new ServiceException.Conflict("Vé đã được xử lý hoặc không ở trạng thái chờ xác nhận");
                }

                // 3. Cập nhật vé sang CONFIRMED (Bảo vệ Optimistic Locking chống xác nhận lặp)
                boolean ticketUpdated = ticketDao.confirmGuarded(
                        conn, ticket.getId(), ticket.getTotalAmount(), ticket.getVoucherCode(), 0
                );
                if (!ticketUpdated) {
                    throw new ServiceException.Conflict("Không thể xác nhận vé (trạng thái vé đã bị thay đổi)");
                }

                // 4. Chuyển trạng thái ghế từ HOLD sang SOLD
                List<Long> seatIds = seatHoldDao.findSeatIdsOfHold(conn, holdId);
                if (seatIds.isEmpty()) {
                    throw new ServiceException.Conflict("Phiên giữ ghế không còn ghế hợp lệ");
                }
                for (Long seatId : seatIds) {
                    seatHoldDao.markSeatSold(conn, hold.showtimeId, seatId);
                }

                // 5. Cập nhật seat_hold sang CONFIRMED
                seatHoldDao.updateHoldStatus(conn, holdId, "CONFIRMED", "ACTIVE");

                // 6. Nạp thông tin ghế snapshot để trả về
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
     * Quét và tự động giải phóng tất cả các ghế bị giữ quá 10 phút chưa thanh toán
     * (Lazy & Periodic sweep).
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

    /** Tính giá snapshot cho ghế dựa theo loại ghế. */
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