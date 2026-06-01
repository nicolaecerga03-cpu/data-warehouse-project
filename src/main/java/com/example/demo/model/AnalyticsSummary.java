package com.example.demo.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "analytics_summaries")
@CompoundIndex(name = "analytics_asset_source_year_idx",
        def = "{'asset_id': 1, 'data_source_id': 1, 'business_year': 1, 'system_date': -1}")
public class AnalyticsSummary {

    @Id
    private String id;

    @Field("asset_id")
    private String assetId;

    @Field("data_source_id")
    private String dataSourceId;

    @Field("business_year")
    private int businessYear;

    @Field("system_date")
    private Instant systemDate;

    private long recordCount;

    private Double minimumClose;

    private Double maximumClose;

    private Double averageClose;

    private Double firstClose;

    private Double lastClose;

    private Double returnPercent;

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

    public int getBusinessYear() {
        return businessYear;
    }

    public void setBusinessYear(int businessYear) {
        this.businessYear = businessYear;
    }

    public Instant getSystemDate() {
        return systemDate;
    }

    public void setSystemDate(Instant systemDate) {
        this.systemDate = systemDate;
    }

    public long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(long recordCount) {
        this.recordCount = recordCount;
    }

    public Double getMinimumClose() {
        return minimumClose;
    }

    public void setMinimumClose(Double minimumClose) {
        this.minimumClose = minimumClose;
    }

    public Double getMaximumClose() {
        return maximumClose;
    }

    public void setMaximumClose(Double maximumClose) {
        this.maximumClose = maximumClose;
    }

    public Double getAverageClose() {
        return averageClose;
    }

    public void setAverageClose(Double averageClose) {
        this.averageClose = averageClose;
    }

    public Double getFirstClose() {
        return firstClose;
    }

    public void setFirstClose(Double firstClose) {
        this.firstClose = firstClose;
    }

    public Double getLastClose() {
        return lastClose;
    }

    public void setLastClose(Double lastClose) {
        this.lastClose = lastClose;
    }

    public Double getReturnPercent() {
        return returnPercent;
    }

    public void setReturnPercent(Double returnPercent) {
        this.returnPercent = returnPercent;
    }
}
