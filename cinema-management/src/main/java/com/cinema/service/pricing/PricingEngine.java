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
