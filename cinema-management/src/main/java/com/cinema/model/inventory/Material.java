package com.cinema.model.inventory;

/** Entity nguyên liệu thô (Kho nguyên liệu - Màn 2). */
public class Material {
    private Long id;
    private String code;
    private String name;
    private String category;
    private String unit;
    private double minStock;
    private long costPrice;
    private String supplier;
    private String status;

    public Material() {}

    public Material(Long id, String code, String name, String category, String unit,
                    double minStock, long costPrice, String supplier, String status) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.category = category;
        this.unit = unit;
        this.minStock = minStock;
        this.costPrice = costPrice;
        this.supplier = supplier;
        this.status = status;
    }

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }

    public String code() { return code; }
    public void setCode(String code) { this.code = code; }

    public String name() { return name; }
    public void setName(String name) { this.name = name; }

    public String category() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String unit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public double minStock() { return minStock; }
    public void setMinStock(double minStock) { this.minStock = minStock; }

    public long costPrice() { return costPrice; }
    public void setCostPrice(long costPrice) { this.costPrice = costPrice; }

    public String supplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }

    public String status() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
