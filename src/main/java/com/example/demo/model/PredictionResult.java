package com.example.demo.model;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "prediction_results")
@CompoundIndex(name = "prediction_asset_source_generated_idx",
        def = "{'asset_id': 1, 'data_source_id': 1, 'generated_at': -1}")
public class PredictionResult {

    @Id
    private String id;

    @Field("asset_id")
    private String assetId;

    @Field("data_source_id")
    private String dataSourceId;

    @Field("generated_at")
    private Instant generatedAt;

    @Field("target_business_date")
    private LocalDate targetBusinessDate;

    @Field("input_start_date")
    private LocalDate inputStartDate;

    @Field("input_end_date")
    private LocalDate inputEndDate;

    private Double predictedClose;

    private long trainingRecordCount;

    private String modelName;

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

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public LocalDate getTargetBusinessDate() {
        return targetBusinessDate;
    }

    public void setTargetBusinessDate(LocalDate targetBusinessDate) {
        this.targetBusinessDate = targetBusinessDate;
    }

    public LocalDate getInputStartDate() {
        return inputStartDate;
    }

    public void setInputStartDate(LocalDate inputStartDate) {
        this.inputStartDate = inputStartDate;
    }

    public LocalDate getInputEndDate() {
        return inputEndDate;
    }

    public void setInputEndDate(LocalDate inputEndDate) {
        this.inputEndDate = inputEndDate;
    }

    public Double getPredictedClose() {
        return predictedClose;
    }

    public void setPredictedClose(Double predictedClose) {
        this.predictedClose = predictedClose;
    }

    public long getTrainingRecordCount() {
        return trainingRecordCount;
    }

    public void setTrainingRecordCount(long trainingRecordCount) {
        this.trainingRecordCount = trainingRecordCount;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }
}
