package com.cinema.service.screen;

import com.cinema.model.screen.Seat;
import com.cinema.common.ServiceException;
import com.cinema.common.SerializationUtil;
import com.cinema.dao.screen.ScreenDAO;
import com.cinema.model.screen.Screen;
import com.cinema.service.screen.ScreenService.SeatLayoutInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScreenServiceTest {
    private final ScreenService service = new ScreenService(null, null, null);

    @Test
    void customLayoutKeepsHolesAndSeatTypes() {
        List<Seat> seats = service.normalizeLayout(7, 3, 4, List.of(
                new SeatLayoutInput("A", 1, "STANDARD"),
                new SeatLayoutInput("A", 3, "VIP"),
                new SeatLayoutInput("C", 4, "COUPLE")));

        assertEquals(3, seats.size());
        assertEquals("VIP", seats.get(1).seatType());
        assertEquals("C", seats.get(2).rowLabel());
        assertEquals("ACTIVE", seats.get(2).status());
    }

    @Test
    void customLayoutRejectsDuplicatePositions() {
        assertThrows(ServiceException.Validation.class, () -> service.normalizeLayout(7, 2, 3,
                List.of(new SeatLayoutInput("A", 1, "STANDARD"),
                        new SeatLayoutInput("A", 1, "VIP"))));
    }

    @Test
    void customLayoutRejectsSeatsOutsideConfiguredBounds() {
        assertThrows(ServiceException.Validation.class, () -> service.normalizeLayout(7, 2, 3,
                List.of(new SeatLayoutInput("C", 1, "STANDARD"))));
    }

    @Test
    void customLayoutRejectsUnknownSeatTypesAndEmptyMaps() {
        assertThrows(ServiceException.Validation.class, () -> service.normalizeLayout(7, 2, 3,
                List.of(new SeatLayoutInput("A", 1, "DELUXE"))));
        assertThrows(ServiceException.Validation.class, () -> service.normalizeLayout(7, 2, 3, List.of()));
    }

    @Test
    void customLayoutPayloadDeserializesSeatTypeAndPosition() throws Exception {
        SeatLayoutInput[] layout = SerializationUtil.fromJson(
                "[{\"rowLabel\":\"B\",\"colNo\":3,\"seatType\":\"VIP\"}]",
                SeatLayoutInput[].class);

        assertEquals(1, layout.length);
        assertEquals("B", layout[0].rowLabel());
        assertEquals(3, layout[0].colNo());
        assertEquals("VIP", layout[0].seatType());
    }

    @Test
    void restoreScreenChangesInactiveScreenToActive() throws Exception {
        Screen screen = new Screen();
        screen.setId(12L);
        screen.setBranchId(7L);
        screen.setStatus("INACTIVE");
        TestScreenDAO screenDao = new TestScreenDAO(screen);
        ScreenService restoreService = new ScreenService(screenDao, null, null);

        Screen restored = restoreService.restore(7L, 12L);

        assertEquals("ACTIVE", restored.status());
        assertEquals("ACTIVE", screenDao.updatedStatus);
    }

    @Test
    void restoreScreenRejectsScreenThatIsNotInactive() {
        Screen screen = new Screen();
        screen.setId(12L);
        screen.setBranchId(7L);
        screen.setStatus("ACTIVE");
        TestScreenDAO screenDao = new TestScreenDAO(screen);

        ServiceException.BusinessRule error = assertThrows(
                ServiceException.BusinessRule.class,
                () -> new ScreenService(screenDao, null, null).restore(7L, 12L));

        assertEquals("SCREEN_NOT_INACTIVE", error.code());
    }

    private static final class TestScreenDAO extends ScreenDAO {
        private final Screen screen;
        private String updatedStatus;

        private TestScreenDAO(Screen screen) {
            this.screen = screen;
        }

        @Override
        public Optional<Screen> findById(Long id) {
            return Optional.of(screen);
        }

        @Override
        public void update(Screen updated) {
            updatedStatus = updated.status();
        }
    }
}
