package com.cinema.model.pricing;

import java.time.LocalDate;

/** Price dimensions and effective dates captured by a reusable template. */
public class PriceTemplateRule {
    private Long id;
    private String seatType;
    private String dayType;
    private String timeSlot;
    private long price;
    private String ticketType;
    private LocalDate activeFrom;
    private LocalDate activeTo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSeatType() { return seatType; }
    public void setSeatType(String seatType) { this.seatType = seatType; }
    public String getDayType() { return dayType; }
    public void setDayType(String dayType) { this.dayType = dayType; }
    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
    public String getTicketType() { return ticketType; }
    public void setTicketType(String ticketType) { this.ticketType = ticketType; }
    public LocalDate getActiveFrom() { return activeFrom; }
    public void setActiveFrom(LocalDate activeFrom) { this.activeFrom = activeFrom; }
    public LocalDate getActiveTo() { return activeTo; }
    public void setActiveTo(LocalDate activeTo) { this.activeTo = activeTo; }
}

