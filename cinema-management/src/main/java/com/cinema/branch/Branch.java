package com.cinema.branch;

import java.time.LocalDateTime;

/** Branch entity for cinema locations. */
public class Branch {
    private Long id;
    private String name;
    private String address;
    private String phone;
    private String status; // ACTIVE, INACTIVE
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Branch(String name, String address, String phone) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.status = "ACTIVE";
        this.createdAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Accessors — dùng để JSP/JS gọi qua record-style API.
    // -----------------------------------------------------------------------
    public Long id() { return id; }
    public String name() { return name; }
    public String address() { return address; }
    public String phone() { return phone; }
    public String status() { return status; }
    public LocalDateTime createdAt() { return createdAt; }
    public LocalDateTime updatedAt() { return updatedAt; }

    // -----------------------------------------------------------------------
    // Mutators — DAO dùng để bind dữ liệu từ ResultSet sau khi load.
    // -----------------------------------------------------------------------
    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setAddress(String address) { this.address = address; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setStatus(String status) { this.status = status; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
