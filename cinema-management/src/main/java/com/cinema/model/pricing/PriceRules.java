package com.cinema.model.pricing;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class PriceRules {
    private final Map<Key, Long> values = new HashMap<>();

    public PriceRules add(PricingEngine.SeatType seatType, PricingEngine.DayType dayType,
                          String timeSlot, long price) {
        if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
        values.put(new Key(seatType, dayType, timeSlot), price);
        return this;
    }

    public Optional<Long> find(PricingEngine.SeatType seatType, PricingEngine.DayType dayType,
                               String timeSlot) {
        return Optional.ofNullable(values.get(new Key(seatType, dayType, timeSlot)));
    }

    private record Key(PricingEngine.SeatType seatType, PricingEngine.DayType dayType,
                       String timeSlot) { }
}
