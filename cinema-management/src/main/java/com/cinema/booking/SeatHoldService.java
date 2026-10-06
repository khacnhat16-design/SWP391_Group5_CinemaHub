package com.cinema.booking;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory reference implementation of the seat-hold state machine.
 * Production JDBC DAOs use SQL Server UPDLOCK/HOLDLOCK/ROWLOCK to preserve
 * the same atomic transition guarantees across concurrent web requests.
 */
public final class SeatHoldService {
    private static final long HOLD_SECONDS = 10 * 60;

    private final Clock clock;
    private final Map<Long, Map<String, SeatState>> seatsByShowtime = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();

    public SeatHoldService(Clock clock) {
        this.clock = clock;
    }

    public synchronized void registerShowtime(long showtimeId, Set<String> seatCodes) {
        if (showtimeId < 1 || seatCodes == null || seatCodes.isEmpty()) {
            throw new IllegalArgumentException("Showtime and seats are required");
        }
        Map<String, SeatState> seats = new HashMap<>();
        for (String seatCode : seatCodes) {
            if (seatCode == null || seatCode.isBlank()) {
                throw new IllegalArgumentException("Seat code is required");
            }
            seats.put(seatCode, new SeatState(SeatStatus.AVAILABLE, null));
        }
        seatsByShowtime.put(showtimeId, seats);
    }

    public synchronized HoldResult holdSeats(long showtimeId, long customerId, Set<String> requestedSeats) {
        releaseExpired(clock.instant());
        if (customerId < 1 || requestedSeats == null || requestedSeats.isEmpty()) {
            return HoldResult.rejected("Select at least one available seat.");
        }
        Map<String, SeatState> seats = seatsByShowtime.get(showtimeId);
        if (seats == null) {
            return HoldResult.rejected("Showtime is unavailable.");
        }
        LinkedHashSet<String> orderedSeats = requestedSeats.stream()
                .sorted(Comparator.naturalOrder())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String seatCode : orderedSeats) {
            SeatState state = seats.get(seatCode);
            if (state == null || state.status != SeatStatus.AVAILABLE) {
                return HoldResult.rejected("Seat " + seatCode + " is no longer available.");
            }
        }
        String holdId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plusSeconds(HOLD_SECONDS);
        for (String seatCode : orderedSeats) {
            seats.put(seatCode, new SeatState(SeatStatus.HOLD, holdId));
        }
        holds.put(holdId, new Hold(showtimeId, customerId, orderedSeats, expiresAt));
        return HoldResult.accepted(holdId);
    }

    public synchronized boolean confirm(String holdId, long customerId, Instant at) {
        releaseExpired(at);
        Hold hold = holds.get(holdId);
        if (hold == null || hold.customerId != customerId || !at.isBefore(hold.expiresAt)) {
            return false;
        }
        Map<String, SeatState> seats = seatsByShowtime.get(hold.showtimeId);
        for (String seatCode : hold.seatCodes) {
            SeatState state = seats.get(seatCode);
            if (state == null || state.status != SeatStatus.HOLD || !holdId.equals(state.holdId)) {
                return false;
            }
        }
        for (String seatCode : hold.seatCodes) {
            seats.put(seatCode, new SeatState(SeatStatus.SOLD, null));
        }
        holds.remove(holdId);
        return true;
    }

    public synchronized boolean releaseExpired(Instant at) {
        boolean released = false;
        var iterator = holds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Hold> entry = iterator.next();
            Hold hold = entry.getValue();
            if (!at.isBefore(hold.expiresAt)) {
                Map<String, SeatState> seats = seatsByShowtime.get(hold.showtimeId);
                for (String seatCode : hold.seatCodes) {
                    SeatState state = seats.get(seatCode);
                    if (state != null && state.status == SeatStatus.HOLD && entry.getKey().equals(state.holdId)) {
                        seats.put(seatCode, new SeatState(SeatStatus.AVAILABLE, null));
                    }
                }
                iterator.remove();
                released = true;
            }
        }
        return released;
    }

    public synchronized SeatStatus statusOf(long showtimeId, String seatCode) {
        releaseExpired(clock.instant());
        Map<String, SeatState> seats = seatsByShowtime.get(showtimeId);
        if (seats == null || !seats.containsKey(seatCode)) {
            throw new IllegalArgumentException("Seat does not exist for showtime.");
        }
        return seats.get(seatCode).status;
    }

    private record SeatState(SeatStatus status, String holdId) { }

    private record Hold(long showtimeId, long customerId, Set<String> seatCodes, Instant expiresAt) { }
}
