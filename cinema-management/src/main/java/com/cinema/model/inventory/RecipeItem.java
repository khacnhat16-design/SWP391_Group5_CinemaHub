package com.cinema.model.inventory;

/** Entity thành phần công thức chế biến (Màn 3). */
public class RecipeItem {
    private Long id;
    private Long productId;
    private Long materialId;
    private double dosage;
    private double lossRate;
    private String note;
    private double quantityRequired;
    private String unit;
    private boolean active;

    public RecipeItem() {}

    public RecipeItem(Long id, Long productId, Long materialId, double dosage, double lossRate,
                      String note, double quantityRequired, String unit, boolean active) {
        this.id = id;
        this.productId = productId;
        this.materialId = materialId;
        this.dosage = dosage;
        this.lossRate = lossRate;
        this.note = note;
        this.quantityRequired = quantityRequired;
        this.unit = unit;
        this.active = active;
    }

    public Long id() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long productId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Long materialId() { return materialId; }
    public void setMaterialId(Long materialId) { this.materialId = materialId; }

    public double dosage() { return dosage; }
    public void setDosage(double dosage) { this.dosage = dosage; }

    public double lossRate() { return lossRate; }
    public void setLossRate(double lossRate) { this.lossRate = lossRate; }

    public String note() { return note; }
    public void setNote(String note) { this.note = note; }

    public double quantityRequired() { return quantityRequired; }
    public void setQuantityRequired(double quantityRequired) { this.quantityRequired = quantityRequired; }

    public String unit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
