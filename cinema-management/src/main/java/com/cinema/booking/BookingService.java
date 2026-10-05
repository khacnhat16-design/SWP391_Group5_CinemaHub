package com.cinema.booking;

import dal.DBContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service đặt vé — Phụ trách bởi Người 4 (Nhất).
 * Chức năng 2: Quản lý sơ đồ ghế, giữ ghế 10 phút và chống tranh chấp đồng thời (Concurrency Control).
 */
public class BookingService {
    private static final Logger logger = Logger.getLogger(BookingService.class.getName());

    private final SeatHoldDAO seatHoldDao;

    public BookingService() {
        this.seatHoldDao = new SeatHoldDAO();
    }

    public BookingService(SeatHoldDAO seatHoldDao) {
        this.seatHoldDao = seatHoldDao;
    }

    /**
     * Giữ ghế trực tuyến trong 10 phút (Chức năng 2).
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

        // 2. Mở Transaction giữ ghế
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

                conn.commit();
                logger.info("Giữ thành công " + lockedSeats.size() + " ghế (holdId=" + holdId + ") cho user " + userId);
                return HoldResult.accepted(String.valueOf(holdId));

            } catch (Exception ex) {
                conn.rollback();
                logger.log(Level.WARNING, "Lỗi khi giữ ghế trong transaction", ex);
                return HoldResult.rejected("Không giữ được ghế — vui lòng thử lại");
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Lỗi kết nối cơ sở dữ liệu khi giữ ghế", e);
            return HoldResult.rejected("Lỗi kết nối cơ sở dữ liệu");
        }
    }

    /**
     * Giải phóng ghế khi khách hàng bỏ chọn hoặc chủ động hủy giữ (Chức năng 2).
     */
    public boolean releaseHold(long showtimeId, List<Long> seatIds, Long userId) {
        if (seatIds == null || seatIds.isEmpty()) return false;

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
     * Quét và tự động giải phóng tất cả các ghế bị giữ quá 10 phút chưa thanh toán (Lazy & Periodic sweep).
     */
    public int sweepExpiredHolds() {
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int count = seatHoldDao.expireElapsedHolds(conn);
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
}
