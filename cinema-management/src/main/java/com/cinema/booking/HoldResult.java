package com.cinema.booking;

public record HoldResult(boolean success, String holdId, String message) {
    public static HoldResult accepted(String holdId) {
        return new HoldResult(true, holdId, "Seats are held for 10 minutes.");
    }

    public static HoldResult rejected(String message) {
        return new HoldResult(false, null, message);
    }
}
