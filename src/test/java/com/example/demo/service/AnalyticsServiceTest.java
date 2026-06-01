package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.example.demo.model.AnalyticsSummary;
import com.example.demo.model.PredictionResult;
import com.example.demo.repository.AnalyticsSummaryRepository;
import com.example.demo.repository.PredictionResultRepository;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTest {

    private final DataWarehouseQueryService dataWarehouseQueryService = mock(DataWarehouseQueryService.class);
    private final AnalyticsSummaryRepository analyticsSummaryRepository = mock(AnalyticsSummaryRepository.class);
    private final PredictionResultRepository predictionResultRepository = mock(PredictionResultRepository.class);
    private final AnalyticsService service = new AnalyticsService(
            dataWarehouseQueryService, analyticsSummaryRepository, predictionResultRepository);

    @Test
    void computesAndPersistsYearlySummary() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2025-01-01");
        when(dataWarehouseQueryService.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, true))
                .thenReturn(records());
        when(analyticsSummaryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<AnalyticsSummary> summaries = service.computeYearlySummary("AAPL", "YAHOO", start, end);

        assertEquals(1, summaries.size());
        assertEquals(2024, summaries.get(0).getBusinessYear());
        assertEquals(3, summaries.get(0).getRecordCount());
        assertEquals(100.0, summaries.get(0).getMinimumClose());
        assertEquals(110.0, summaries.get(0).getMaximumClose());
        assertEquals(10.0, summaries.get(0).getReturnPercent());
        verify(analyticsSummaryRepository).saveAll(any());
    }

    @Test
    void predictsNextCloseAndPersistsResult() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-01-05");
        when(dataWarehouseQueryService.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, true))
                .thenReturn(records());
        when(predictionResultRepository.save(any(PredictionResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PredictionResult result = service.predictNextClose("AAPL", "YAHOO", start, end);

        assertEquals("AAPL", result.getAssetId());
        assertEquals(3, result.getTrainingRecordCount());
        assertEquals(LocalDate.parse("2024-01-05"), result.getTargetBusinessDate());
        assertNotNull(result.getPredictedClose());
        verify(predictionResultRepository).save(any(PredictionResult.class));
    }

    @Test
    void calculatesRiskSignal() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-01-05");
        when(dataWarehouseQueryService.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, true))
                .thenReturn(records());

        Map<String, Object> risk = service.riskSignal("AAPL", "YAHOO", start, end);

        assertEquals("AAPL", risk.get("assetId"));
        assertEquals(3, risk.get("recordCount"));
        assertNotNull(risk.get("riskLevel"));
    }

    private List<Map<String, Object>> records() {
        return List.of(
                record("2024-01-04", 110.0),
                record("2024-01-03", 105.0),
                record("2024-01-02", 100.0));
    }

    private Map<String, Object> record(String businessDate, double close) {
        return Map.of("businessDate", businessDate, "values", Map.of("Close", close));
    }
}
