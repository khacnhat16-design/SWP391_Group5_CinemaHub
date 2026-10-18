package com.cinema.service.pricing;

import com.cinema.common.ServiceException;
import com.cinema.dao.pricing.PriceRuleDAO;
import com.cinema.dao.pricing.PricingConfigDAO;
import com.cinema.model.branch.Branch;
import com.cinema.model.movie.Movie;
import com.cinema.model.pricing.PriceRule;
import com.cinema.model.pricing.TicketType;
import com.cinema.service.pricing.PricingService;

import com.cinema.model.booking.Ticket;
import com.cinema.model.showtime.Showtime;
import com.cinema.dao.showtime.ShowtimeDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho PricingService — kiểm tra thứ tự ưu tiên dynamic pricing (Req 7).
 *
 * <p>Hierarchy (giảm dần): Showtime-specific → Movie+Time → Branch → System → Default.
 * Stub DAO trả về danh sách PriceRule cố định; stub ShowtimeDAO trả về một Showtime cố định.</p>
 */
class PricingServiceTest {

    private StubPriceRuleDAO dao;
    private StubShowtimeDAO showtimeDao;
    private PricingService service;

    private static final long SHOWTIME_ID = 100L;
    private static final long MOVIE_A = 1L;
    private static final long MOVIE_B = 2L;
    private static final long BRANCH_X = 10L;

    @BeforeEach
    void setUp() {
        dao = new StubPriceRuleDAO();
        showtimeDao = new StubShowtimeDAO();
        showtimeDao.stubShowtime(showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X));
        service = new PricingService(dao, showtimeDao, new StubPricingConfigDAO(80_000L));
    }

    private Showtime showtimeForShowtime(long id, long movieId, long branchId) {
        Showtime s = new Showtime();
        s.setId(id);
        s.setMovieId(movieId);
        s.setBranchId(branchId);
        // WEEKDAY 14:00 → DAY slot
        s.setStartTime(LocalDateTime.of(2026, 10, 5, 14, 0));
        return s;
    }

    @Test
    void systemRuleAppliesWhenNoOverrides() throws Exception {
        dao.add(rule(100L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));

        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        PricingService.PricedSeat seat = service.priceSeatFor(s, "STANDARD", null);

        assertEquals(80_000L, seat.price(), "Phải khớp system rule");
        assertEquals(PricingService.SOURCE_SYSTEM, seat.source());
    }

    @Test
    void showtimeSpecificRuleWinsOverEverything() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));   // system
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "DAY", 90_000L, null, BRANCH_X, null, null)); // branch
        dao.add(rule(3L, "STANDARD", "WEEKDAY", "DAY", 110_000L, MOVIE_A, null, null, null)); // movie
        dao.add(rule(4L, "STANDARD", "WEEKDAY", "DAY", 130_000L, null, null, SHOWTIME_ID, null)); // showtime

        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        PricingService.PricedSeat seat = service.priceSeatFor(s, "STANDARD", null);

        assertEquals(130_000L, seat.price(), "Showtime-specific phải thắng mọi scope");
        assertEquals(PricingService.SOURCE_SHOWTIME, seat.source());
    }

    @Test
    void movieRuleWinsWhenNoShowtimeSpecific() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));     // system
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "DAY", 90_000L, null, BRANCH_X, null, null));  // branch
        dao.add(rule(3L, "STANDARD", "WEEKDAY", "DAY", 110_000L, MOVIE_A, null, null, null)); // movie (Movie A)
        dao.add(rule(4L, "STANDARD", "WEEKDAY", "DAY", 150_000L, MOVIE_B, null, null, null)); // movie (Movie B — should NOT match)

        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        PricingService.PricedSeat seat = service.priceSeatFor(s, "STANDARD", null);

        assertEquals(110_000L, seat.price(), "Movie A phải thắng — Movie B không match");
        assertEquals(PricingService.SOURCE_MOVIE, seat.source());
    }

    @Test
    void movieBranchRuleWinsOverMovieAndBranchDefaults() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 85_000L, null, null, null, null));
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "DAY", 95_000L, MOVIE_A, null, null, null));
        dao.add(rule(3L, "STANDARD", "WEEKDAY", "DAY", 105_000L, null, BRANCH_X, null, null));
        dao.add(rule(4L, "STANDARD", "WEEKDAY", "DAY", 115_000L, MOVIE_A, BRANCH_X, null, null));

        PricingService.PricedSeat seat = service.priceSeatFor(
                showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X), "STANDARD", null);

        assertEquals(115_000L, seat.price());
        assertEquals(PricingService.SOURCE_MOVIE_BRANCH, seat.source());
    }

    @Test
    void dateRangeRulesSwitchPriceForShowtimesAndIncludeBothEndpoints() throws Exception {
        PriceRule before = rule(20L, "STANDARD", "WEEKDAY", "DAY", 90_000L,
                MOVIE_A, BRANCH_X, null, null);
        before.setActiveFrom(LocalDate.of(2026, 10, 1));
        before.setActiveTo(LocalDate.of(2026, 10, 8));
        PriceRule after = rule(21L, "STANDARD", "WEEKDAY", "DAY", 110_000L,
                MOVIE_A, BRANCH_X, null, null);
        after.setActiveFrom(LocalDate.of(2026, 10, 9));
        after.setActiveTo(LocalDate.of(2026, 10, 20));
        dao.add(before);
        dao.add(after);

        Showtime firstDay = showtimeForShowtime(101L, MOVIE_A, BRANCH_X);
        firstDay.setStartTime(LocalDateTime.of(2026, 10, 8, 14, 0));
        Showtime secondDay = showtimeForShowtime(102L, MOVIE_A, BRANCH_X);
        secondDay.setStartTime(LocalDateTime.of(2026, 10, 9, 14, 0));

        assertEquals(90_000L, service.priceSeatFor(firstDay, "STANDARD", null).price());
        assertEquals(110_000L, service.priceSeatFor(secondDay, "STANDARD", null).price());
    }

    @Test
    void narrowerApplicableDateRangeTakesPrecedence() throws Exception {
        PriceRule broad = rule(22L, "STANDARD", "WEEKDAY", "DAY", 100_000L,
                MOVIE_A, BRANCH_X, null, null);
        PriceRule narrow = rule(23L, "STANDARD", "WEEKDAY", "DAY", 120_000L,
                MOVIE_A, BRANCH_X, null, null);
        narrow.setActiveFrom(LocalDate.of(2026, 10, 5));
        narrow.setActiveTo(LocalDate.of(2026, 10, 7));
        dao.add(broad);
        dao.add(narrow);

        assertEquals(120_000L, service.priceSeatFor(
                showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X), "STANDARD", null).price());
    }

    @Test
    void ruleOutsideItsDateRangeFallsBackToSystemPricing() throws Exception {
        PriceRule expired = rule(24L, "STANDARD", "WEEKDAY", "DAY", 120_000L,
                MOVIE_A, BRANCH_X, null, null);
        expired.setActiveFrom(java.time.LocalDate.of(2026, 9, 1));
        expired.setActiveTo(java.time.LocalDate.of(2026, 9, 30));
        dao.add(expired);
        dao.add(rule(25L, "STANDARD", "WEEKDAY", "DAY", 85_000L,
                null, null, null, null));

        assertEquals(85_000L, service.priceSeatFor(
                showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X), "STANDARD", null).price());
    }

    @Test
    void invalidDateRangeIsRejectedBeforePersistence() {
        assertThrows(com.cinema.common.ServiceException.Validation.class, () ->
                service.createRule("STANDARD", "WEEKDAY", "DAY", 100_000L,
                        MOVIE_A, BRANCH_X, null, null,
                        LocalDate.of(2026, 10, 10),
                        LocalDate.of(2026, 10, 9)));
    }

    @Test
    void branchRuleWinsWhenNoMovieOrShowtime() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));     // system
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "DAY", 90_000L, null, BRANCH_X, null, null));  // branch X
        dao.add(rule(3L, "STANDARD", "WEEKDAY", "DAY", 95_000L, null, 99L, null, null));       // branch khác — không match

        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        PricingService.PricedSeat seat = service.priceSeatFor(s, "STANDARD", null);

        assertEquals(90_000L, seat.price(), "Branch default phải thắng khi không có rule cao hơn");
        assertEquals(PricingService.SOURCE_BRANCH, seat.source());
    }

    @Test
    void defaultAppliesWhenNothingMatches() throws Exception {
        // Không có rule nào — dùng giá mặc định cấu hình từ DAO.
        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        PricingService.PricedSeat seat = service.priceSeatFor(s, "STANDARD", null);

        assertEquals(80_000L, seat.price());
        assertEquals(PricingService.SOURCE_DEFAULT, seat.source());
    }

    @Test
    void timeSlotAffectsMatch() throws Exception {
        // System rule DAY=80k, rule LATE=120k
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "LATE", 120_000L, null, null, null, null));

        Showtime early = new Showtime();
        early.setId(1L); early.setMovieId(MOVIE_A); early.setBranchId(BRANCH_X);
        early.setStartTime(LocalDateTime.of(2026, 10, 5, 14, 0)); // DAY

        Showtime late = new Showtime();
        late.setId(2L); late.setMovieId(MOVIE_A); late.setBranchId(BRANCH_X);
        late.setStartTime(LocalDateTime.of(2026, 10, 5, 23, 0)); // LATE

        assertEquals(80_000L, service.priceSeatFor(early, "STANDARD", null).price());
        assertEquals(120_000L, service.priceSeatFor(late, "STANDARD", null).price());
    }

    @Test
    void dayTypeAffectsMatch() throws Exception {
        // WEEKDAY=80k, WEEKEND=120k
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));
        dao.add(rule(2L, "STANDARD", "WEEKEND", "DAY", 120_000L, null, null, null, null));

        Showtime weekday = new Showtime();
        weekday.setId(1L); weekday.setMovieId(MOVIE_A); weekday.setBranchId(BRANCH_X);
        weekday.setStartTime(LocalDateTime.of(2026, 10, 7, 14, 0)); // Wed → WEEKDAY

        Showtime weekend = new Showtime();
        weekend.setId(2L); weekend.setMovieId(MOVIE_A); weekend.setBranchId(BRANCH_X);
        weekend.setStartTime(LocalDateTime.of(2026, 10, 10, 14, 0)); // Sat → WEEKEND

        assertEquals(80_000L, service.priceSeatFor(weekday, "STANDARD", null).price());
        assertEquals(120_000L, service.priceSeatFor(weekend, "STANDARD", null).price());
    }

    @Test
    void ticketTypeExactMatchWinsOverNullInSameScope() throws Exception {
        // Showtime #SHOWTIME_ID: rule STUDENT=60k vs rule null=100k
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 100_000L, null, null, SHOWTIME_ID, null));
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "DAY", 60_000L, null, null, SHOWTIME_ID, "STUDENT"));

        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        // ADULT không match STUDENT → null rule → 100k
        assertEquals(100_000L, service.priceSeatFor(s, "STANDARD", TicketType.ADULT).price());
        // STUDENT match STUDENT → 60k
        assertEquals(60_000L, service.priceSeatFor(s, "STANDARD", TicketType.STUDENT).price());
    }

    @Test
    void ticketTypeDoesNotLeakAcrossShowtime() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 60_000L, null, null, 999L, "STUDENT"));
        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        // STUDENT rule chỉ apply cho showtime 999 — showtime này phải dùng default
        assertEquals(80_000L,
                service.priceSeatFor(s, "STANDARD", TicketType.STUDENT).price());
    }

    @Test
    void systemRulesRespectTicketTypeInsteadOfOverwritingEachOther() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 100_000L, null, null, null, null));
        dao.add(rule(2L, "STANDARD", "WEEKDAY", "DAY", 65_000L, null, null, null, "STUDENT"));
        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);

        assertEquals(100_000L, service.priceSeatFor(s, "STANDARD", TicketType.ADULT).price());
        assertEquals(65_000L, service.priceSeatFor(s, "STANDARD", TicketType.STUDENT).price());
    }

    @Test
    void mixedSeatTypesArePricedIndividuallyAndSummed() throws Exception {
        dao.add(rule(1L, "STANDARD", "WEEKDAY", "DAY", 80_000L, null, null, null, null));
        dao.add(rule(2L, "VIP", "WEEKDAY", "DAY", 120_000L, null, null, null, null));
        dao.add(rule(3L, "COUPLE", "WEEKDAY", "DAY", 200_000L, null, null, null, null));

        PricingService.PricedOrder order = service.calcOrder(SHOWTIME_ID,
                List.of("STANDARD", "VIP", "COUPLE"), null, null);
        assertEquals(400_000L, order.baseTotal());
        assertEquals(400_000L, order.baseTotal());
    }

    @Test
    void invalidSeatTypeIsRejected() {
        Showtime s = showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X);
        assertThrows(com.cinema.common.ServiceException.Validation.class,
                () -> service.priceSeatFor(s, "INVALID", null));
    }

    @Test
    void missingDefaultConfigurationFailsExplicitly() {
        service = new PricingService(dao, showtimeDao, new PricingConfigDAO() {
                    @Override
                    public long getDefaultTicketPrice() {
                        throw new IllegalStateException("missing pricing configuration");
                    }
                });

        assertThrows(IllegalStateException.class, () -> service.priceSeatFor(
                showtimeForShowtime(SHOWTIME_ID, MOVIE_A, BRANCH_X), "STANDARD", null));
    }

    @Test
    void snapshotPriceStoredOnTicket() {
        // Ticket.TicketSeat chứa price snapshot (Req 8) — test helper constructor backward-compatible.
        Ticket.TicketSeat seat = new Ticket.TicketSeat(1L, "A", 1, "STANDARD", 80_000L);
        assertEquals(80_000L, seat.price());
        assertNull(seat.ticketType(), "ticketType default null khi không chỉ định");
    }

    @Test
    void snapshotPriceWithTicketType() {
        Ticket.TicketSeat seat = new Ticket.TicketSeat(1L, "A", 1, "STANDARD", "STUDENT", 60_000L);
        assertEquals("STUDENT", seat.ticketType());
        assertEquals(60_000L, seat.price());
    }

    // ---- Stubs ----

    private static PriceRule rule(long id, String seatType, String dayType, String timeSlot,
                                  long price, Long movieId, Long branchId, Long showtimeId,
                                  String ticketType) {
        PriceRule r = new PriceRule();
        r.setId(id);
        r.setSeatType(seatType);
        r.setDayType(dayType);
        r.setTimeSlot(timeSlot);
        r.setPrice(price);
        r.setMovieId(movieId);
        r.setBranchId(branchId);
        r.setShowtimeId(showtimeId);
        r.setTicketType(ticketType);
        return r;
    }

    private static class StubPriceRuleDAO extends PriceRuleDAO {
        private final List<PriceRule> rules = new ArrayList<>();

        void add(PriceRule rule) { rules.add(rule); }

        @Override
        public List<PriceRule> findAll() {
            return new ArrayList<>(rules);
        }
    }

    private static class StubShowtimeDAO extends ShowtimeDAO {
        private final java.util.Map<Long, Showtime> map = new java.util.HashMap<>();

        void stubShowtime(Showtime s) { map.put(s.id(), s); }

        @Override
        public Optional<Showtime> findById(Long id) {
            return Optional.ofNullable(map.get(id));
        }
    }

    private static class StubPricingConfigDAO extends PricingConfigDAO {
        private final long defaultPrice;

        private StubPricingConfigDAO(long defaultPrice) { this.defaultPrice = defaultPrice; }

        @Override
        public long getDefaultTicketPrice() { return defaultPrice; }
    }
}