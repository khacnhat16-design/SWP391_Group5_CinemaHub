package com.cinema.wallet;

/** Entity ví khách hàng (Req 18) — 1:1 với customer_profile, số dư không âm. */
public class Wallet {
    private long userId;
    private long balance;
    private int version;

    public long userId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public long balance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }
}
