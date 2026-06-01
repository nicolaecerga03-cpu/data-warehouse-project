package com.example.demo.model;

import java.util.HashMap;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "financial_assets")
public class FinancialAsset {

    @Id
    private String assetId;

    private String name;

    private String description;

    @Indexed
    private String assetClass;

    @Indexed
    private String region;

    private Map<String, String> attributes = new HashMap<>();

    public FinancialAsset() {
    }

    public FinancialAsset(String assetId, String name, String description, String region,
            Map<String, String> attributes) {
        this(assetId, name, description, null, region, attributes);
    }

    public FinancialAsset(String assetId, String name, String description, String assetClass, String region,
            Map<String, String> attributes) {
        this.assetId = assetId;
        this.name = name;
        this.description = description;
        this.assetClass = assetClass;
        this.region = region;
        setAttributes(attributes);
        if (this.assetClass == null || this.assetClass.isBlank()) {
            this.assetClass = inferAssetClass(this.attributes);
        }
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public void setAssetClass(String assetClass) {
        this.assetClass = assetClass;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        if (assetClass == null || assetClass.isBlank()) {
            assetClass = inferAssetClass(this.attributes);
        }
    }

    private String inferAssetClass(Map<String, String> attributes) {
        if (attributes == null) {
            return null;
        }
        String classValue = attributes.get("assetClass");
        if (classValue == null) {
            classValue = attributes.get("class");
        }
        if (classValue == null) {
            classValue = attributes.get("type");
        }
        return classValue;
    }
}
