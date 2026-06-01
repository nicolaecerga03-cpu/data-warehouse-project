package com.example.demo.controller;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.demo.model.AnalyticsSummary;
import com.example.demo.model.DataSource;
import com.example.demo.model.FinancialAsset;
import com.example.demo.model.PredictionResult;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.DataWarehouseQueryService;
import com.example.demo.service.MarketDataIngestionService;
import com.example.demo.service.SampleDataService;
import com.example.demo.spark.SparkAnalyticsJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DataWarehouseController.class)
class DataWarehouseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataWarehouseQueryService dataWarehouseQueryService;

    @MockitoBean
    private MarketDataIngestionService marketDataIngestionService;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private SampleDataService sampleDataService;

    @MockitoBean
    private SparkAnalyticsJob sparkAnalyticsJob;

    @Test
    void routesAssetListEndpoint() throws Exception {
        when(dataWarehouseQueryService.findAssetIds(0, 20)).thenReturn(List.of("AAPL", "BTC", "USDT"));

        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", contains("AAPL", "BTC", "USDT")));
    }

    @Test
    void routesAssetCatalogEndpoint() throws Exception {
        when(dataWarehouseQueryService.findAssetCatalog(0, 50, "Stock", "US", "assetId", "asc"))
                .thenReturn(List.of(new DataWarehouseQueryService.AssetSummary(
                        "AAPL", "Apple Inc.", "Apple common stock", "Stock", "US", Map.of("symbol", "AAPL"))));

        mockMvc.perform(get("/api/v1/assets/catalog")
                        .param("assetClass", "Stock")
                        .param("region", "US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetId").value("AAPL"))
                .andExpect(jsonPath("$[0].assetClass").value("Stock"));
    }

    @Test
    void routesAssetDetailEndpoint() throws Exception {
        FinancialAsset asset = new FinancialAsset("AAPL", "Apple Inc.", "Apple common stock", "US",
                Map.of("symbol", "AAPL"));
        when(dataWarehouseQueryService.findAsset("AAPL")).thenReturn(Optional.of(asset));

        mockMvc.perform(get("/api/v1/assets/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value("AAPL"))
                .andExpect(jsonPath("$.name").value("Apple Inc."));
    }

    @Test
    void routesDataSourceEndpoints() throws Exception {
        DataSource dataSource = new DataSource("YAHOO", "Yahoo Finance Chart API", "Yahoo Finance",
                java.util.Set.of("Open", "Close"));
        when(dataWarehouseQueryService.findDataSourceIds(0, 20)).thenReturn(List.of("BINANCE", "YAHOO"));
        when(dataWarehouseQueryService.findDataSource("YAHOO")).thenReturn(Optional.of(dataSource));

        mockMvc.perform(get("/api/v1/data-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", contains("BINANCE", "YAHOO")));

        mockMvc.perform(get("/api/v1/data-sources/YAHOO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("YAHOO"));
    }

    @Test
    void routesTimeSeriesDataEndpoint() throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("assetId", "AAPL");
        response.put("dataSourceId", "YAHOO");
        response.put("businessDate", LocalDate.parse("2024-01-02"));
        response.put("systemDate", Instant.parse("2024-01-03T10:00:00Z"));
        response.put("values", Map.of("Open", 100.5, "Close", 100.75));

        when(dataWarehouseQueryService.findLatestTimeSeriesData(
                eq("AAPL"),
                eq("YAHOO"),
                eq(LocalDate.parse("2024-01-01")),
                eq(LocalDate.parse("2024-02-01")),
                eq(true),
                eq(0),
                eq(1000),
                eq("desc"),
                eq(null))).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/data")
                        .param("assetId", "AAPL")
                        .param("dataSourceId", "YAHOO")
                        .param("startBusinessDate", "2024-01-01")
                        .param("endBusinessDate", "2024-02-01")
                        .param("includeAttributes", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetId").value("AAPL"))
                .andExpect(jsonPath("$[0].values.Open").value(100.5));
    }

    @Test
    void routesCurrentTimeSeriesDataEndpoint() throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("assetId", "USDT");
        response.put("dataSourceId", "BINANCE");
        response.put("businessDate", LocalDate.parse("2026-05-15"));
        response.put("systemDate", Instant.parse("2026-05-16T10:00:00Z"));
        response.put("values", Map.of("Close", 0.99966));

        when(dataWarehouseQueryService.findMostCurrentTimeSeriesData(
                eq("USDT"), eq("BINANCE"), eq(true), eq("Close"))).thenReturn(Optional.of(response));

        mockMvc.perform(get("/api/v1/data")
                        .param("assetId", "USDT")
                        .param("dataSourceId", "BINANCE")
                        .param("currentOnly", "true")
                        .param("includeAttributes", "true")
                        .param("indicator", "Close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assetId").value("USDT"))
                .andExpect(jsonPath("$[0].values.Close").value(0.99966));
    }

    @Test
    void returnsNotFoundForMissingAsset() throws Exception {
        when(dataWarehouseQueryService.findAsset(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/assets/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void routesMarketIngestionEndpoint() throws Exception {
        when(marketDataIngestionService.ingestAsset("USDT"))
                .thenReturn(new MarketDataIngestionService.IngestionSummary(
                        "USDT", "BINANCE", "USDCUSDT", 10, 3, 7, 1,
                        LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-10"), true));

        mockMvc.perform(post("/api/v1/ingest/USDT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ingestion completed successfully"))
                .andExpect(jsonPath("$.assetId").value("USDT"))
                .andExpect(jsonPath("$.dataSourceId").value("BINANCE"))
                .andExpect(jsonPath("$.sourceSymbol").value("USDCUSDT"))
                .andExpect(jsonPath("$.fetchedRecords").value(10))
                .andExpect(jsonPath("$.insertedRecords").value(3))
                .andExpect(jsonPath("$.skippedRecords").value(7))
                .andExpect(jsonPath("$.pagesFetched").value(1));
    }

    @Test
    void routesDefaultMarketIngestionEndpoint() throws Exception {
        when(marketDataIngestionService.ingestDefaultAssets())
                .thenReturn(List.of(
                        new MarketDataIngestionService.IngestionSummary(
                                "USDT", "BINANCE", "USDCUSDT", 10, 3, 7, 1,
                                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-10"), true),
                        new MarketDataIngestionService.IngestionSummary(
                                "BTC", "BINANCE", "BTCUSDT", 10, 2, 8, 1,
                                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-10"), true),
                        new MarketDataIngestionService.IngestionSummary(
                                "AAPL", "YAHOO", "AAPL", 10, 4, 6, 1,
                                LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-10"), true)));

        mockMvc.perform(post("/api/v1/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Default asset ingestion completed successfully"))
                .andExpect(jsonPath("$.assets[0].assetId").value("USDT"))
                .andExpect(jsonPath("$.assets[1].assetId").value("BTC"))
                .andExpect(jsonPath("$.assets[2].assetId").value("AAPL"))
                .andExpect(jsonPath("$.totalInsertedRecords").value(9));
    }

    @Test
    void returnsBadGatewayWhenMarketIngestionFails() throws Exception {
        when(marketDataIngestionService.ingestAsset("USDT"))
                .thenThrow(new MarketDataIngestionService.IngestionException(
                        "Binance rejected the public kline request with HTTP 429 Too Many Requests",
                        new RuntimeException()));

        mockMvc.perform(post("/api/v1/ingest/USDT"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error")
                        .value("Binance rejected the public kline request with HTTP 429 Too Many Requests"));
    }

    @Test
    void routesDemoDataEndpoint() throws Exception {
        when(sampleDataService.seedDemoData()).thenReturn(new SampleDataService.SampleDataSummary(3, 3, 18));

        mockMvc.perform(post("/api/v1/demo-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetsCreated").value(3))
                .andExpect(jsonPath("$.insertedPoints").value(18));
    }

    @Test
    void routesAnalyticsEndpoints() throws Exception {
        AnalyticsSummary summary = new AnalyticsSummary();
        summary.setAssetId("AAPL");
        summary.setDataSourceId("YAHOO");
        summary.setBusinessYear(2024);
        summary.setRecordCount(6);
        summary.setAverageClose(183.95);

        PredictionResult prediction = new PredictionResult();
        prediction.setAssetId("AAPL");
        prediction.setDataSourceId("YAHOO");
        prediction.setTargetBusinessDate(LocalDate.parse("2024-01-10"));
        prediction.setPredictedClose(186.25);

        when(analyticsService.computeYearlySummary(
                eq("AAPL"),
                eq("YAHOO"),
                eq(LocalDate.parse("2024-01-01")),
                eq(LocalDate.parse("2024-02-01")))).thenReturn(List.of(summary));
        when(analyticsService.predictNextClose(
                eq("AAPL"),
                eq("YAHOO"),
                eq(LocalDate.parse("2024-01-01")),
                eq(LocalDate.parse("2024-02-01")))).thenReturn(prediction);
        when(analyticsService.riskSignal(
                eq("AAPL"),
                eq("YAHOO"),
                eq(LocalDate.parse("2024-01-01")),
                eq(LocalDate.parse("2024-02-01")))).thenReturn(Map.of("riskLevel", "LOW"));

        mockMvc.perform(post("/api/v1/analytics/yearly-summary")
                        .param("assetId", "AAPL")
                        .param("dataSourceId", "YAHOO")
                        .param("startBusinessDate", "2024-01-01")
                        .param("endBusinessDate", "2024-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].businessYear").value(2024));

        mockMvc.perform(post("/api/v1/analytics/predict-next-close")
                        .param("assetId", "AAPL")
                        .param("dataSourceId", "YAHOO")
                        .param("startBusinessDate", "2024-01-01")
                        .param("endBusinessDate", "2024-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predictedClose").value(186.25));

        mockMvc.perform(get("/api/v1/analytics/risk-signal")
                        .param("assetId", "AAPL")
                        .param("dataSourceId", "YAHOO")
                        .param("startBusinessDate", "2024-01-01")
                        .param("endBusinessDate", "2024-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void routesSparkAnalyticsEndpoint() throws Exception {
        when(sparkAnalyticsJob.run(
                eq("AAPL"),
                eq("YAHOO"),
                eq(LocalDate.parse("2024-01-01")),
                eq(LocalDate.parse("2025-01-01"))))
                .thenReturn(new SparkAnalyticsJob.SparkAnalyticsResult(
                        "AAPL",
                        "YAHOO",
                        LocalDate.parse("2024-01-01"),
                        LocalDate.parse("2025-01-01"),
                        252,
                        1,
                        1,
                        "spark_analytics_summaries",
                        "spark_prediction_results"));

        mockMvc.perform(post("/api/v1/analytics/spark/run")
                        .param("assetId", "AAPL")
                        .param("dataSourceId", "YAHOO")
                        .param("startBusinessDate", "2024-01-01")
                        .param("endBusinessDate", "2025-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value("AAPL"))
                .andExpect(jsonPath("$.latestInputRecords").value(252))
                .andExpect(jsonPath("$.yearlySummariesWritten").value(1))
                .andExpect(jsonPath("$.predictionsWritten").value(1));
    }
}
