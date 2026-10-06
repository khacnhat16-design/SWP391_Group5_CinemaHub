package com.cinema.booking;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingServiceCheckInWindowTest {

    @Test
    void allowsCheckInWithinThirtyMinutesBeforeShowtime() {
        LocalDateTime showtimeStart = LocalDateTime.of(2026, 9, 29, 15, 10);

        assertFalse(BookingService.isTooEarlyForCheckIn(
                LocalDateTime.of(2026, 9, 29, 14, 45), showtimeStart));
    }

    @Test
    void rejectsCheckInMoreThanThirtyMinutesBeforeShowtime() {
        LocalDateTime showtimeStart = LocalDateTime.of(2026, 9, 29, 15, 10);

        assertTrue(BookingService.isTooEarlyForCheckIn(
                LocalDateTime.of(2026, 9, 29, 14, 39), showtimeStart));
        assertFalse(BookingService.isTooEarlyForCheckIn(
                LocalDateTime.of(2026, 9, 29, 14, 40), showtimeStart));
    }
}
