package com.cinema.model.pricing;

import java.util.List;

/**
 * Loáº¡i vÃ© do Customer chá»n lÃºc booking â€” áº£nh hÆ°á»Ÿng Ä‘áº¿n giÃ¡ (Req 9).
 *
 * <p>PhÃ¢n biá»‡t vá»›i {@link PricingEngine.SeatType} (loáº¡i gháº¿ â€” STANDARD/VIP/COUPLE).
 * TicketType = há»“ sÆ¡ ngÆ°á»i mua (ADULT/CHILD/STUDENT) hoáº·c tráº¡ng thÃ¡i Ä‘áº·c biá»‡t (VIP).
 * Má»™t gháº¿ cÃ³ thá»ƒ bÃ¡n cho báº¥t ká»³ ticket type nÃ o; giÃ¡ resolve theo priority
 * Showtime â†’ Movie+Time â†’ Branch â†’ System, sau Ä‘Ã³ Æ°u tiÃªn rule cÃ³ {@code ticket_type}
 * khá»›p trÆ°á»›c rule cÃ¹ng scope nhÆ°ng ticket_type NULL (Req 7.4).</p>
 */
public enum TicketType {
    ADULT, CHILD, STUDENT, VIP;

    /** Háº±ng sá»‘ DB dÃ¹ng trong {@code dbo.price_rule.ticket_type} vÃ  {@code dbo.ticket_seat.ticket_type}. */
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
