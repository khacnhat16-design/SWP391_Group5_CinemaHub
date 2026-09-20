package com.cinema.auth;

/** Customer profile — extends User for retail customers. */
public class CustomerProfile {
    private Long userId;
    private int points;
    private String tier; // STANDARD, BRONZE, SILVER, GOLD

    public CustomerProfile(Long userId) {
        this.userId = userId;
        this.points = 0;
        this.tier = "STANDARD";
    }

    public Long userId() { return userId; }
    public int points() { return points; }
    public String tier() { return tier; }

    public void setPoints(int points) { this.points = points; }
    public void setTier(String tier) { this.tier = tier; }
}
