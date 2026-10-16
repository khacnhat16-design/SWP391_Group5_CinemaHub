package com.cinema.model.pricing;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Reusable, global snapshot of movie and branch ticket prices. */
public class PriceTemplate {
    private Long id;
    private String name;
    private String description;
    private String sourceType = "MANUAL";
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PriceTemplateRule> rules = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<PriceTemplateRule> getRules() { return rules; }
    public void setRules(List<PriceTemplateRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
    }
}

