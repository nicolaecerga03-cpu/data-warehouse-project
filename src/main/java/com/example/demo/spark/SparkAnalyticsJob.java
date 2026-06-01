package com.example.demo.spark;

import static org.apache.spark.sql.functions.avg;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.current_timestamp;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.min;
import static org.apache.spark.sql.functions.row_number;
import static org.apache.spark.sql.functions.to_date;
import static org.apache.spark.sql.functions.year;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.ml.linalg.Vectors;
import org.apache.spark.ml.regression.LinearRegression;
import org.apache.spark.ml.regression.LinearRegressionModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SparkAnalyticsJob {

    public static final String SUMMARY_COLLECTION = "spark_analytics_summaries";
    public static final String PREDICTION_COLLECTION = "spark_prediction_results";

    private final String connectionString;
    private final String database;
    private final String master;

    public SparkAnalyticsJob(
            @Value("${spark.mongodb.connection-string:mongodb://localhost:27017}") String connectionString,
            @Value("${spark.mongodb.database:test}") String database,
            @Value("${spark.master:local[*]}") String master) {
        this.connectionString = connectionString;
        this.database = database;
        this.master = master;
    }

    public SparkAnalyticsResult run(String assetId, String dataSourceId, LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        SparkSession spark = createSparkSession();
        try {
            Dataset<Row> latestPoints = loadLatestClosePoints(
                    spark, assetId, dataSourceId, startBusinessDate, endBusinessDate).cache();
            long inputRecords = latestPoints.count();

            long yearlySummariesWritten = writeYearlySummaries(latestPoints);
            long predictionsWritten = writeLinearRegressionPrediction(
                    spark, latestPoints, assetId, dataSourceId, startBusinessDate, endBusinessDate, inputRecords);

            return new SparkAnalyticsResult(
                    assetId,
                    dataSourceId,
                    startBusinessDate,
                    endBusinessDate,
                    inputRecords,
                    yearlySummariesWritten,
                    predictionsWritten,
                    SUMMARY_COLLECTION,
                    PREDICTION_COLLECTION);
        } finally {
            spark.stop();
        }
    }

    private SparkSession createSparkSession() {
        return SparkSession.builder()
                .appName("AcmeFinancialWarehouseSparkAnalytics")
                .master(master)
                .config("spark.ui.enabled", "false")
                .config("spark.sql.shuffle.partitions", "4")
                .getOrCreate();
    }

    private Dataset<Row> loadLatestClosePoints(
            SparkSession spark,
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        Dataset<Row> raw = spark.read()
                .format("mongodb")
                .option("connection.uri", collectionUri("time_series_points"))
                .load();

        Dataset<Row> filtered = raw
                .withColumn("businessDate", to_date(col("business_date")))
                .withColumn("close", col("values.Close").cast("double"))
                .filter(col("asset_id").equalTo(assetId))
                .filter(col("data_source_id").equalTo(dataSourceId));

        if (startBusinessDate != null) {
            filtered = filtered.filter(col("businessDate").geq(to_date(lit(startBusinessDate.toString()))));
        }
        if (endBusinessDate != null) {
            filtered = filtered.filter(col("businessDate").lt(to_date(lit(endBusinessDate.toString()))));
        }

        WindowSpec latestVersion = Window
                .partitionBy(col("asset_id"), col("data_source_id"), col("businessDate"))
                .orderBy(col("system_date").desc());

        return filtered
                .withColumn("versionRank", row_number().over(latestVersion))
                .filter(col("versionRank").equalTo(1))
                .filter(coalesce(col("metadata.deleted").cast("boolean"), lit(false)).equalTo(false))
                .filter(col("close").isNotNull())
                .select(
                        col("asset_id").alias("assetId"),
                        col("data_source_id").alias("dataSourceId"),
                        col("businessDate"),
                        col("system_date").alias("systemDate"),
                        col("close"));
    }

    private long writeYearlySummaries(Dataset<Row> latestPoints) {
        Dataset<Row> yearlySummaries = latestPoints
                .withColumn("businessYear", year(col("businessDate")))
                .groupBy(col("assetId"), col("dataSourceId"), col("businessYear"))
                .agg(
                        count(lit(1)).alias("recordCount"),
                        min(col("close")).alias("minimumClose"),
                        max(col("close")).alias("maximumClose"),
                        avg(col("close")).alias("averageClose"))
                .withColumn("generatedAt", current_timestamp())
                .withColumn("jobType", lit("spark-yearly-aggregation"));

        long summaryCount = yearlySummaries.count();
        if (summaryCount > 0) {
            writeMongo(yearlySummaries, SUMMARY_COLLECTION);
        }
        return summaryCount;
    }

    private long writeLinearRegressionPrediction(
            SparkSession spark,
            Dataset<Row> latestPoints,
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate,
            long inputRecords) {
        if (inputRecords < 2) {
            return 0;
        }

        WindowSpec orderByBusinessDate = Window.orderBy(col("businessDate").asc());
        Dataset<Row> training = latestPoints
                .orderBy(col("businessDate").asc())
                .withColumn("dayIndex", row_number().over(orderByBusinessDate).minus(1).cast("double"))
                .withColumnRenamed("close", "label");

        Dataset<Row> featureRows = new VectorAssembler()
                .setInputCols(new String[] {"dayIndex"})
                .setOutputCol("features")
                .transform(training)
                .select(col("features"), col("label"));

        LinearRegressionModel model = new LinearRegression()
                .setFeaturesCol("features")
                .setLabelCol("label")
                .fit(featureRows);

        double predictedClose = model.predict(Vectors.dense((double) inputRecords));
        LocalDate latestBusinessDate = toLocalDate(latestPoints.agg(max(col("businessDate"))).first().get(0));

        SparkPredictionDocument prediction = new SparkPredictionDocument();
        prediction.setAssetId(assetId);
        prediction.setDataSourceId(dataSourceId);
        prediction.setGeneratedAt(Timestamp.from(Instant.now()));
        prediction.setInputStartDate(startBusinessDate == null ? null : startBusinessDate.toString());
        prediction.setInputEndDate(endBusinessDate == null ? null : endBusinessDate.toString());
        prediction.setTargetBusinessDate(nextBusinessDate(latestBusinessDate).toString());
        prediction.setPredictedClose(predictedClose);
        prediction.setTrainingRecordCount(inputRecords);
        prediction.setModelName("Apache Spark MLlib LinearRegression over latest Close values");

        Dataset<Row> predictionFrame = spark.createDataFrame(List.of(prediction), SparkPredictionDocument.class);
        writeMongo(predictionFrame, PREDICTION_COLLECTION);
        return 1;
    }

    private void writeMongo(Dataset<Row> frame, String collection) {
        frame.write()
                .format("mongodb")
                .mode(SaveMode.Append)
                .option("connection.uri", collectionUri(collection))
                .save();
    }

    private String collectionUri(String collection) {
        String normalized = connectionString.endsWith("/") ? connectionString.substring(0, connectionString.length() - 1)
                : connectionString;
        return normalized + "/" + database + "." + collection;
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private LocalDate nextBusinessDate(LocalDate date) {
        LocalDate next = date.plusDays(1);
        return switch (next.getDayOfWeek()) {
            case SATURDAY -> next.plusDays(2);
            case SUNDAY -> next.plusDays(1);
            default -> next;
        };
    }

    public record SparkAnalyticsResult(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate,
            long latestInputRecords,
            long yearlySummariesWritten,
            long predictionsWritten,
            String summaryCollection,
            String predictionCollection) {
    }

    public static class SparkPredictionDocument {

        private String assetId;
        private String dataSourceId;
        private Timestamp generatedAt;
        private String inputStartDate;
        private String inputEndDate;
        private String targetBusinessDate;
        private Double predictedClose;
        private long trainingRecordCount;
        private String modelName;

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

        public Timestamp getGeneratedAt() {
            return generatedAt;
        }

        public void setGeneratedAt(Timestamp generatedAt) {
            this.generatedAt = generatedAt;
        }

        public String getInputStartDate() {
            return inputStartDate;
        }

        public void setInputStartDate(String inputStartDate) {
            this.inputStartDate = inputStartDate;
        }

        public String getInputEndDate() {
            return inputEndDate;
        }

        public void setInputEndDate(String inputEndDate) {
            this.inputEndDate = inputEndDate;
        }

        public String getTargetBusinessDate() {
            return targetBusinessDate;
        }

        public void setTargetBusinessDate(String targetBusinessDate) {
            this.targetBusinessDate = targetBusinessDate;
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
}
