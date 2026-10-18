package com.cinema.model.pricing;

import com.cinema.model.branch.Branch;
import com.cinema.model.movie.Movie;
import com.cinema.model.showtime.Showtime;

import java.util.List;

/**
 * Loại vé do Customer chọn lúc booking — ảnh hưởng đến giá (Req 9).
 *
 * <p>Phân biệt với {@link PricingEngine.SeatType} (loại ghế — STANDARD/VIP/COUPLE).
 * TicketType = hồ sơ người mua (ADULT/CHILD/STUDENT) hoặc trạng thái đặc biệt (VIP).
 * Một ghế có thể bán cho bất kỳ ticket type nào; giá resolve theo priority
 * Showtime → Movie+Time → Branch → System, sau đó ưu tiên rule có {@code ticket_type}
 * khớp trước rule cùng scope nhưng ticket_type NULL (Req 7.4).</p>
 */
public enum TicketType {
    ADULT, CHILD, STUDENT, VIP;

    /** Hằng số DB dùng trong {@code dbo.price_rule.ticket_type} và {@code dbo.ticket_seat.ticket_type}. */
    public String code() {
        return name();
    }

    public static TicketType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return TicketType.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<String> codes() {
        return List.of(ADULT.code(), CHILD.code(), STUDENT.code(), VIP.code());
    }
}