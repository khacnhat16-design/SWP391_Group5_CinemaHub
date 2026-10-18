package com.cinema.model.pricing;

import com.cinema.model.screen.Seat;

public final class PricingEngine {
    public enum SeatType { STANDARD, VIP, COUPLE }
    public enum DayType { WEEKDAY, WEEKEND, HOLIDAY }

    public long seatPrice(SeatType seatType, DayType dayType, String timeSlot,
                          PriceRules rules, long defaultPrice) {
        if (seatType == null || dayType == null || timeSlot == null || timeSlot.isBlank()) {
            throw new IllegalArgumentException("Seat type, day type and time slot are required");
        }
        if (defaultPrice < 0) throw new IllegalArgumentException("Default price cannot be negative");
        return rules.find(seatType, dayType, timeSlot).orElse(defaultPrice);
    }
}
