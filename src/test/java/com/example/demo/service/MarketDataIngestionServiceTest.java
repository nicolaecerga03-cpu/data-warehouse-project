package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import com.example.demo.model.DataSource;
import com.example.demo.model.FinancialAsset;
import com.example.demo.model.TimeSeriesPoint;
import com.example.demo.repository.DataSourceRepository;
import com.example.demo.repository.FinancialAssetRepository;
import com.example.demo.repository.TimeSeriesPointRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class MarketDataIngestionServiceTest {

    private final FinancialAssetRepository financialAssetRepository = mock(FinancialAssetRepository.class);
    private final DataSourceRepository dataSourceRepository = mock(DataSourceRepository.class);
    private final TimeSeriesPointRepository timeSeriesPointRepository = mock(TimeSeriesPointRepository.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);

    @Test
    void ingestsBinanceUsdtKlinesAndPreservesWorkingInversionLogic() {
        MarketDataIngestionService service = newService();
        when(financialAssetRepository.findById("USDT")).thenReturn(Optional.empty());
        when(financialAssetRepository.save(any(FinancialAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dataSourceRepository.findById("BINANCE")).thenReturn(Optional.empty());
        when(dataSourceRepository.save(any(DataSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(restTemplate.getForObject(argThat(uri -> contains(uri, "symbol=USDCUSDT")
                && contains(uri, "interval=1d") && contains(uri, "limit=365")), eq(String.class)))
                .thenReturn(binanceResponse());
        when(timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                "USDT", "BINANCE", LocalDate.parse("2026-05-15"))).thenReturn(Optional.empty());

        MarketDataIngestionService.IngestionSummary summary = service.ingestAsset("usdt");

        assertEquals("USDT", summary.assetId());
        assertEquals("BINANCE", summary.dataSourceId());
        assertEquals("USDCUSDT", summary.sourceSymbol());
        assertEquals(1, summary.insertedRecords());

        TimeSeriesPoint point = savedPoint();
        assertEquals("USDT", point.getAssetId());
        assertEquals("BINANCE", point.getDataSourceId());
        assertEquals(LocalDate.parse("2026-05-15"), point.getBusinessDate());
        assertNotNull(point.getSystemDate());
        assertEquals(1.000010000100001, (Double) point.getValues().get("Open"), 0.0000000001);
        assertEquals(1.000060003600216, (Double) point.getValues().get("High"), 0.0000000001);
        assertEquals(0.999620144345149, (Double) point.getValues().get("Low"), 0.0000000001);
        assertEquals(0.999660115560710, (Double) point.getValues().get("Close"), 0.0000000001);
        assertEquals(true, point.getValues().get("PriceInverted"));
        assertFalse(point.getMetadata().containsKey("deleted"));
    }

    @Test
    void ingestsBinanceBtcKlinesWithoutInvertingPrice() {
        MarketDataIngestionService service = newService();
        when(financialAssetRepository.findById("BTC")).thenReturn(Optional.empty());
        when(financialAssetRepository.save(any(FinancialAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dataSourceRepository.findById("BINANCE")).thenReturn(Optional.of(new DataSource()));
        when(restTemplate.getForObject(argThat(uri -> contains(uri, "symbol=BTCUSDT")), eq(String.class)))
                .thenReturn(binanceResponse());
        when(timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                "BTC", "BINANCE", LocalDate.parse("2026-05-15"))).thenReturn(Optional.empty());

        MarketDataIngestionService.IngestionSummary summary = service.ingestAsset("BTC");

        assertEquals("BTC", summary.assetId());
        assertEquals("BTCUSDT", summary.sourceSymbol());
        TimeSeriesPoint point = savedPoint();
        assertEquals(0.99999, point.getValues().get("Open"));
        assertEquals(1.00034, point.getValues().get("Close"));
        assertEquals(false, point.getValues().get("PriceInverted"));
    }

    @Test
    void ingestsYahooAaplChartDataWithUserAgentHeaders() {
        MarketDataIngestionService service = newService();
        when(financialAssetRepository.findById("AAPL")).thenReturn(Optional.empty());
        when(financialAssetRepository.save(any(FinancialAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dataSourceRepository.findById("YAHOO")).thenReturn(Optional.empty());
        when(dataSourceRepository.save(any(DataSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(restTemplate.exchange(
                argThat(uri -> contains(uri, "finance/chart/AAPL") && contains(uri, "range=1y")),
                eq(HttpMethod.GET),
                argThat(entity -> hasUserAgent(entity)),
                eq(String.class))).thenReturn(ResponseEntity.ok(yahooResponse()));
        when(timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                "AAPL", "YAHOO", LocalDate.parse("2026-05-15"))).thenReturn(Optional.empty());

        MarketDataIngestionService.IngestionSummary summary = service.ingestAsset("AAPL");

        assertEquals("AAPL", summary.assetId());
        assertEquals("YAHOO", summary.dataSourceId());
        TimeSeriesPoint point = savedPoint();
        assertEquals(LocalDate.parse("2026-05-15"), point.getBusinessDate());
        assertEquals(297.9, point.getValues().get("Open"));
        assertEquals(303.2, point.getValues().get("High"));
        assertEquals(296.52, point.getValues().get("Low"));
        assertEquals(300.23, point.getValues().get("Close"));
        assertEquals(54721100L, point.getValues().get("Volume"));
        assertEquals(300.23, point.getValues().get("AdjustedClose"));
    }

    @Test
    void skipsSaveWhenLatestVersionHasSameValues() {
        MarketDataIngestionService service = newService();
        TimeSeriesPoint existingPoint = new TimeSeriesPoint();
        existingPoint.setAssetId("USDT");
        existingPoint.setDataSourceId("BINANCE");
        existingPoint.setBusinessDate(LocalDate.parse("2026-05-15"));
        existingPoint.setValues(expectedUsdtValues());

        when(financialAssetRepository.findById("USDT")).thenReturn(Optional.of(new FinancialAsset()));
        when(dataSourceRepository.findById("BINANCE")).thenReturn(Optional.of(new DataSource()));
        when(restTemplate.getForObject(any(URI.class), eq(String.class))).thenReturn(binanceResponse());
        when(timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                "USDT", "BINANCE", LocalDate.parse("2026-05-15"))).thenReturn(Optional.of(existingPoint));

        MarketDataIngestionService.IngestionSummary summary = service.ingestAsset("USDT");

        assertEquals(1, summary.fetchedRecords());
        assertEquals(0, summary.insertedRecords());
        assertEquals(1, summary.skippedRecords());
        verify(timeSeriesPointRepository, never()).saveAll(any());
    }

    @Test
    void skipsSaveWhenProviderValuesOnlyDifferByMongoNumericType() {
        MarketDataIngestionService service = newService();
        TimeSeriesPoint existingPoint = new TimeSeriesPoint();
        existingPoint.setAssetId("AAPL");
        existingPoint.setDataSourceId("YAHOO");
        existingPoint.setBusinessDate(LocalDate.parse("2026-05-15"));
        existingPoint.setValues(Map.of(
                "Open", 297.9000001,
                "High", 303.2,
                "Low", 296.52,
                "Close", 300.23,
                "Volume", 54721100.0,
                "AdjustedClose", 300.23005));

        when(financialAssetRepository.findById("AAPL")).thenReturn(Optional.of(new FinancialAsset()));
        when(dataSourceRepository.findById("YAHOO")).thenReturn(Optional.of(new DataSource()));
        when(restTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class))).thenReturn(ResponseEntity.ok(yahooResponse()));
        when(timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                "AAPL", "YAHOO", LocalDate.parse("2026-05-15"))).thenReturn(Optional.of(existingPoint));

        MarketDataIngestionService.IngestionSummary summary = service.ingestAsset("AAPL");

        assertEquals(1, summary.fetchedRecords());
        assertEquals(0, summary.insertedRecords());
        assertEquals(1, summary.skippedRecords());
        verify(timeSeriesPointRepository, never()).saveAll(any());
    }

    @Test
    void rejectsUnsupportedSymbols() {
        MarketDataIngestionService service = newService();

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> service.ingestAsset("MSFT"));
        assertEquals("Unsupported asset. Only USDT, BTC, and AAPL are allowed.", exception.getMessage());
    }

    private MarketDataIngestionService newService() {
        return new MarketDataIngestionService(
                financialAssetRepository,
                dataSourceRepository,
                timeSeriesPointRepository,
                restTemplate,
                new ObjectMapper(),
                "https://api.binance.com/api/v3/klines",
                "https://query1.finance.yahoo.com/v8/finance/chart/{assetSymbol}",
                365);
    }

    private TimeSeriesPoint savedPoint() {
        ArgumentCaptor<Iterable<TimeSeriesPoint>> pointsCaptor = pointsCaptor();
        verify(timeSeriesPointRepository).saveAll(pointsCaptor.capture());
        return toList(pointsCaptor.getValue()).get(0);
    }

    private boolean contains(URI uri, String expected) {
        return uri != null && uri.toString().contains(expected);
    }

    private boolean hasUserAgent(HttpEntity<?> entity) {
        return entity != null
                && entity.getHeaders().getFirst("User-Agent") != null
                && entity.getHeaders().getFirst("User-Agent").contains("Mozilla/5.0");
    }

    private String binanceResponse() {
        return """
                [[1778803200000,"0.99999000","1.00038000","0.99994000","1.00034000","2266870760.00000000",
                1778889599999,"2267078480.60004000",444144,"1102058843.00000000","1102158310.26686000","0"]]
                """;
    }

    private String yahooResponse() {
        return """
                {
                  "chart": {
                    "result": [{
                      "timestamp": [1778851800],
                      "indicators": {
                        "quote": [{
                          "open": [297.9],
                          "high": [303.2],
                          "low": [296.52],
                          "close": [300.23],
                          "volume": [54721100]
                        }],
                        "adjclose": [{
                          "adjclose": [300.23]
                        }]
                      }
                    }],
                    "error": null
                  }
                }
                """;
    }

    private Map<String, Object> expectedUsdtValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("Open", 1.000010000100001);
        values.put("High", 1.000060003600216);
        values.put("Low", 0.9996201443451489);
        values.put("Close", 0.9996601155607093);
        values.put("Volume", 2267078480.60004);
        values.put("QuoteVolume", 2267078480.60004);
        values.put("TradeCount", 444144L);
        values.put("SourceOpen", 0.99999);
        values.put("SourceHigh", 1.00038);
        values.put("SourceLow", 0.99994);
        values.put("SourceClose", 1.00034);
        values.put("SourceBaseVolume", 2266870760.0);
        values.put("SourceQuoteVolume", 2267078480.60004);
        values.put("PriceInverted", true);
        return values;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Iterable<TimeSeriesPoint>> pointsCaptor() {
        return ArgumentCaptor.forClass(Iterable.class);
    }

    private List<TimeSeriesPoint> toList(Iterable<TimeSeriesPoint> points) {
        return StreamSupport.stream(points.spliterator(), false).toList();
    }
}
