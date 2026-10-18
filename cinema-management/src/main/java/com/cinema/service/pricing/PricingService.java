package com.cinema.service.pricing;

import com.cinema.dao.pricing.PriceRuleDAO;
import com.cinema.dao.pricing.PricingConfigDAO;
import com.cinema.model.branch.Branch;
import com.cinema.model.movie.Movie;
import com.cinema.model.pricing.PriceRule;
import com.cinema.model.pricing.PricingEngine;
import com.cinema.model.pricing.TicketType;

import com.cinema.common.ServiceException;
import com.cinema.model.showtime.Showtime;
import com.cinema.dao.showtime.ShowtimeDAO;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Quản lý khung giá và tính giá vé theo Movie / Branch / Showtime / TicketType (Req 5.1-5.5, 7-9).
 *
 * <p>Hệ thống phân cấp (ưu tiên giảm dần — Req 7):
 * <ol>
 *   <li>Showtime-specific (showtime_id NOT NULL).</li>
 *   <li>Movie + Time-based (movie_id NOT NULL).</li>
 *   <li>Branch default (branch_id NOT NULL).</li>
 *   <li>System default (cả 3 cột NULL).</li>
 * </ol>
 * Trong cùng scope, rule có {@code ticket_type} khớp thắng rule null (Req 9).</p>
 *
 * <p>Business rules:
 * <ul>
 *   <li>Snapshot: ticket_seat.price là giá tại thời điểm bán; update rule sau đó KHÔNG
 *       hồi tố (Req 8).</li>
 *   <li>Backend KHÔNG trust client unitPrice (Req 11) — luôn resolve qua service này.</li>
 *   <li>Đồng thời: seat lock + ticket creation + price snapshot cùng transaction (Req 12).</li>
 * </ul>
 */
public class PricingService {
    private static final Logger logger = Logger.getLogger(PricingService.class.getName());

    private static final LocalTime EARLY_CUTOFF = LocalTime.of(10, 0);
    private static final LocalTime LATE_CUTOFF = LocalTime.of(22, 0);

    public static final String SLOT_EARLY = "EARLY";
    public static final String SLOT_DAY = "DAY";
    public static final String SLOT_LATE = "LATE";

    public static final String SOURCE_SHOWTIME = "GIA_SHOWTIME";
    public static final String SOURCE_MOVIE_BRANCH = "GIA_MOVIE_CHI_NHANH";
    public static final String SOURCE_MOVIE = "GIA_MOVIE_TIME";
    public static final String SOURCE_BRANCH = "GIA_BRANCH";
    public static final String SOURCE_SYSTEM = "KHUNG_GIA";
    public static final String SOURCE_DEFAULT = "GIA_MAC_DINH";

    private final PriceRuleDAO priceRuleDao;
    private final ShowtimeDAO showtimeDao;
    private final PricingConfigDAO pricingConfigDao;

    public PricingService(PriceRuleDAO priceRuleDao, ShowtimeDAO showtimeDao) {
        this(priceRuleDao, showtimeDao, new PricingConfigDAO());
    }

    public PricingService(PriceRuleDAO priceRuleDao, ShowtimeDAO showtimeDao,
                          PricingConfigDAO pricingConfigDao) {
        this.priceRuleDao = priceRuleDao;
        this.showtimeDao = showtimeDao;
        this.pricingConfigDao = pricingConfigDao;
    }

    /** Báo giá vé theo loại ghế và hạng vé. */
    public PricedOrder calcOrder(long showtimeId, List<String> seatTypes, Long userId,
                                 TicketType ticketType) throws Exception {
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
        if (seatTypes == null || seatTypes.isEmpty()) {
            throw new ServiceException.Validation("Đơn vé phải có ít nhất một ghế");
        }

        long baseTotal = 0;
        for (String seatType : seatTypes) {
            baseTotal += priceSeatFor(showtime, seatType, ticketType).price();
        }

        return new PricedOrder(showtimeId, baseTotal);
    }

    /** Kết quả báo giá đơn vé. */
    public record PricedOrder(long showtimeId, long baseTotal) { }

    // ============================================================
    //  CRUD rule — Admin only (Caller must enforce auth, Req 14)
    // ============================================================

    /** Tạo rule SYSTEM (seat_type × day_type × time_slot). Reuse cho Req 5.1. */
    public PriceRule createRule(String seatType, String dayType, String timeSlot, long price) throws Exception {
        return createRule(seatType, dayType, timeSlot, price, null, null, null, null);
    }

    /**
     * Tạo rule mới với override optional (Req 7). Đúng một trong movieId/branchId showtimeId
     * được phép NOT NULL; movieId + branchId kết hợp được để định giá riêng cho từng phim tại rạp.
     */
    public PriceRule createRule(String seatType, String dayType, String timeSlot, long price,
                                Long movieId, Long branchId, Long showtimeId,
                                TicketType ticketType) throws Exception {
        return createRule(seatType, dayType, timeSlot, price, movieId, branchId, showtimeId,
                ticketType, null, null);
    }

    public PriceRule createRule(String seatType, String dayType, String timeSlot, long price,
                                Long movieId, Long branchId, Long showtimeId,
                                TicketType ticketType, LocalDate activeFrom,
                                LocalDate activeTo) throws Exception {
        String normalizedSeat = normalizeEnum(seatType, "Loại ghế",
                List.of(PricingEngine.SeatType.STANDARD.name(), PricingEngine.SeatType.VIP.name(),
                        PricingEngine.SeatType.COUPLE.name()));
        String normalizedDay = normalizeEnum(dayType, "Loại ngày",
                List.of(PricingEngine.DayType.WEEKDAY.name(), PricingEngine.DayType.WEEKEND.name(),
                        PricingEngine.DayType.HOLIDAY.name()));
        String normalizedSlot = normalizeEnum(timeSlot, "Khung giờ",
                List.of(SLOT_EARLY, SLOT_DAY, SLOT_LATE));
        if (price < 0) {
            throw new ServiceException.Validation("Giá vé không được âm");
        }
        validateDateRange(activeFrom, activeTo);
        if (showtimeId != null && (movieId != null || branchId != null)) {
            throw new ServiceException.Validation(
                    "Khung giá suất chiếu không được kết hợp với scope phim hoặc chi nhánh");
        }

        PriceRule rule = new PriceRule();
        rule.setSeatType(normalizedSeat);
        rule.setDayType(normalizedDay);
        rule.setTimeSlot(normalizedSlot);
        rule.setPrice(price);
        rule.setMovieId(movieId);
        rule.setBranchId(branchId);
        rule.setShowtimeId(showtimeId);
        rule.setTicketType(ticketType == null ? null : ticketType.code());
        rule.setActiveFrom(activeFrom);
        rule.setActiveTo(activeTo);
        priceRuleDao.insert(rule);

        logger.info("Created price rule " + rule.id() + " scope=" + rule.scope()
                + " seat=" + normalizedSeat + " day=" + normalizedDay + " slot=" + normalizedSlot
                + " ticketType=" + rule.ticketType() + " dates=" + activeFrom + ".." + activeTo
                + " price=" + price);
        return rule;
    }

    /** Req 5.4 — chỉ áp dụng cho vé tạo sau. */
    public PriceRule updateRulePrice(long ruleId, long price) throws Exception {
        PriceRule existing = priceRuleDao.findById(ruleId)
                .orElseThrow(() -> new ServiceException.NotFound("Khung giá không tồn tại"));
        return updateRulePrice(ruleId, price, existing.activeFrom(), existing.activeTo());
    }

    public PriceRule updateRulePrice(long ruleId, long price, LocalDate activeFrom,
                                     LocalDate activeTo) throws Exception {
        if (price < 0) {
            throw new ServiceException.Validation("Giá vé không được âm");
        }
        validateDateRange(activeFrom, activeTo);
        PriceRule rule = priceRuleDao.findById(ruleId)
                .orElseThrow(() -> new ServiceException.NotFound("Khung giá không tồn tại"));
        priceRuleDao.updatePriceAndDates(ruleId, price, activeFrom, activeTo);
        rule.setPrice(price);
        rule.setActiveFrom(activeFrom);
        rule.setActiveTo(activeTo);
        logger.info("Updated price rule " + ruleId + " → " + price);
        return rule;
    }

    private static void validateDateRange(LocalDate activeFrom, LocalDate activeTo) {
        if ((activeFrom == null) != (activeTo == null)) {
            throw new ServiceException.Validation("Phải nhập cả ngày bắt đầu và ngày kết thúc, hoặc để trống cả hai");
        }
        if (activeFrom != null && activeTo.isBefore(activeFrom)) {
            throw new ServiceException.Validation("Ngày kết thúc phải bằng hoặc sau ngày bắt đầu");
        }
    }

    public List<PriceRule> listRules() throws Exception {
        return priceRuleDao.findAll();
    }

    public void deleteRule(long ruleId) throws Exception {
        priceRuleDao.findById(ruleId)
                .orElseThrow(() -> new ServiceException.NotFound("Khung giá không tồn tại"));
        if (!priceRuleDao.delete(ruleId)) {
            throw new ServiceException.NotFound("Khung giá không tồn tại");
        }
        logger.info("Deleted price rule " + ruleId);
    }

    public long defaultTicketPrice() throws Exception {
        return pricingConfigDao.getDefaultTicketPrice();
    }

    public long updateDefaultTicketPrice(long price) throws Exception {
        if (price < 0) {
            throw new ServiceException.Validation("Giá vé mặc định không được âm");
        }
        return pricingConfigDao.updateDefaultTicketPrice(price);
    }

    // ============================================================
    //  Price resolution — Req 7 hierarchy
    // ============================================================

    public PricedSeat priceSeat(long showtimeId, String seatType) throws Exception {
        return priceSeat(showtimeId, seatType, null);
    }

    public PricedSeat priceSeat(long showtimeId, String seatType, TicketType ticketType) throws Exception {
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
        return priceSeatFor(showtime, seatType, ticketType);
    }

    public PricedSeat priceSeatFor(Showtime showtime, String seatType) throws Exception {
        return priceSeatFor(showtime, seatType, null);
    }

    /**
     * Tính giá snapshot cho từng ghế của lịch chiếu — đây là entry point duy nhất
     * mà {@code BookingService} gọi khi tạo vé (Req 11).
     */
    public PricedSeat priceSeatFor(Showtime showtime, String seatType, TicketType ticketType) throws Exception {
        return priceSeatFor(showtime, seatType, ticketType, loadAllRules());
    }

    public Map<String, PricedSeat> priceTableFor(Showtime showtime) throws Exception {
        return priceTableFor(showtime, null);
    }

    public Map<String, PricedSeat> priceTableFor(Showtime showtime, TicketType ticketType) throws Exception {
        AllRules all = loadAllRules();
        Map<String, PricedSeat> table = new LinkedHashMap<>();
        for (PricingEngine.SeatType type : PricingEngine.SeatType.values()) {
            table.put(type.name(), priceSeatFor(showtime, type.name(), ticketType, all));
        }
        return table;
    }

    public Map<String, PricedSeat> priceTable(long showtimeId) throws Exception {
        return priceTable(showtimeId, null);
    }

    public Map<String, PricedSeat> priceTable(long showtimeId, TicketType ticketType) throws Exception {
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("Suất chiếu không tồn tại"));
        return priceTableFor(showtime, ticketType);
    }

    /** Resolve giá với hierarchy đầy đủ (Req 7.4). */
    private PricedSeat priceSeatFor(Showtime showtime, String seatType, TicketType ticketType,
                                    AllRules all) throws Exception {
        PricingEngine.SeatType type = parseSeatType(seatType);
        LocalDateTime start = showtime.startTime();
        PricingEngine.DayType dayType = dayTypeOf(start.toLocalDate(), all.holidayMonthDays);
        String timeSlot = timeSlotOf(start.toLocalTime());
        TicketType reqTicket = ticketType;

        Resolution r = resolveHierarchy(type, dayType, timeSlot, start.toLocalDate(),
                showtime, reqTicket, all);
        return new PricedSeat(showtime.id(), type.name(), dayType.name(), timeSlot,
                r.price, r.source,
                explain(type, dayType, timeSlot, reqTicket, r, start, showtime));
    }

    /**
     * Phân cấp ưu tiên (Req 7.4): Showtime → Movie+Time → Branch → System → Default.
     * Trong cùng scope, ưu tiên rule có ticket_type khớp.
     */
    private Resolution resolveHierarchy(PricingEngine.SeatType type, PricingEngine.DayType dayType,
                                        String timeSlot, LocalDate showDate, Showtime showtime, TicketType ticketType,
                                        AllRules all) throws Exception {
        // 1) Showtime-specific (Req 7.1)
        Optional<PriceRule> showtimeRule = pickByScope(all.showtime, showtime.id(), type, dayType,
                timeSlot, showDate, ticketType);
        if (showtimeRule.isPresent()) {
            return Resolution.of(showtimeRule.get().price(), SOURCE_SHOWTIME, showtimeRule.get());
        }
        // 2) Exact movie + branch rule, then the broader movie rule.
        Optional<PriceRule> movieBranchRule = pickMovieBranch(all.movieBranch, showtime.movieId(),
                showtime.branchId(), type, dayType, timeSlot, showDate, ticketType);
        if (movieBranchRule.isPresent()) {
            return Resolution.of(movieBranchRule.get().price(), SOURCE_MOVIE_BRANCH,
                    movieBranchRule.get());
        }
        // 3) Movie + Time-based (Req 7.2)
        Optional<PriceRule> movieRule = pickByScope(all.movie, showtime.movieId(), type, dayType,
                timeSlot, showDate, ticketType);
        if (movieRule.isPresent()) {
            return Resolution.of(movieRule.get().price(), SOURCE_MOVIE, movieRule.get());
        }
        // 4) Branch default (Req 7.3)
        Optional<PriceRule> branchRule = pickByScope(all.branch, showtime.branchId(), type, dayType,
                timeSlot, showDate, ticketType);
        if (branchRule.isPresent()) {
            return Resolution.of(branchRule.get().price(), SOURCE_BRANCH, branchRule.get());
        }
        // 5) System rules include ticket type, just like scoped rules.
        Optional<PriceRule> systemRule = pickSystemRule(all.system, type, dayType, timeSlot,
                showDate, ticketType);
        if (systemRule.isPresent()) {
            return Resolution.of(systemRule.get().price(), SOURCE_SYSTEM, systemRule.get());
        }
        // 6) Explicitly configured system fallback (Req 5.3); never silently hard-code.
        return new Resolution(pricingConfigDao.getDefaultTicketPrice(), SOURCE_DEFAULT,
                "Giá mặc định được cấu hình");
    }

    /**
     * Tìm rule khớp trong một scope. Khi có nhiều rule cùng hoạt động, ưu tiên ticket type
     * chính xác trước, sau đó khoảng ngày cụ thể nhất (ngày bắt đầu mới nhất, ngày kết thúc sớm nhất).
     */
    private Optional<PriceRule> pickByScope(List<PriceRule> scope, long entityId,
                                            PricingEngine.SeatType type, PricingEngine.DayType dayType,
                                            String timeSlot, LocalDate showDate, TicketType ticketType) {
        List<PriceRule> candidates = new ArrayList<>();
        for (PriceRule rule : scope) {
            if (rule.id() == null) continue;
            Long owner = rule.showtimeId() != null ? rule.showtimeId()
                    : (rule.movieId() != null ? rule.movieId() : rule.branchId());
            if (owner == null || owner != entityId) continue;
            if (!type.name().equals(rule.seatType())) continue;
            if (!dayType.name().equals(rule.dayType())) continue;
            if (!timeSlot.equals(rule.timeSlot())) continue;
            if (!activeOn(rule, showDate)) continue;
            if (!matchesTicketType(rule.ticketType(), ticketType)) continue;
            candidates.add(rule);
        }
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(rulePriority(ticketType));
        return Optional.of(candidates.get(0));
    }

    private Optional<PriceRule> pickSystemRule(List<PriceRule> rules, PricingEngine.SeatType type,
                                               PricingEngine.DayType dayType, String timeSlot,
                                               LocalDate showDate, TicketType ticketType) {
        return rules.stream()
                .filter(rule -> type.name().equals(rule.seatType()))
                .filter(rule -> dayType.name().equals(rule.dayType()))
                .filter(rule -> timeSlot.equals(rule.timeSlot()))
                .filter(rule -> activeOn(rule, showDate))
                .filter(rule -> matchesTicketType(rule.ticketType(), ticketType))
                .sorted(rulePriority(ticketType))
                .findFirst();
    }

    private Optional<PriceRule> pickMovieBranch(List<PriceRule> rules, long movieId, long branchId,
                                                PricingEngine.SeatType type,
                                                PricingEngine.DayType dayType, String timeSlot,
                                                LocalDate showDate, TicketType ticketType) {
        return rules.stream()
                .filter(rule -> rule.movieId() != null && rule.movieId() == movieId)
                .filter(rule -> rule.branchId() != null && rule.branchId() == branchId)
                .filter(rule -> type.name().equals(rule.seatType()))
                .filter(rule -> dayType.name().equals(rule.dayType()))
                .filter(rule -> timeSlot.equals(rule.timeSlot()))
                .filter(rule -> activeOn(rule, showDate))
                .filter(rule -> matchesTicketType(rule.ticketType(), ticketType))
                .sorted(rulePriority(ticketType))
                .findFirst();
    }

    private static boolean activeOn(PriceRule rule, LocalDate date) {
        return (rule.activeFrom() == null || !date.isBefore(rule.activeFrom()))
                && (rule.activeTo() == null || !date.isAfter(rule.activeTo()));
    }

    private static Comparator<PriceRule> rulePriority(TicketType ticketType) {
        return Comparator.comparingInt((PriceRule rule) ->
                        matchTicketScore(rule.ticketType(), ticketType == null ? null : ticketType.code()))
                .thenComparing(PriceRule::activeFrom,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PriceRule::activeTo, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static boolean matchesTicketType(String ruleTicketType, TicketType requested) {
        return ruleTicketType == null
                || requested != null && requested.code().equals(ruleTicketType);
    }

    private static int matchTicketScore(String ruleTicketType, String wanted) {
        if (wanted == null) {
            return ruleTicketType == null ? 0 : 1;
        }
        return ruleTicketType == null ? 1 : (wanted.equals(ruleTicketType) ? 0 : 2);
    }

    /** Phân loại ngày. */
    static PricingEngine.DayType dayTypeOf(LocalDate date) {
        return dayTypeOf(date, Set.of());
    }

    static PricingEngine.DayType dayTypeOf(LocalDate date, Set<String> holidayMonthDays) {
        if (holidayMonthDays.contains(date.toString().substring(5))) {
            return PricingEngine.DayType.HOLIDAY;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
                ? PricingEngine.DayType.WEEKEND : PricingEngine.DayType.WEEKDAY;
    }

    /** Phân loại khung giờ. */
    static String timeSlotOf(LocalTime time) {
        if (time.isBefore(EARLY_CUTOFF)) return SLOT_EARLY;
        if (!time.isBefore(LATE_CUTOFF)) return SLOT_LATE;
        return SLOT_DAY;
    }

    /** Cache tất cả rule (chia scope) — load 1 lần / call. */
    private AllRules loadAllRules() throws Exception {
        List<PriceRule> all = priceRuleDao.findAll();
        AllRules result = new AllRules();
        result.holidayMonthDays = Set.of();
        for (PriceRule rule : all) {
            switch (rule.scope()) {
                case PriceRule.SCOPE_SHOWTIME -> result.showtime.add(rule);
                case PriceRule.SCOPE_MOVIE_BRANCH -> result.movieBranch.add(rule);
                case PriceRule.SCOPE_MOVIE -> result.movie.add(rule);
                case PriceRule.SCOPE_BRANCH -> result.branch.add(rule);
                default -> result.system.add(rule);
            }
        }
        return result;
    }

    private PricingEngine.SeatType parseSeatType(String seatType) {
        if (seatType == null || seatType.isBlank()) {
            throw new ServiceException.Validation("Loại ghế là bắt buộc");
        }
        try {
            return PricingEngine.SeatType.valueOf(seatType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ServiceException.Validation("Loại ghế phải là STANDARD, VIP hoặc COUPLE");
        }
    }

    private String normalizeEnum(String value, String label, List<String> allowed) {
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation(label + " là bắt buộc");
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new ServiceException.Validation(label + " phải là một trong: " + String.join(", ", allowed));
        }
        return normalized;
    }

    private String explain(PricingEngine.SeatType type, PricingEngine.DayType dayType,
                           String timeSlot, TicketType ticketType, Resolution r,
                           LocalDateTime start, Showtime showtime) {
        StringBuilder sb = new StringBuilder("Giá ").append(r.price).append("đ cho ghế ").append(type);
        sb.append(" suất ").append(start).append(" (").append(dayType).append("/").append(timeSlot).append(")");
        if (ticketType != null) {
            sb.append(" ticketType=").append(ticketType.code());
        }
        switch (r.source) {
            case SOURCE_SHOWTIME -> sb.append(" — lấy từ khung giá riêng cho showtime #").append(showtime.id());
            case SOURCE_MOVIE_BRANCH -> sb.append(" — lấy từ khung giá riêng cho phim tại chi nhánh");
            case SOURCE_MOVIE -> sb.append(" — lấy từ khung giá theo Movie #").append(showtime.movieId());
            case SOURCE_BRANCH -> sb.append(" — lấy từ khung giá theo Branch #").append(showtime.branchId());
            case SOURCE_SYSTEM -> sb.append(" — lấy từ khung giá hệ thống khớp tổ hợp");
            default -> sb.append(" — không có khung giá khớp, áp giá mặc định");
        }
        return sb.toString();
    }

    /** Internal: cache + bucket cho hierarchy lookup. */
    private static final class AllRules {
        final List<PriceRule> showtime = new ArrayList<>();
        final List<PriceRule> movieBranch = new ArrayList<>();
        final List<PriceRule> movie = new ArrayList<>();
        final List<PriceRule> branch = new ArrayList<>();
        final List<PriceRule> system = new ArrayList<>();
        Set<String> holidayMonthDays;
    }

    private record Resolution(long price, String source, String note) {
        static Resolution of(long price, String source, PriceRule rule) {
            return new Resolution(price, source, "rule #" + rule.id()
                    + (rule.ticketType() != null ? " (ticket_type=" + rule.ticketType() + ")" : ""));
        }
    }

    /** Kết quả tính giá một ghế kèm diễn giải (Req 5.5, 7.6). */
    public record PricedSeat(long showtimeId, String seatType, String dayType, String timeSlot,
                             long price, String source, String explanation) { }
}