package com.example.demo.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.demo.model.AnalyticsSummary;
import com.example.demo.model.DataSource;
import com.example.demo.model.FinancialAsset;
import com.example.demo.model.PredictionResult;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.DataWarehouseQueryService;
import com.example.demo.service.MarketDataIngestionService;
import com.example.demo.service.SampleDataService;
import com.example.demo.spark.SparkAnalyticsJob;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class DataWarehouseController {

    private final DataWarehouseQueryService dataWarehouseQueryService;
    private final MarketDataIngestionService marketDataIngestionService;
    private final AnalyticsService analyticsService;
    private final SampleDataService sampleDataService;
    private final SparkAnalyticsJob sparkAnalyticsJob;

    public DataWarehouseController(
            DataWarehouseQueryService dataWarehouseQueryService,
            MarketDataIngestionService marketDataIngestionService,
            AnalyticsService analyticsService,
            SampleDataService sampleDataService,
            SparkAnalyticsJob sparkAnalyticsJob) {
        this.dataWarehouseQueryService = dataWarehouseQueryService;
        this.marketDataIngestionService = marketDataIngestionService;
        this.analyticsService = analyticsService;
        this.sampleDataService = sampleDataService;
        this.sparkAnalyticsJob = sparkAnalyticsJob;
    }

    @GetMapping("/assets")
    public List<String> findAssets(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return dataWarehouseQueryService.findAssetIds(offset, limit);
    }

    @GetMapping("/assets/catalog")
    public List<DataWarehouseQueryService.AssetSummary> findAssetCatalog(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "assetId") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        return dataWarehouseQueryService.findAssetCatalog(offset, limit, assetClass, region, sortBy, direction);
    }

    @GetMapping("/assets/{assetId}")
    public FinancialAsset findAsset(@PathVariable String assetId) {
        return dataWarehouseQueryService.findAsset(assetId)
                .orElseThrow(() -> notFound("Asset not found: " + assetId));
    }

    @GetMapping("/data-sources")
    public List<String> findDataSources(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return dataWarehouseQueryService.findDataSourceIds(offset, limit);
    }

    @GetMapping("/data-sources/{dataSourceId}")
    public DataSource findDataSource(@PathVariable String dataSourceId) {
        return dataWarehouseQueryService.findDataSource(dataSourceId)
                .orElseThrow(() -> notFound("Data source not found: " + dataSourceId));
    }

    @GetMapping("/data")
    public List<Map<String, Object>> findData(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate,
            @RequestParam(defaultValue = "false") boolean includeAttributes,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(required = false) String indicator,
            @RequestParam(defaultValue = "false") boolean currentOnly) {
        if (currentOnly || (startBusinessDate == null && endBusinessDate == null)) {
            return dataWarehouseQueryService.findMostCurrentTimeSeriesData(
                            assetId, dataSourceId, includeAttributes, indicator)
                    .map(List::of)
                    .orElseGet(List::of);
        }
        if (startBusinessDate == null || endBusinessDate == null) {
            throw new IllegalArgumentException(
                    "Provide both startBusinessDate and endBusinessDate, or use currentOnly=true.");
        }
        return dataWarehouseQueryService.findLatestTimeSeriesData(
                assetId, dataSourceId, startBusinessDate, endBusinessDate, includeAttributes, offset, limit, sort,
                indicator);
    }

    @GetMapping("/data/range")
    public DataWarehouseQueryService.TimeSeriesDateRange findDataRange(
            @RequestParam String assetId,
            @RequestParam String dataSourceId) {
        return dataWarehouseQueryService.findTimeSeriesDateRange(assetId, dataSourceId);
    }

    @PostMapping("/ingest/{assetSymbol}")
    public Map<String, Object> ingestMarketData(@PathVariable String assetSymbol) {
        MarketDataIngestionService.IngestionSummary summary =
                marketDataIngestionService.ingestAsset(assetSymbol);

        return ingestionResponse(summary);
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingestDefaultMarketData() {
        List<MarketDataIngestionService.IngestionSummary> summaries =
                marketDataIngestionService.ingestDefaultAssets();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Default asset ingestion completed successfully");
        response.put("assets", summaries.stream().map(this::ingestionResponse).toList());
        response.put("totalFetchedRecords", summaries.stream().mapToInt(MarketDataIngestionService.IngestionSummary::fetchedRecords).sum());
        response.put("totalInsertedRecords", summaries.stream().mapToInt(MarketDataIngestionService.IngestionSummary::insertedRecords).sum());
        response.put("totalSkippedRecords", summaries.stream().mapToInt(MarketDataIngestionService.IngestionSummary::skippedRecords).sum());
        return response;
    }

    private Map<String, Object> ingestionResponse(MarketDataIngestionService.IngestionSummary summary) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Ingestion completed successfully");
        response.put("assetId", summary.assetId());
        response.put("dataSourceId", summary.dataSourceId());
        response.put("sourceSymbol", summary.sourceSymbol());
        response.put("fetchedRecords", summary.fetchedRecords());
        response.put("insertedRecords", summary.insertedRecords());
        response.put("skippedRecords", summary.skippedRecords());
        response.put("pagesFetched", summary.pagesFetched());
        response.put("firstBusinessDate", summary.firstBusinessDate());
        response.put("lastBusinessDate", summary.lastBusinessDate());
        response.put("providerExhausted", summary.providerExhausted());
        return response;
    }

    @PostMapping("/demo-data")
    public Map<String, Object> seedDemoData() {
        SampleDataService.SampleDataSummary summary = sampleDataService.seedDemoData();
        return Map.of(
                "message", "Demo data is ready",
                "assetsCreated", summary.assetsCreated(),
                "dataSourcesCreated", summary.dataSourcesCreated(),
                "insertedPoints", summary.insertedPoints());
    }

    @PostMapping("/analytics/yearly-summary")
    public List<AnalyticsSummary> computeYearlySummary(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate) {
        return analyticsService.computeYearlySummary(assetId, dataSourceId, startBusinessDate, endBusinessDate);
    }

    @PostMapping("/analytics/predict-next-close")
    public PredictionResult predictNextClose(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate) {
        return analyticsService.predictNextClose(assetId, dataSourceId, startBusinessDate, endBusinessDate);
    }

    @GetMapping("/analytics/risk-signal")
    public Map<String, Object> riskSignal(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate) {
        return analyticsService.riskSignal(assetId, dataSourceId, startBusinessDate, endBusinessDate);
    }

    @GetMapping("/analytics/compare")
    public Map<String, Object> compareAssets(
            @RequestParam String firstAssetId,
            @RequestParam String secondAssetId,
            @RequestParam(required = false) String dataSourceId,
            @RequestParam(required = false) String firstDataSourceId,
            @RequestParam(required = false) String secondDataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate) {
        String firstSource = firstDataSourceId == null || firstDataSourceId.isBlank() ? dataSourceId : firstDataSourceId;
        String secondSource = secondDataSourceId == null || secondDataSourceId.isBlank() ? dataSourceId : secondDataSourceId;
        if (firstSource == null || firstSource.isBlank() || secondSource == null || secondSource.isBlank()) {
            throw new IllegalArgumentException(
                    "Provide dataSourceId or both firstDataSourceId and secondDataSourceId for comparison");
        }
        return analyticsService.compareAssets(
                firstAssetId, firstSource, secondAssetId, secondSource, startBusinessDate, endBusinessDate);
    }

    @PostMapping("/analytics/spark/run")
    public SparkAnalyticsJob.SparkAnalyticsResult runSparkAnalytics(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate) {
        return sparkAnalyticsJob.run(assetId, dataSourceId, startBusinessDate, endBusinessDate);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    @org.springframework.web.bind.annotation.ExceptionHandler(MarketDataIngestionService.IngestionException.class)
    public Map<String, String> handleMarketDataIngestionError(MarketDataIngestionService.IngestionException exception) {
        return Map.of("error", exception.getMessage());
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
