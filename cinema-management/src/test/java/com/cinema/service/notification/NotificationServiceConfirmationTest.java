package com.cinema.service.notification;

import com.cinema.model.movie.Movie;
import com.cinema.model.booking.Ticket;
import com.cinema.dao.booking.TicketDAO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationServiceConfirmationTest {

    @Test
    void formatsMovieShowtimeAndSeatLabelsInsteadOfInternalIds() {
        TicketDAO.ConfirmationDetails details = new TicketDAO.ConfirmationDetails(
                "Example Movie",
                LocalDateTime.of(2026, 10, 5, 16, 0),
                List.of(
                        new Ticket.TicketSeat(41, "A", 1, "STANDARD", 100_000),
                        new Ticket.TicketSeat(76, "E", 4, "VIP", 150_000)));

        assertEquals("Example Movie · 05/10/2026 16:00",
                NotificationService.confirmationShowtimeInfo(details));
        assertEquals("A1, E4", NotificationService.confirmationSeatInfo(details));
    }
}
