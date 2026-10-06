package com.cinema.booking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingServiceRefundPolicyTest {

    @Test
    void calculatesRefundWindowInVietnamLocalTime() {
        Instant now = Instant.parse("2026-10-03T16:00:00Z"); // 23:00 in Vietnam

        assertEquals(50, BookingService.refundRatePercent(
                now, LocalDateTime.of(2026, 10, 4, 19, 0))); // 20 hours remain
        assertEquals(100, BookingService.refundRatePercent(
                now, LocalDateTime.of(2026, 10, 4, 23, 0))); // exactly 24 hours
        assertEquals(0, BookingService.refundRatePercent(
                Instant.parse("2026-10-04T11:00:00Z"), // 18:00 in Vietnam
                LocalDateTime.of(2026, 10, 4, 19, 0))); // 1 hour remains
    }
}
