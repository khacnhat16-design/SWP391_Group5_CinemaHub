package com.cinema.model.auth;

/** Customer profile — extends User for retail customers. */
public class CustomerProfile {
    private Long userId;
    private int points;
    private String tier; // STANDARD, BRONZE, SILVER, GOLD
    private String bankName;
    private String bankAccountName;
    private String bankAccountNumber;

    public CustomerProfile(Long userId) {
        this.userId = userId;
        this.points = 0;
        this.tier = "STANDARD";
    }

    public Long userId() { return userId; }
    public int points() { return points; }
    public String tier() { return tier; }
    public String bankName() { return bankName; }
    public String bankAccountName() { return bankAccountName; }
    public String bankAccountNumber() { return bankAccountNumber; }

    public void setPoints(int points) { this.points = points; }
    public void setTier(String tier) { this.tier = tier; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
}
