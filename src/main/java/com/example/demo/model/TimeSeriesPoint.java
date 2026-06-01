package com.example.demo.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "time_series_points")
@CompoundIndexes({
        @CompoundIndex(name = "asset_source_business_system_idx",
                def = "{'asset_id': 1, 'data_source_id': 1, 'business_year': 1, "
                        + "'business_date': 1, 'system_date': -1}")
})
public class TimeSeriesPoint {

    @Id
    private String id;

    @Field("asset_id")
    private String assetId;

    @Field("data_source_id")
    private String dataSourceId;

    @Field("business_date")
    private LocalDate businessDate;

    @Field("business_year")
    private Integer businessYear;

    @Field("system_date")
    private Instant systemDate;

    private Map<String, Object> values = new HashMap<>();

    private Map<String, Object> metadata = new HashMap<>();

    public TimeSeriesPoint() {
    }

    public TimeSeriesPoint(String id, String assetId, String dataSourceId, LocalDate businessDate,
            Instant systemDate, Map<String, Object> values) {
        this(id, assetId, dataSourceId, businessDate, systemDate, values, null);
    }

    public TimeSeriesPoint(String id, String assetId, String dataSourceId, LocalDate businessDate,
            Instant systemDate, Map<String, Object> values, Map<String, Object> metadata) {
        this.id = id;
        this.assetId = assetId;
        this.dataSourceId = dataSourceId;
        this.businessDate = businessDate;
        this.systemDate = systemDate;
        setValues(values);
        setMetadata(metadata);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
        this.businessYear = businessDate == null ? null : businessDate.getYear();
    }

    public Integer getBusinessYear() {
        return businessYear;
    }

    public void setBusinessYear(Integer businessYear) {
        this.businessYear = businessYear;
    }

    public Instant getSystemDate() {
        return systemDate;
    }

    public void setSystemDate(Instant systemDate) {
        this.systemDate = systemDate;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public void setValues(Map<String, Object> values) {
        this.values = values == null ? new HashMap<>() : new HashMap<>(values);
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
    }
}
