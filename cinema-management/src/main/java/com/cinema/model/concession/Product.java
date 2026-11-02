package com.cinema.model.concession;

/** Entity sản phẩm bắp nước (Req 19). COMBO có combo_item thành phần. */
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
    private Integer stock;

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }
    public String name() { return name; }
    public void setName(String name) { this.name = name; }
    public String description() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long price() { return price; }
    public void setPrice(long price) { this.price = price; }
    public String unit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String type() { return type; }
    public void setType(String type) { this.type = type; }
    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int version() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Integer stock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public boolean isCombo() { return TYPE_COMBO.equals(type); }
    public boolean isActive() { return STATUS_ACTIVE.equals(status); }

    /** Thành phần combo (Req 19.2): productId + qty. */
    public record ComboComponent(long productId, String productName, int qty) { }
}
