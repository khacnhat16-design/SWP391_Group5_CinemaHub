package com.cinema.model.inventory;

/** Entity sản phẩm bắp nước (Giai đoạn 1). */
public class Product {
    public static final String TYPE_POPCORN = "POPCORN";
    public static final String TYPE_DRINK = "DRINK";
    public static final String TYPE_COMBO = "COMBO";
    public static final String TYPE_OTHER = "OTHER";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    private Long id;
    private String name;
    private String description;
    private long price;
    private String unit;
    private String type;
    private String status;
    private int version;

    public Product() {}

    public Product(Long id, String name, String description, long price, String unit, String type, String status) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.unit = unit;
        this.type = type;
        this.status = status;
    }

    public Long id() { return id; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String name() { return name; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String description() { return description; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long price() { return price; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
    public String unit() { return unit; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String type() { return type; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String status() { return status; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int version() { return version; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public boolean isCombo() { return TYPE_COMBO.equals(type); }
    public boolean isActive() { return STATUS_ACTIVE.equals(status); }
}
