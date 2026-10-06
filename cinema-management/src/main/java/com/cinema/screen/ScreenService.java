package com.cinema.screen;

import com.cinema.branch.BranchDAO;
import com.cinema.common.ServiceException;
import dal.DBContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Service quản lý phòng chiếu & sơ đồ ghế theo chi nhánh (Req 3.1-3.6).
 *
 * <p>Business rules:
 * <ul>
 *   <li>Tạo phòng: branch tồn tại + ACTIVE, mã phòng unique trong branch,
 *       row/col là số nguyên dương; sinh ghế rowLabel A.., colNo 1.. (Req 3.1, 3.2).</li>
 *   <li>Cập nhật sơ đồ ghế: chặn thay đổi làm giảm số ghế hoặc đổi loại ghế
 *       của ghế đã có vé/hold khi phòng còn lịch chiếu tương lai (Req 3.3, 3.4).</li>
 *   <li>Vô hiệu hóa phòng: chặn khi còn lịch chiếu tương lai chưa kết thúc (Req 3.5).</li>
 *   <li>Branch scope: caller phải truyền branchId đã được filter xác thực;
 *       thao tác sai chi nhánh bị từ chối (Req 3.6).</li>
 * </ul>
 */
public class ScreenService {
    public record SeatLayoutInput(String rowLabel, int colNo, String seatType) { }

    private static final Logger logger = Logger.getLogger(ScreenService.class.getName());
    /** Req 15.3/11.2 — audit thay đổi screen/seat map. */
    private final com.cinema.audit.AuditService audit = new com.cinema.audit.AuditService();
    private static final Set<String> VALID_SEAT_TYPES = Set.of(
            Seat.TYPE_STANDARD, Seat.TYPE_VIP, Seat.TYPE_COUPLE);

    private final ScreenDAO screenDao;
    private final SeatDAO seatDao;
    private final BranchDAO branchDao;

    public ScreenService(ScreenDAO screenDao, SeatDAO seatDao, BranchDAO branchDao) {
        this.screenDao = screenDao;
        this.seatDao = seatDao;
        this.branchDao = branchDao;
    }

    /**
     * Tạo phòng chiếu + sinh sơ đồ ghế (Req 3.1, 3.2).
     *
     * @param actorBranchScope branchId hiệu lực của người thao tác (Admin: null = toàn chuỗi)
     */
    public Screen createScreen(Long actorBranchScope, long branchId, String code, String name,
                               int rowCount, int colCount, String vipRows) throws Exception {
        return createScreen(actorBranchScope, branchId, code, name, rowCount, colCount,
                vipRows, null);
    }

    public Screen createScreen(Long actorBranchScope, long branchId, String code, String name,
                               int rowCount, int colCount, String vipRows,
                               List<SeatLayoutInput> requestedLayout) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        validateCode(code);
        if (name == null || name.isBlank()) {
            throw new ServiceException.Validation("Tên phòng chiếu là bắt buộc");
        }
        if (rowCount <= 0 || colCount <= 0) {
            throw new ServiceException.Validation("Số hàng và số ghế mỗi hàng phải là số nguyên dương");
        }
        if (rowCount > 26) {
            throw new ServiceException.Validation("Số hàng tối đa là 26 (A-Z)");
        }
        if (colCount > 30) {
            throw new ServiceException.Validation("Số cột tối đa là 30");
        }

        var branch = branchDao.findById(branchId)
                .orElseThrow(() -> new ServiceException.NotFound("Chi nhánh không tồn tại"));
        if (!"ACTIVE".equals(branch.status())) {
            throw new ServiceException.BusinessRule("BRANCH_INACTIVE",
                    "Chi nhánh đã ngừng hoạt động, không thể tạo phòng chiếu mới");
        }

        String normalizedCode = code.trim().toUpperCase();
        if (screenDao.findByBranchAndCode(branchId, normalizedCode).isPresent()) {
            throw new ServiceException.Conflict("Mã phòng chiếu đã tồn tại trong chi nhánh");
        }

        Screen screen = new Screen();
        screen.setBranchId(branchId);
        screen.setCode(normalizedCode);
        screen.setName(name.trim());
        screen.setRowCount(rowCount);
        screen.setColCount(colCount);
        screen.setStatus("ACTIVE");
        List<Seat> seats = requestedLayout == null
                ? buildSeatMap(0, rowCount, colCount, vipRows)
                : normalizeLayout(0, rowCount, colCount, requestedLayout);
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                screenDao.insert(conn, screen);
                for (Seat seat : seats) seat.setScreenId(screen.id());
                seatDao.insertAll(conn, screen.id(), seats);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        logger.info("Created screen " + screen.id() + " with " + seats.size() + " seats (branch " + branchId + ")");
        audit.recordSafely(null, "CREATE_SCREEN", "SCREEN", screen.id(), null,
                "{\"branchId\":" + branchId + ",\"code\":\"" + normalizedCode + "\",\"seats\":" + seats.size() + "}",
                com.cinema.audit.AuditService.SUCCESS);
        return screen;
    }

    /**
     * Cập nhật sơ đồ ghế (Req 3.3, 3.4). Chỉ cho phép khi phòng không còn lịch chiếu
     * tương lai đã mở bán có vé/hold trên các ghế bị ảnh hưởng.
     */
    public Screen updateSeatMap(Long actorBranchScope, long screenId,
                                int rowCount, int colCount, String vipRows) throws Exception {
        if (rowCount <= 0 || rowCount > 26 || colCount <= 0 || colCount > 30) {
            throw new ServiceException.Validation("Sơ đồ tối đa 26 hàng và 30 cột");
        }
        List<SeatLayoutInput> legacyLayout = buildSeatMap(screenId, rowCount, colCount, vipRows)
                .stream()
                .map(seat -> new SeatLayoutInput(seat.rowLabel(), seat.colNo(), seat.seatType()))
                .toList();
        return updateSeatMap(actorBranchScope, screenId, rowCount, colCount, legacyLayout);
    }

    public Screen updateSeatMap(Long actorBranchScope, long screenId,
                                int rowCount, int colCount,
                                List<SeatLayoutInput> requestedLayout) throws Exception {
        Screen screen = requireScreenInScope(actorBranchScope, screenId);
        int oldRowCount = screen.rowCount();
        int oldColCount = screen.colCount();

        List<Seat> seats = normalizeLayout(screenId, rowCount, colCount, requestedLayout);
        screen.setRowCount(rowCount);
        screen.setColCount(colCount);
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(
                        "SELECT id FROM dbo.screen WITH (UPDLOCK, HOLDLOCK) WHERE id = ?")) {
                    lock.setLong(1, screenId);
                    try (ResultSet result = lock.executeQuery()) {
                        if (!result.next()) throw new ServiceException.NotFound("Phòng chiếu không tồn tại");
                    }
                }
                List<Seat> current = seatDao.findByScreen(conn, screenId, true);
                Map<String, Seat> desiredByPosition = new HashMap<>();
                for (Seat seat : seats) desiredByPosition.put(positionKey(seat), seat);
                List<Seat> affectedSeats = current.stream()
                        .filter(seat -> "ACTIVE".equals(seat.status()))
                        .filter(seat -> {
                            Seat desired = desiredByPosition.get(positionKey(seat));
                            return desired == null || !desired.seatType().equals(seat.seatType());
                        })
                        .toList();
                if (hasFutureBookedSeats(conn, screenId, affectedSeats)) {
                    throw new ServiceException.BusinessRule("SCREEN_HAS_FUTURE_TICKETS",
                            "Không thể xóa hoặc đổi loại ghế đã có vé/giữ chỗ cho suất chiếu tương lai");
                }
                seatDao.synchronize(conn, screenId, seats);
                seatDao.initializeFutureShowtimeSeats(conn, screenId);
                screenDao.update(conn, screen);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        logger.info("Updated seat map of screen " + screenId + " -> " + seats.size() + " seats");
        audit.recordSafely(null, "UPDATE_SEAT_MAP", "SCREEN", screenId,
                "{\"rowCount\":" + oldRowCount + ",\"colCount\":" + oldColCount + "}",
                "{\"rowCount\":" + rowCount + ",\"colCount\":" + colCount + ",\"seats\":" + seats.size() + "}",
                com.cinema.audit.AuditService.SUCCESS);
        return screen;
    }

    List<Seat> normalizeLayout(long screenId, int rowCount, int colCount,
                               List<SeatLayoutInput> requestedLayout) {
        if (rowCount <= 0 || colCount <= 0) {
            throw new ServiceException.Validation("Số hàng và số cột phải là số nguyên dương");
        }
        if (rowCount > 26 || colCount > 30) {
            throw new ServiceException.Validation("Sơ đồ tối đa 26 hàng và 30 cột");
        }
        if (requestedLayout == null || requestedLayout.isEmpty()) {
            throw new ServiceException.Validation("Sơ đồ phải có ít nhất một ghế");
        }
        List<Seat> seats = new ArrayList<>();
        Set<String> positions = new HashSet<>();
        for (SeatLayoutInput input : requestedLayout) {
            if (input == null || input.rowLabel() == null
                    || !input.rowLabel().matches("[A-Z]")
                    || input.rowLabel().charAt(0) - 'A' >= rowCount
                    || input.colNo() < 1 || input.colNo() > colCount
                    || !isValidSeatType(input.seatType())) {
                throw new ServiceException.Validation("Vị trí hoặc loại ghế trong sơ đồ không hợp lệ");
            }
            String row = input.rowLabel();
            String type = input.seatType().toUpperCase();
            if (!positions.add(row + ":" + input.colNo())) {
                throw new ServiceException.Validation("Sơ đồ có vị trí ghế bị trùng");
            }
            Seat seat = new Seat();
            seat.setScreenId(screenId);
            seat.setRowLabel(row);
            seat.setColNo(input.colNo());
            seat.setSeatType(type);
            seat.setStatus("ACTIVE");
            seats.add(seat);
        }
        return seats;
    }

    private String positionKey(Seat seat) {
        return seat.rowLabel() + ":" + seat.colNo();
    }

    private boolean hasFutureBookedSeats(Connection conn, long screenId,
                                         List<Seat> affectedSeats) throws Exception {
        if (affectedSeats.isEmpty()) return false;
        StringBuilder sql = new StringBuilder("""
            SELECT TOP 1 1 FROM dbo.showtime s WITH (UPDLOCK, HOLDLOCK)
            JOIN dbo.showtime_seat ss WITH (UPDLOCK, HOLDLOCK)
              ON ss.showtime_id = s.id
            JOIN dbo.seat se ON se.id = ss.seat_id
            WHERE s.screen_id = ? AND s.status = 'OPEN'
              AND s.start_time > SYSUTCDATETIME()
              AND (ss.status = 'SOLD'
                   OR (ss.status = 'HOLD' AND ss.hold_expires_at > SYSUTCDATETIME()))
              AND (
            """);
        for (int i = 0; i < affectedSeats.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("(se.row_label = ? AND se.col_no = ?)");
        }
        sql.append(")");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setLong(1, screenId);
            int parameter = 2;
            for (Seat seat : affectedSeats) {
                ps.setString(parameter++, seat.rowLabel());
                ps.setInt(parameter++, seat.colNo());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Vô hiệu hóa phòng chiếu (Req 3.5): chặn khi còn lịch chiếu tương lai chưa kết thúc. */
    public Screen deactivate(Long actorBranchScope, long screenId) throws Exception {
        Screen screen = requireScreenInScope(actorBranchScope, screenId);

        if (hasFutureShowtimes(screenId)) {
            throw new ServiceException.BusinessRule("SCREEN_HAS_FUTURE_SHOWTIME",
                    "Không thể vô hiệu hóa phòng chiếu vì còn lịch chiếu tương lai chưa kết thúc");
        }

        screen.setStatus("INACTIVE");
        screenDao.update(screen);
        logger.info("Deactivated screen " + screenId);
        audit.recordSafely(null, "DEACTIVATE_SCREEN", "SCREEN", screenId,
                "{\"status\":\"ACTIVE\"}", "{\"status\":\"INACTIVE\"}",
                com.cinema.audit.AuditService.SUCCESS);
        return screen;
    }

    /** Danh sách phòng của một chi nhánh (đã ép scope). */
    public List<Screen> listForBranch(Long actorBranchScope, long branchId) throws Exception {
        requireBranchScope(actorBranchScope, branchId);
        return screenDao.findByBranch(branchId);
    }

    /** Return all screens; caller must enforce the Admin-only access rule. */
    public List<Screen> listAll() throws Exception {
        return screenDao.findAll();
    }

    /** Sơ đồ ghế của một phòng (đã ép scope). */
    public List<Seat> seatMap(Long actorBranchScope, long screenId) throws Exception {
        requireScreenInScope(actorBranchScope, screenId);
        return seatDao.findByScreen(screenId);
    }

    // ---- helpers ----

    /** Req 3.6 — caller thuộc chi nhánh A không được thao tác dữ liệu chi nhánh B. */
    private void requireBranchScope(Long actorBranchScope, long branchId) {
        if (actorBranchScope != null && actorBranchScope != branchId) {
            throw new ServiceException.Forbidden(
                    "Vi phạm phạm vi chi nhánh: không thể thao tác phòng chiếu của chi nhánh khác");
        }
    }

    private Screen requireScreenInScope(Long actorBranchScope, long screenId) throws Exception {
        Screen screen = screenDao.findById(screenId)
                .orElseThrow(() -> new ServiceException.NotFound("Phòng chiếu không tồn tại"));
        requireBranchScope(actorBranchScope, screen.branchId());
        return screen;
    }

    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ServiceException.Validation("Mã phòng chiếu là bắt buộc");
        }
        if (code.trim().length() > 20) {
            throw new ServiceException.Validation("Mã phòng chiếu tối đa 20 ký tự");
        }
    }

    /** Sinh ghế: rowLabel A.., colNo 1..; hàng trong vipRows (vd "A,B") là VIP. */
    private List<Seat> buildSeatMap(long screenId, int rowCount, int colCount, String vipRows) {
        Set<String> vip = parseVipRows(vipRows);
        List<Seat> seats = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            String rowLabel = String.valueOf((char) ('A' + r));
            String type = vip.contains(rowLabel) ? Seat.TYPE_VIP : Seat.TYPE_STANDARD;
            for (int c = 1; c <= colCount; c++) {
                Seat seat = new Seat();
                seat.setScreenId(screenId);
                seat.setRowLabel(rowLabel);
                seat.setColNo(c);
                seat.setSeatType(type);
                seat.setStatus("ACTIVE");
                seats.add(seat);
            }
        }
        return seats;
    }

    private Set<String> parseVipRows(String vipRows) {
        if (vipRows == null || vipRows.isBlank()) return Set.of();
        Set<String> rows = new java.util.HashSet<>();
        for (String part : vipRows.split(",")) {
            String normalized = part.trim().toUpperCase();
            if (!normalized.isEmpty()) rows.add(normalized);
        }
        return rows;
    }

    /** Phòng còn lịch chiếu tương lai (OPEN, start_time > now)? */
    private boolean hasFutureShowtimes(long screenId) throws Exception {
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

    /** Lịch chiếu tương lai của phòng đã có vé (SOLD) hoặc hold chưa hết hạn? */
    private boolean hasFutureShowtimesWithTicketsOrHolds(long screenId) throws Exception {
        String sql = """
            SELECT COUNT(*) FROM dbo.showtime s
            JOIN dbo.showtime_seat ss ON ss.showtime_id = s.id
            WHERE s.screen_id = ? AND s.status = 'OPEN' AND s.start_time > SYSUTCDATETIME()
              AND (ss.status = 'SOLD'
                   OR (ss.status = 'HOLD' AND ss.hold_expires_at > SYSUTCDATETIME()))
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

    /** So loại ghế của map mới với ghế hiện có ở các vị trí trùng (Req 3.4). */
    private boolean seatTypesChanged(long screenId, int rowCount, int colCount, String vipRows) throws Exception {
        Set<String> newVip = parseVipRows(vipRows);
        for (Seat seat : seatDao.findByScreen(screenId)) {
            boolean stillExists = seat.rowLabel().charAt(0) - 'A' < rowCount && seat.colNo() <= colCount;
            if (!stillExists) return true; // ghế bị loại bỏ = đổi loại/mất ghế
            String newType = newVip.contains(seat.rowLabel()) ? Seat.TYPE_VIP : Seat.TYPE_STANDARD;
            if (!newType.equals(seat.seatType())) return true;
        }
        return false;
    }

    /** Kiểm tra loại ghế hợp lệ (dùng khi mở rộng seat type theo vị trí). */
    static boolean isValidSeatType(String seatType) {
        return seatType != null && VALID_SEAT_TYPES.contains(seatType.toUpperCase());
    }
}
