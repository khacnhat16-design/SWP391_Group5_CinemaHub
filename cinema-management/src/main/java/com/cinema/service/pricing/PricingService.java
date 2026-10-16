package com.cinema.service.pricing;

import com.cinema.model.pricing.*;
import com.cinema.dao.pricing.*;
import com.cinema.infrastructure.database.DBContext;
import com.cinema.dao.showtime.ShowtimeDAO;
import com.cinema.dao.branch.BranchDAO;
import com.cinema.dao.movie.MovieDAO;
import com.cinema.model.showtime.Showtime;
import com.cinema.model.branch.Branch;
import com.cinema.model.auth.AccessScope;

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
 * QuÃ¡ÂºÂ£n lÃƒÂ½ khung giÃƒÂ¡ vÃƒÂ  tÃƒÂ­nh giÃƒÂ¡ vÃƒÂ© theo Movie / Branch / Showtime / TicketType (Req 5.1-5.5, 7-9).
 *
 * <p>HÃ¡Â»â€¡ thÃ¡Â»â€˜ng phÃƒÂ¢n cÃ¡ÂºÂ¥p (Ã†Â°u tiÃƒÂªn giÃ¡ÂºÂ£m dÃ¡ÂºÂ§n Ã¢â‚¬â€ Req 7):
 * <ol>
 *   <li>Showtime-specific (showtime_id NOT NULL).</li>
 *   <li>Movie + Time-based (movie_id NOT NULL).</li>
 *   <li>Branch default (branch_id NOT NULL).</li>
 *   <li>System default (cÃ¡ÂºÂ£ 3 cÃ¡Â»â„¢t NULL).</li>
 * </ol>
 * Trong cÃƒÂ¹ng scope, rule cÃƒÂ³ {@code ticket_type} khÃ¡Â»â€ºp thÃ¡ÂºÂ¯ng rule null (Req 9).</p>
 *
 * <p>Business rules:
 * <ul>
 *   <li>Snapshot: ticket_seat.price lÃƒÂ  giÃƒÂ¡ tÃ¡ÂºÂ¡i thÃ¡Â»Âi Ã„â€˜iÃ¡Â»Æ’m bÃƒÂ¡n; update rule sau Ã„â€˜ÃƒÂ³ KHÃƒâ€NG
 *       hÃ¡Â»â€œi tÃ¡Â»â€˜ (Req 8).</li>
 *   <li>Backend KHÃƒâ€NG trust client unitPrice (Req 11) Ã¢â‚¬â€ luÃƒÂ´n resolve qua service nÃƒÂ y.</li>
 *   <li>Ã„ÂÃ¡Â»â€œng thÃ¡Â»Âi: seat lock + ticket creation + price snapshot cÃƒÂ¹ng transaction (Req 12).</li>
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
    private final com.cinema.dao.pricing.HolidayDAO holidayDao;
    private final PricingConfigDAO pricingConfigDao;

    public PricingService(PriceRuleDAO priceRuleDao, ShowtimeDAO showtimeDao) {
        this(priceRuleDao, showtimeDao, null, new PricingConfigDAO());
    }

    public PricingService(PriceRuleDAO priceRuleDao, ShowtimeDAO showtimeDao,
                          com.cinema.dao.pricing.HolidayDAO holidayDao,
                          PricingConfigDAO pricingConfigDao) {
        this.priceRuleDao = priceRuleDao;
        this.showtimeDao = showtimeDao;
        this.holidayDao = holidayDao;
        this.pricingConfigDao = pricingConfigDao;
    }

    /**
     * BÃƒÂ¡o giÃƒÂ¡ vÃƒÂ© theo loÃ¡ÂºÂ¡i ghÃ¡ÂºÂ¿ vÃƒÂ  loÃ¡ÂºÂ¡i vÃƒÂ©, khÃƒÂ´ng ÃƒÂ¡p dÃ¡Â»Â¥ng Ã†Â°u Ã„â€˜ÃƒÂ£i thÃƒÂ nh viÃƒÂªn hoÃ¡ÂºÂ·c voucher.
     */
    public PricedOrder calcOrder(long showtimeId, List<String> seatTypes,
                                 TicketType ticketType) throws Exception {
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("SuÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
        if (seatTypes == null || seatTypes.isEmpty()) {
            throw new ServiceException.Validation("Ã„ÂÃ†Â¡n vÃƒÂ© phÃ¡ÂºÂ£i cÃƒÂ³ ÃƒÂ­t nhÃ¡ÂºÂ¥t mÃ¡Â»â„¢t ghÃ¡ÂºÂ¿");
        }

        long baseTotal = 0;
        for (String seatType : seatTypes) {
            baseTotal += priceSeatFor(showtime, seatType, ticketType).price();
        }

        return new PricedOrder(showtimeId, baseTotal, 0, 0, baseTotal);
    }

    /** KÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ bÃƒÂ¡o giÃƒÂ¡ Ã„â€˜Ã†Â¡n vÃƒÂ©. */
    public record PricedOrder(long showtimeId, long baseTotal, long tierDiscount,
                              long voucherDiscount, long payable) { }

    // ============================================================
    //  CRUD rule Ã¢â‚¬â€ Admin only (Caller must enforce auth, Req 14)
    // ============================================================

    /** TÃ¡ÂºÂ¡o rule SYSTEM (seat_type Ãƒâ€” day_type Ãƒâ€” time_slot). Reuse cho Req 5.1. */
    public PriceRule createRule(String seatType, String dayType, String timeSlot, long price) throws Exception {
        return createRule(seatType, dayType, timeSlot, price, null, null, null, null);
    }

    /**
     * TÃ¡ÂºÂ¡o rule mÃ¡Â»â€ºi vÃ¡Â»â€ºi override optional (Req 7). Ã„ÂÃƒÂºng mÃ¡Â»â„¢t trong movieId/branchId showtimeId
     * Ã„â€˜Ã†Â°Ã¡Â»Â£c phÃƒÂ©p NOT NULL; movieId + branchId kÃ¡ÂºÂ¿t hÃ¡Â»Â£p Ã„â€˜Ã†Â°Ã¡Â»Â£c Ã„â€˜Ã¡Â»Æ’ Ã„â€˜Ã¡Â»â€¹nh giÃƒÂ¡ riÃƒÂªng cho tÃ¡Â»Â«ng phim tÃ¡ÂºÂ¡i rÃ¡ÂºÂ¡p.
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
        String normalizedSeat = normalizeEnum(seatType, "LoÃ¡ÂºÂ¡i ghÃ¡ÂºÂ¿",
                List.of(PricingEngine.SeatType.STANDARD.name(), PricingEngine.SeatType.VIP.name(),
                        PricingEngine.SeatType.COUPLE.name()));
        String normalizedDay = normalizeEnum(dayType, "LoÃ¡ÂºÂ¡i ngÃƒÂ y",
                List.of(PricingEngine.DayType.WEEKDAY.name(), PricingEngine.DayType.WEEKEND.name(),
                        PricingEngine.DayType.HOLIDAY.name()));
        String normalizedSlot = normalizeEnum(timeSlot, "Khung giÃ¡Â»Â",
                List.of(SLOT_EARLY, SLOT_DAY, SLOT_LATE));
        if (price < 0) {
            throw new ServiceException.Validation("GiÃƒÂ¡ vÃƒÂ© khÃƒÂ´ng Ã„â€˜Ã†Â°Ã¡Â»Â£c ÃƒÂ¢m");
        }
        validateDateRange(activeFrom, activeTo);
        if (showtimeId != null && (movieId != null || branchId != null)) {
            throw new ServiceException.Validation(
                    "Khung giÃƒÂ¡ suÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u khÃƒÂ´ng Ã„â€˜Ã†Â°Ã¡Â»Â£c kÃ¡ÂºÂ¿t hÃ¡Â»Â£p vÃ¡Â»â€ºi scope phim hoÃ¡ÂºÂ·c chi nhÃƒÂ¡nh");
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

    /** Req 5.4 Ã¢â‚¬â€ chÃ¡Â»â€° ÃƒÂ¡p dÃ¡Â»Â¥ng cho vÃƒÂ© tÃ¡ÂºÂ¡o sau. */
    public PriceRule updateRulePrice(long ruleId, long price) throws Exception {
        PriceRule existing = priceRuleDao.findById(ruleId)
                .orElseThrow(() -> new ServiceException.NotFound("Khung giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
        return updateRulePrice(ruleId, price, existing.activeFrom(), existing.activeTo());
    }

    public PriceRule updateRulePrice(long ruleId, long price, LocalDate activeFrom,
                                     LocalDate activeTo) throws Exception {
        if (price < 0) {
            throw new ServiceException.Validation("GiÃƒÂ¡ vÃƒÂ© khÃƒÂ´ng Ã„â€˜Ã†Â°Ã¡Â»Â£c ÃƒÂ¢m");
        }
        validateDateRange(activeFrom, activeTo);
        PriceRule rule = priceRuleDao.findById(ruleId)
                .orElseThrow(() -> new ServiceException.NotFound("Khung giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
        priceRuleDao.updatePriceAndDates(ruleId, price, activeFrom, activeTo);
        rule.setPrice(price);
        rule.setActiveFrom(activeFrom);
        rule.setActiveTo(activeTo);
        logger.info("Updated price rule " + ruleId + " Ã¢â€ â€™ " + price);
        return rule;
    }

    private static void validateDateRange(LocalDate activeFrom, LocalDate activeTo) {
        if ((activeFrom == null) != (activeTo == null)) {
            throw new ServiceException.Validation("PhÃ¡ÂºÂ£i nhÃ¡ÂºÂ­p cÃ¡ÂºÂ£ ngÃƒÂ y bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u vÃƒÂ  ngÃƒÂ y kÃ¡ÂºÂ¿t thÃƒÂºc, hoÃ¡ÂºÂ·c Ã„â€˜Ã¡Â»Æ’ trÃ¡Â»â€˜ng cÃ¡ÂºÂ£ hai");
        }
        if (activeFrom != null && activeTo.isBefore(activeFrom)) {
            throw new ServiceException.Validation("NgÃƒÂ y kÃ¡ÂºÂ¿t thÃƒÂºc phÃ¡ÂºÂ£i bÃ¡ÂºÂ±ng hoÃ¡ÂºÂ·c sau ngÃƒÂ y bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u");
        }
    }

    public List<PriceRule> listRules() throws Exception {
        return priceRuleDao.findAll();
    }

    public void deleteRule(long ruleId) throws Exception {
        priceRuleDao.findById(ruleId)
                .orElseThrow(() -> new ServiceException.NotFound("Khung giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
        if (!priceRuleDao.delete(ruleId)) {
            throw new ServiceException.NotFound("Khung giÃƒÂ¡ khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i");
        }
        logger.info("Deleted price rule " + ruleId);
    }

    public long defaultTicketPrice() throws Exception {
        return pricingConfigDao.getDefaultTicketPrice();
    }

    public long updateDefaultTicketPrice(long price) throws Exception {
        if (price < 0) {
            throw new ServiceException.Validation("GiÃƒÂ¡ vÃƒÂ© mÃ¡ÂºÂ·c Ã„â€˜Ã¡Â»â€¹nh khÃƒÂ´ng Ã„â€˜Ã†Â°Ã¡Â»Â£c ÃƒÂ¢m");
        }
        return pricingConfigDao.updateDefaultTicketPrice(price);
    }

    // ============================================================
    //  Price resolution Ã¢â‚¬â€ Req 7 hierarchy
    // ============================================================

    public PricedSeat priceSeat(long showtimeId, String seatType) throws Exception {
        return priceSeat(showtimeId, seatType, null);
    }

    public PricedSeat priceSeat(long showtimeId, String seatType, TicketType ticketType) throws Exception {
        Showtime showtime = showtimeDao.findById(showtimeId)
                .orElseThrow(() -> new ServiceException.NotFound("SuÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
        return priceSeatFor(showtime, seatType, ticketType);
    }

    public PricedSeat priceSeatFor(Showtime showtime, String seatType) throws Exception {
        return priceSeatFor(showtime, seatType, null);
    }

    /**
     * TÃƒÂ­nh giÃƒÂ¡ snapshot cho tÃ¡Â»Â«ng ghÃ¡ÂºÂ¿ cÃ¡Â»Â§a lÃ¡Â»â€¹ch chiÃ¡ÂºÂ¿u Ã¢â‚¬â€ Ã„â€˜ÃƒÂ¢y lÃƒÂ  entry point duy nhÃ¡ÂºÂ¥t
     * mÃƒÂ  {@code BookingService} gÃ¡Â»Âi khi tÃ¡ÂºÂ¡o vÃƒÂ© (Req 11).
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
                .orElseThrow(() -> new ServiceException.NotFound("SuÃ¡ÂºÂ¥t chiÃ¡ÂºÂ¿u khÃƒÂ´ng tÃ¡Â»â€œn tÃ¡ÂºÂ¡i"));
        return priceTableFor(showtime, ticketType);
    }

    /** Resolve giÃƒÂ¡ vÃ¡Â»â€ºi hierarchy Ã„â€˜Ã¡ÂºÂ§y Ã„â€˜Ã¡Â»Â§ (Req 7.4). */
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
     * PhÃƒÂ¢n cÃ¡ÂºÂ¥p Ã†Â°u tiÃƒÂªn (Req 7.4): Showtime Ã¢â€ â€™ Movie+Time Ã¢â€ â€™ Branch Ã¢â€ â€™ System Ã¢â€ â€™ Default.
     * Trong cÃƒÂ¹ng scope, Ã†Â°u tiÃƒÂªn rule cÃƒÂ³ ticket_type khÃ¡Â»â€ºp.
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
                "GiÃƒÂ¡ mÃ¡ÂºÂ·c Ã„â€˜Ã¡Â»â€¹nh Ã„â€˜Ã†Â°Ã¡Â»Â£c cÃ¡ÂºÂ¥u hÃƒÂ¬nh");
    }

    /**
     * TÃƒÂ¬m rule khÃ¡Â»â€ºp trong mÃ¡Â»â„¢t scope. Khi cÃƒÂ³ nhiÃ¡Â»Âu rule cÃƒÂ¹ng hoÃ¡ÂºÂ¡t Ã„â€˜Ã¡Â»â„¢ng, Ã†Â°u tiÃƒÂªn ticket type
     * chÃƒÂ­nh xÃƒÂ¡c trÃ†Â°Ã¡Â»â€ºc, sau Ã„â€˜ÃƒÂ³ khoÃ¡ÂºÂ£ng ngÃƒÂ y cÃ¡Â»Â¥ thÃ¡Â»Æ’ nhÃ¡ÂºÂ¥t (ngÃƒÂ y bÃ¡ÂºÂ¯t Ã„â€˜Ã¡ÂºÂ§u mÃ¡Â»â€ºi nhÃ¡ÂºÂ¥t, ngÃƒÂ y kÃ¡ÂºÂ¿t thÃƒÂºc sÃ¡Â»â€ºm nhÃ¡ÂºÂ¥t).
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

    /** PhÃƒÂ¢n loÃ¡ÂºÂ¡i ngÃƒÂ y. */
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

    /** PhÃƒÂ¢n loÃ¡ÂºÂ¡i khung giÃ¡Â»Â. */
    static String timeSlotOf(LocalTime time) {
        if (time.isBefore(EARLY_CUTOFF)) return SLOT_EARLY;
        if (!time.isBefore(LATE_CUTOFF)) return SLOT_LATE;
        return SLOT_DAY;
    }

    /** Cache tÃ¡ÂºÂ¥t cÃ¡ÂºÂ£ rule (chia scope) Ã¢â‚¬â€ load 1 lÃ¡ÂºÂ§n / call. */
    private AllRules loadAllRules() throws Exception {
        List<PriceRule> all = priceRuleDao.findAll();
        AllRules result = new AllRules();
        result.holidayMonthDays = holidayDao == null ? Set.of() : holidayDao.findEnabledMonthDays();
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
            throw new ServiceException.Validation("LoÃ¡ÂºÂ¡i ghÃ¡ÂºÂ¿ lÃƒÂ  bÃ¡ÂºÂ¯t buÃ¡Â»â„¢c");
        }
        try {
            return PricingEngine.SeatType.valueOf(seatType.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ServiceException.Validation("LoÃ¡ÂºÂ¡i ghÃ¡ÂºÂ¿ phÃ¡ÂºÂ£i lÃƒÂ  STANDARD, VIP hoÃ¡ÂºÂ·c COUPLE");
        }
    }

    private String normalizeEnum(String value, String label, List<String> allowed) {
        if (value == null || value.isBlank()) {
            throw new ServiceException.Validation(label + " lÃƒÂ  bÃ¡ÂºÂ¯t buÃ¡Â»â„¢c");
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new ServiceException.Validation(label + " phÃ¡ÂºÂ£i lÃƒÂ  mÃ¡Â»â„¢t trong: " + String.join(", ", allowed));
        }
        return normalized;
    }

    private String explain(PricingEngine.SeatType type, PricingEngine.DayType dayType,
                           String timeSlot, TicketType ticketType, Resolution r,
                           LocalDateTime start, Showtime showtime) {
        StringBuilder sb = new StringBuilder("GiÃƒÂ¡ ").append(r.price).append("Ã„â€˜ cho ghÃ¡ÂºÂ¿ ").append(type);
        sb.append(" suÃ¡ÂºÂ¥t ").append(start).append(" (").append(dayType).append("/").append(timeSlot).append(")");
        if (ticketType != null) {
            sb.append(" ticketType=").append(ticketType.code());
        }
        switch (r.source) {
            case SOURCE_SHOWTIME -> sb.append(" Ã¢â‚¬â€ lÃ¡ÂºÂ¥y tÃ¡Â»Â« khung giÃƒÂ¡ riÃƒÂªng cho showtime #").append(showtime.id());
            case SOURCE_MOVIE_BRANCH -> sb.append(" Ã¢â‚¬â€ lÃ¡ÂºÂ¥y tÃ¡Â»Â« khung giÃƒÂ¡ riÃƒÂªng cho phim tÃ¡ÂºÂ¡i chi nhÃƒÂ¡nh");
            case SOURCE_MOVIE -> sb.append(" Ã¢â‚¬â€ lÃ¡ÂºÂ¥y tÃ¡Â»Â« khung giÃƒÂ¡ theo Movie #").append(showtime.movieId());
            case SOURCE_BRANCH -> sb.append(" Ã¢â‚¬â€ lÃ¡ÂºÂ¥y tÃ¡Â»Â« khung giÃƒÂ¡ theo Branch #").append(showtime.branchId());
            case SOURCE_SYSTEM -> sb.append(" Ã¢â‚¬â€ lÃ¡ÂºÂ¥y tÃ¡Â»Â« khung giÃƒÂ¡ hÃ¡Â»â€¡ thÃ¡Â»â€˜ng khÃ¡Â»â€ºp tÃ¡Â»â€¢ hÃ¡Â»Â£p");
            default -> sb.append(" Ã¢â‚¬â€ khÃƒÂ´ng cÃƒÂ³ khung giÃƒÂ¡ khÃ¡Â»â€ºp, ÃƒÂ¡p giÃƒÂ¡ mÃ¡ÂºÂ·c Ã„â€˜Ã¡Â»â€¹nh");
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

    /** KÃ¡ÂºÂ¿t quÃ¡ÂºÂ£ tÃƒÂ­nh giÃƒÂ¡ mÃ¡Â»â„¢t ghÃ¡ÂºÂ¿ kÃƒÂ¨m diÃ¡Â»â€¦n giÃ¡ÂºÂ£i (Req 5.5, 7.6). */
    public record PricedSeat(long showtimeId, String seatType, String dayType, String timeSlot,
                             long price, String source, String explanation) { }
}