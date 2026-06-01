package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.example.demo.model.TimeSeriesPoint;
import com.example.demo.repository.DataSourceRepository;
import com.example.demo.repository.FinancialAssetRepository;
import com.example.demo.repository.TimeSeriesPointRepository;
import org.junit.jupiter.api.Test;

class DataWarehouseQueryServiceTest {

    private final FinancialAssetRepository financialAssetRepository = mock(FinancialAssetRepository.class);
    private final DataSourceRepository dataSourceRepository = mock(DataSourceRepository.class);
    private final TimeSeriesPointRepository timeSeriesPointRepository = mock(TimeSeriesPointRepository.class);
    private final DataWarehouseQueryService service = new DataWarehouseQueryService(
            financialAssetRepository, dataSourceRepository, timeSeriesPointRepository);

    @Test
    void keepsOnlyLatestVersionPerBusinessDateAndOmitsLogicalDeletes() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-01-04");
        TimeSeriesPoint deletedLatest = point("3-new", "2024-01-03", "2024-01-04T10:00:00Z",
                Map.of("Close", 300.0), Map.of("deleted", true));
        TimeSeriesPoint deletedOlderValue = point("3-old", "2024-01-03", "2024-01-03T10:00:00Z",
                Map.of("Close", 299.0), Map.of());
        TimeSeriesPoint latest = point("2-new", "2024-01-02", "2024-01-03T10:00:00Z",
                Map.of("Close", 101.0), Map.of());
        TimeSeriesPoint older = point("2-old", "2024-01-02", "2024-01-02T10:00:00Z",
                Map.of("Close", 100.0), Map.of());
        TimeSeriesPoint onlyVersion = point("1", "2024-01-01", "2024-01-02T10:00:00Z",
                Map.of("Close", 99.0), Map.of());

        when(timeSeriesPointRepository
                .findVersionsForBusinessDateWindow(
                        "AAPL", "YAHOO", start, end))
                .thenReturn(List.of(deletedLatest, deletedOlderValue, latest, older, onlyVersion));

        List<Map<String, Object>> records = service.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, true);

        assertEquals(2, records.size());
        assertEquals(LocalDate.parse("2024-01-02"), records.get(0).get("businessDate"));
        assertEquals(Map.of("Close", 101.0), records.get(0).get("values"));
        assertEquals(LocalDate.parse("2024-01-01"), records.get(1).get("businessDate"));
    }

    @Test
    void omitsValuesWhenAttributesAreNotRequested() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-01-02");

        when(timeSeriesPointRepository
                .findVersionsForBusinessDateWindow(
                        "AAPL", "YAHOO", start, end))
                .thenReturn(List.of(point("1", "2024-01-01", "2024-01-02T10:00:00Z",
                        Map.of("Close", 99.0), Map.of())));

        List<Map<String, Object>> records = service.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, false);

        assertEquals(1, records.size());
        assertFalse(records.get(0).containsKey("values"));
    }

    @Test
    void appliesOffsetAndLimitAfterLatestVersionFiltering() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-01-05");

        when(timeSeriesPointRepository
                .findVersionsForBusinessDateWindow(
                        "AAPL", "YAHOO", start, end))
                .thenReturn(List.of(
                        point("4", "2024-01-04", "2024-01-05T10:00:00Z", Map.of("Close", 104.0), Map.of()),
                        point("3", "2024-01-03", "2024-01-04T10:00:00Z", Map.of("Close", 103.0), Map.of()),
                        point("2", "2024-01-02", "2024-01-03T10:00:00Z", Map.of("Close", 102.0), Map.of()),
                        point("1", "2024-01-01", "2024-01-02T10:00:00Z", Map.of("Close", 101.0), Map.of())));

        List<Map<String, Object>> records =
                service.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, true, 1, 2);

        assertEquals(2, records.size());
        assertEquals(LocalDate.parse("2024-01-03"), records.get(0).get("businessDate"));
        assertEquals(LocalDate.parse("2024-01-02"), records.get(1).get("businessDate"));
    }

    @Test
    void returnsMostCurrentNonDeletedPoint() {
        when(timeSeriesPointRepository.findVersionsForAssetAndDataSource(eq("USDT"), eq("BINANCE"), any()))
                .thenReturn(List.of(
                        point("latest-deleted", "2026-05-16", "2026-05-16T10:00:00Z",
                                Map.of("Close", 1.0), Map.of("deleted", true)),
                        point("latest-old", "2026-05-16", "2026-05-16T09:00:00Z",
                                Map.of("Close", 0.99), Map.of()),
                        point("previous", "2026-05-15", "2026-05-15T10:00:00Z",
                                Map.of("Close", 0.999), Map.of())));

        var current = service.findMostCurrentTimeSeriesData("USDT", "BINANCE", true, "Close");

        assertTrue(current.isPresent());
        assertEquals(LocalDate.parse("2026-05-15"), current.get().get("businessDate"));
        assertEquals(Map.of("Close", 0.999), current.get().get("values"));
    }

    @Test
    void appliesIndicatorFilterAndAscendingSort() {
        LocalDate start = LocalDate.parse("2024-01-01");
        LocalDate end = LocalDate.parse("2024-01-04");

        when(timeSeriesPointRepository
                .findVersionsForBusinessDateWindow(
                        "AAPL", "YAHOO", start, end))
                .thenReturn(List.of(
                        point("3", "2024-01-03", "2024-01-04T10:00:00Z", Map.of("Close", 103.0), Map.of()),
                        point("2", "2024-01-02", "2024-01-03T10:00:00Z", Map.of("Volume", 200L), Map.of()),
                        point("1", "2024-01-01", "2024-01-02T10:00:00Z", Map.of("Close", 101.0), Map.of())));

        List<Map<String, Object>> records =
                service.findLatestTimeSeriesData("AAPL", "YAHOO", start, end, true, 0, 10, "asc", "Close");

        assertEquals(2, records.size());
        assertEquals(LocalDate.parse("2024-01-01"), records.get(0).get("businessDate"));
        assertEquals(LocalDate.parse("2024-01-03"), records.get(1).get("businessDate"));
    }

    private TimeSeriesPoint point(String id, String businessDate, String systemDate, Map<String, Object> values,
            Map<String, Object> metadata) {
        TimeSeriesPoint point = new TimeSeriesPoint();
        point.setId(id);
        point.setAssetId("AAPL");
        point.setDataSourceId("YAHOO");
        point.setBusinessDate(LocalDate.parse(businessDate));
        point.setSystemDate(Instant.parse(systemDate));
        point.setValues(values);
        point.setMetadata(metadata);
        return point;
    }
}
