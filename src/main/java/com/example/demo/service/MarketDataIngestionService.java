package com.example.demo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.demo.WarehouseCatalog;
import com.example.demo.model.DataSource;
import com.example.demo.model.FinancialAsset;
import com.example.demo.model.TimeSeriesPoint;
import com.example.demo.repository.DataSourceRepository;
import com.example.demo.repository.FinancialAssetRepository;
import com.example.demo.repository.TimeSeriesPointRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MarketDataIngestionService {

    public static final List<String> DEFAULT_ASSETS = WarehouseCatalog.ALLOWED_ASSET_IDS;
    public static final String BINANCE_DATA_SOURCE_ID = "BINANCE";
    public static final String YAHOO_DATA_SOURCE_ID = "YAHOO";

    private static final String INTERVAL = "1d";
    private static final int MAX_LIMIT = 1000;
    private static final String YAHOO_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/125.0 Safari/537.36";
    private static final BigDecimal NUMERIC_COMPARISON_TOLERANCE = new BigDecimal("0.000001");
    private static final BigDecimal ADJUSTED_CLOSE_COMPARISON_TOLERANCE = new BigDecimal("0.0001");
    private static final Set<String> SUPPORTED_ATTRIBUTES = Set.of(
            "Open",
            "High",
            "Low",
            "Close",
            "Volume",
            "QuoteVolume",
            "TradeCount",
            "AdjustedClose",
            "SourceOpen",
            "SourceHigh",
            "SourceLow",
            "SourceClose");

    private final FinancialAssetRepository financialAssetRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TimeSeriesPointRepository timeSeriesPointRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String binanceKlinesUrl;
    private final String yahooChartUrlTemplate;
    private final int limit;

    @Autowired
    public MarketDataIngestionService(
            FinancialAssetRepository financialAssetRepository,
            DataSourceRepository dataSourceRepository,
            TimeSeriesPointRepository timeSeriesPointRepository,
            @Value("${crypto.binance.klines-url:https://api.binance.com/api/v3/klines}") String binanceKlinesUrl,
            @Value("${market.yahoo.chart-url-template:https://query1.finance.yahoo.com/v8/finance/chart/{assetSymbol}}")
            String yahooChartUrlTemplate,
            @Value("${crypto.binance.limit:365}") int limit) {
        this(financialAssetRepository, dataSourceRepository, timeSeriesPointRepository, new RestTemplate(),
                new ObjectMapper(), binanceKlinesUrl, yahooChartUrlTemplate, limit);
    }

    MarketDataIngestionService(
            FinancialAssetRepository financialAssetRepository,
            DataSourceRepository dataSourceRepository,
            TimeSeriesPointRepository timeSeriesPointRepository,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            String binanceKlinesUrl,
            String yahooChartUrlTemplate,
            int limit) {
        this.financialAssetRepository = financialAssetRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.timeSeriesPointRepository = timeSeriesPointRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.binanceKlinesUrl = binanceKlinesUrl;
        this.yahooChartUrlTemplate = yahooChartUrlTemplate;
        this.limit = Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    public List<IngestionSummary> ingestDefaultAssets() {
        List<IngestionSummary> summaries = new ArrayList<>();
        for (String asset : DEFAULT_ASSETS) {
            summaries.add(ingestAsset(asset));
        }
        return summaries;
    }

    public IngestionSummary ingestAsset(String assetSymbol) {
        return ingestAsset(assetSymbol, null, null);
    }

    public IngestionSummary ingestAsset(String assetSymbol, LocalDate startDate, LocalDate endDate) {
        AssetRoute route = routeFor(assetSymbol);
        ensureFinancialAssetExists(route);
        ensureDataSourceExists(route.dataSourceId());

        Instant ingestionTime = Instant.now();
        List<TimeSeriesPoint> transformedPoints = switch (route.provider()) {
            case BINANCE -> fetchBinance(route, startDate, endDate, ingestionTime);
            case YAHOO -> fetchYahoo(route, startDate, endDate, ingestionTime);
        };

        List<TimeSeriesPoint> newVersions = new ArrayList<>();
        for (TimeSeriesPoint candidate : transformedPoints) {
            Optional<TimeSeriesPoint> latestVersion =
                    timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                            candidate.getAssetId(), candidate.getDataSourceId(), candidate.getBusinessDate());

            if (latestVersion.isEmpty() || isDeleted(latestVersion.get())
                    || !sameBusinessValues(latestVersion.get().getValues(), candidate.getValues())) {
                newVersions.add(candidate);
            }
        }

        if (!newVersions.isEmpty()) {
            timeSeriesPointRepository.saveAll(newVersions);
        }

        LocalDate firstBusinessDate = transformedPoints.stream()
                .map(TimeSeriesPoint::getBusinessDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDate lastBusinessDate = transformedPoints.stream()
                .map(TimeSeriesPoint::getBusinessDate)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return new IngestionSummary(
                route.assetId(),
                route.dataSourceId(),
                route.sourceSymbol(),
                transformedPoints.size(),
                newVersions.size(),
                transformedPoints.size() - newVersions.size(),
                1,
                firstBusinessDate,
                lastBusinessDate,
                true);
    }

    private List<TimeSeriesPoint> fetchBinance(
            AssetRoute route,
            LocalDate startDate,
            LocalDate endDate,
            Instant ingestionTime) {
        URI uri = buildBinanceUri(route.sourceSymbol(), startDate, endDate);
        String response = getString(uri, null, "Binance");

        try {
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                throw new IllegalStateException("Binance kline response must be an array");
            }
            List<TimeSeriesPoint> points = new ArrayList<>();
            root.forEach(row -> points.add(toBinancePoint(route, row, ingestionTime)));
            return points;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Binance kline response", exception);
        }
    }

    private List<TimeSeriesPoint> fetchYahoo(
            AssetRoute route,
            LocalDate startDate,
            LocalDate endDate,
            Instant ingestionTime) {
        URI uri = buildYahooUri(route.assetId(), startDate, endDate);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, YAHOO_USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, "application/json");
        String response = getString(uri, new HttpEntity<>(headers), "Yahoo Finance");

        try {
            JsonNode result = objectMapper.readTree(response)
                    .path("chart")
                    .path("result")
                    .path(0);
            if (result.isMissingNode() || result.isNull()) {
                JsonNode error = objectMapper.readTree(response).path("chart").path("error");
                throw new IllegalStateException("Yahoo chart response did not contain result data: " + error);
            }

            JsonNode timestamps = result.path("timestamp");
            JsonNode quote = result.path("indicators").path("quote").path(0);
            JsonNode adjustedClose = result.path("indicators").path("adjclose").path(0).path("adjclose");

            List<TimeSeriesPoint> points = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                if (isNullAt(quote.path("open"), i) || isNullAt(quote.path("high"), i)
                        || isNullAt(quote.path("low"), i) || isNullAt(quote.path("close"), i)) {
                    continue;
                }
                points.add(toYahooPoint(route, timestamps, quote, adjustedClose, i, ingestionTime));
            }
            return points;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Yahoo Finance chart response", exception);
        }
    }

    private URI buildBinanceUri(String sourceSymbol, LocalDate startDate, LocalDate endDate) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(binanceKlinesUrl)
                .queryParam("symbol", sourceSymbol)
                .queryParam("interval", INTERVAL)
                .queryParam("limit", limit);

        if (startDate != null) {
            builder.queryParam("startTime", startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        if (endDate != null) {
            builder.queryParam("endTime", endDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1);
        }

        return builder.build().toUri();
    }

    private URI buildYahooUri(String assetSymbol, LocalDate startDate, LocalDate endDate) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(yahooChartUrlTemplate)
                .queryParam("interval", INTERVAL)
                .queryParam("includePrePost", "false")
                .queryParam("events", "history");

        if (startDate == null && endDate == null) {
            builder.queryParam("range", "1y");
        } else {
            LocalDate start = startDate == null ? LocalDate.now(ZoneOffset.UTC).minusYears(1) : startDate;
            LocalDate end = endDate == null ? LocalDate.now(ZoneOffset.UTC).plusDays(1) : endDate;
            builder.queryParam("period1", start.atStartOfDay().toEpochSecond(ZoneOffset.UTC));
            builder.queryParam("period2", end.atStartOfDay().toEpochSecond(ZoneOffset.UTC));
        }

        return builder.buildAndExpand(Map.of("assetSymbol", assetSymbol)).toUri();
    }

    private String getString(URI uri, HttpEntity<?> entity, String providerName) {
        try {
            if (entity == null) {
                return restTemplate.getForObject(uri, String.class);
            }
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (RestClientResponseException exception) {
            throw new IngestionException(providerName + " rejected the public data request with HTTP "
                    + exception.getStatusCode().value() + " " + exception.getStatusText()
                    + ". " + httpErrorDetail(exception), exception);
        } catch (RestClientException exception) {
            throw new IngestionException("Failed to fetch public " + providerName + " market data", exception);
        }
    }

    private TimeSeriesPoint toBinancePoint(AssetRoute route, JsonNode row, Instant ingestionTime) {
        if (!row.isArray() || row.size() < 11) {
            throw new IllegalStateException("Binance kline row has an unexpected shape");
        }

        LocalDate businessDate = Instant.ofEpochMilli(row.get(0).longValue()).atZone(ZoneOffset.UTC).toLocalDate();
        BigDecimal sourceOpen = decimal(row.get(1));
        BigDecimal sourceHigh = decimal(row.get(2));
        BigDecimal sourceLow = decimal(row.get(3));
        BigDecimal sourceClose = decimal(row.get(4));
        BigDecimal baseVolume = decimal(row.get(5));
        BigDecimal quoteVolume = decimal(row.get(7));

        Map<String, Object> values = new HashMap<>();
        if (route.invertPrice()) {
            values.put("Open", invert(sourceOpen).doubleValue());
            values.put("High", invert(sourceLow).doubleValue());
            values.put("Low", invert(sourceHigh).doubleValue());
            values.put("Close", invert(sourceClose).doubleValue());
            values.put("Volume", quoteVolume.doubleValue());
        } else {
            values.put("Open", sourceOpen.doubleValue());
            values.put("High", sourceHigh.doubleValue());
            values.put("Low", sourceLow.doubleValue());
            values.put("Close", sourceClose.doubleValue());
            values.put("Volume", baseVolume.doubleValue());
        }
        values.put("QuoteVolume", quoteVolume.doubleValue());
        values.put("TradeCount", row.get(8).longValue());
        values.put("SourceOpen", sourceOpen.doubleValue());
        values.put("SourceHigh", sourceHigh.doubleValue());
        values.put("SourceLow", sourceLow.doubleValue());
        values.put("SourceClose", sourceClose.doubleValue());
        values.put("SourceBaseVolume", baseVolume.doubleValue());
        values.put("SourceQuoteVolume", quoteVolume.doubleValue());
        values.put("PriceInverted", route.invertPrice());

        return point(route, businessDate, ingestionTime, values);
    }

    private TimeSeriesPoint toYahooPoint(
            AssetRoute route,
            JsonNode timestamps,
            JsonNode quote,
            JsonNode adjustedClose,
            int index,
            Instant ingestionTime) {
        LocalDate businessDate = Instant.ofEpochSecond(timestamps.get(index).longValue())
                .atZone(ZoneOffset.UTC)
                .toLocalDate();

        Map<String, Object> values = new HashMap<>();
        values.put("Open", quote.path("open").get(index).doubleValue());
        values.put("High", quote.path("high").get(index).doubleValue());
        values.put("Low", quote.path("low").get(index).doubleValue());
        values.put("Close", quote.path("close").get(index).doubleValue());
        if (!isNullAt(quote.path("volume"), index)) {
            values.put("Volume", quote.path("volume").get(index).longValue());
        }
        if (!isNullAt(adjustedClose, index)) {
            values.put("AdjustedClose", adjustedClose.get(index).doubleValue());
        }

        return point(route, businessDate, ingestionTime, values);
    }

    private TimeSeriesPoint point(AssetRoute route, LocalDate businessDate, Instant ingestionTime,
            Map<String, Object> values) {
        TimeSeriesPoint point = new TimeSeriesPoint();
        point.setAssetId(route.assetId());
        point.setDataSourceId(route.dataSourceId());
        point.setBusinessDate(businessDate);
        point.setSystemDate(ingestionTime);
        point.setValues(values);
        point.setMetadata(ingestionMetadata(route, ingestionTime));
        return point;
    }

    private boolean isNullAt(JsonNode array, int index) {
        return array == null || !array.isArray() || index >= array.size() || array.get(index).isNull();
    }

    private BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    private BigDecimal decimal(Number value) {
        return new BigDecimal(value.toString());
    }

    private BigDecimal invert(BigDecimal value) {
        if (BigDecimal.ZERO.compareTo(value) == 0) {
            throw new IllegalStateException("Cannot invert a zero price from Binance");
        }
        return BigDecimal.ONE.divide(value, 18, RoundingMode.HALF_UP);
    }

    private void ensureFinancialAssetExists(AssetRoute route) {
        financialAssetRepository.findById(route.assetId()).orElseGet(() -> {
            Map<String, String> attributes = new HashMap<>(route.attributes());
            attributes.put("symbol", route.assetId());
            attributes.put("assetClass", route.assetClass());
            attributes.put("source", route.provider().displayName());
            attributes.put("sourceSymbol", route.sourceSymbol());

            FinancialAsset asset = new FinancialAsset(
                    route.assetId(),
                    route.name(),
                    route.description(),
                    route.assetClass(),
                    route.region(),
                    attributes);

            try {
                return financialAssetRepository.insert(asset);
            } catch (DuplicateKeyException duplicateKeyException) {
                return financialAssetRepository.findById(route.assetId()).orElse(asset);
            }
        });
    }

    private void ensureDataSourceExists(String dataSourceId) {
        dataSourceRepository.findById(dataSourceId).orElseGet(() -> {
            DataSource dataSource = switch (dataSourceId) {
                case BINANCE_DATA_SOURCE_ID -> new DataSource(
                        BINANCE_DATA_SOURCE_ID,
                        "Binance Public API",
                        "No-key public daily kline data from Binance Spot API.",
                        new HashSet<>(SUPPORTED_ATTRIBUTES));
                case YAHOO_DATA_SOURCE_ID -> new DataSource(
                        YAHOO_DATA_SOURCE_ID,
                        "Yahoo Finance Chart API",
                        "No-key public daily OHLCV chart data from Yahoo Finance.",
                        new HashSet<>(SUPPORTED_ATTRIBUTES));
                default -> throw new IllegalArgumentException("Unsupported data source: " + dataSourceId);
            };

            try {
                return dataSourceRepository.insert(dataSource);
            } catch (DuplicateKeyException duplicateKeyException) {
                return dataSourceRepository.findById(dataSourceId).orElse(dataSource);
            }
        });
    }

    private Map<String, Object> ingestionMetadata(AssetRoute route, Instant ingestionTime) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", route.provider().displayName());
        metadata.put("dataSourceId", route.dataSourceId());
        metadata.put("dataset", route.provider().dataset());
        metadata.put("sourceSymbol", route.sourceSymbol());
        metadata.put("interval", INTERVAL);
        metadata.put("assetSymbol", route.assetId());
        metadata.put("ingestedAt", ingestionTime.toString());
        if (route.provider() == Provider.BINANCE) {
            metadata.put("priceInversion", route.invertPrice());
        }
        return metadata;
    }

    private AssetRoute routeFor(String assetSymbol) {
        String normalized = assetSymbol == null || assetSymbol.isBlank()
                ? ""
                : assetSymbol.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USDT" -> new AssetRoute(
                    "USDT",
                    "Tether USD",
                    "USDT stablecoin priced in USD using public Binance daily klines.",
                    "Crypto",
                    "Global",
                    BINANCE_DATA_SOURCE_ID,
                    "USDCUSDT",
                    Provider.BINANCE,
                    true,
                    Map.of(
                            "coin", "Tether",
                            "baseAsset", "USDT",
                            "quoteCurrency", "USD",
                            "priceInversion", "USDCUSDT inverted to approximate USDT/USD"));
            case "BTC" -> new AssetRoute(
                    "BTC",
                    "Bitcoin",
                    "Bitcoin priced in USD using public Binance BTCUSDT daily klines.",
                    "Crypto",
                    "Global",
                    BINANCE_DATA_SOURCE_ID,
                    "BTCUSDT",
                    Provider.BINANCE,
                    false,
                    Map.of(
                            "coin", "Bitcoin",
                            "baseAsset", "BTC",
                            "quoteCurrency", "USD"));
            case "AAPL" -> new AssetRoute(
                    "AAPL",
                    "Apple Inc.",
                    "Apple common stock daily OHLCV data from Yahoo Finance.",
                    "Stock",
                    "US",
                    YAHOO_DATA_SOURCE_ID,
                    "AAPL",
                    Provider.YAHOO,
                    false,
                    Map.of(
                            "exchange", "NASDAQ",
                            "source", "Yahoo Finance public chart API",
                            "currency", "USD"));
            default -> throw new IllegalArgumentException("Unsupported asset. Only USDT, BTC, and AAPL are allowed.");
        };
    }

    private boolean isDeleted(TimeSeriesPoint point) {
        return Boolean.TRUE.equals(point.getMetadata().get("deleted"));
    }

    private boolean sameBusinessValues(Map<String, Object> existingValues, Map<String, Object> candidateValues) {
        if (existingValues == null || candidateValues == null) {
            return existingValues == candidateValues;
        }
        if (!existingValues.keySet().equals(candidateValues.keySet())) {
            return false;
        }
        for (String key : existingValues.keySet()) {
            if (!sameValue(key, existingValues.get(key), candidateValues.get(key))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameValue(String key, Object existingValue, Object candidateValue) {
        if (existingValue == candidateValue) {
            return true;
        }
        if (existingValue == null || candidateValue == null) {
            return false;
        }
        if (existingValue instanceof Number existingNumber && candidateValue instanceof Number candidateNumber) {
            BigDecimal existingDecimal = decimal(existingNumber);
            BigDecimal candidateDecimal = decimal(candidateNumber);
            if (isIntegral(existingNumber) && isIntegral(candidateNumber)) {
                return existingDecimal.compareTo(candidateDecimal) == 0;
            }
            BigDecimal tolerance = "AdjustedClose".equals(key)
                    ? ADJUSTED_CLOSE_COMPARISON_TOLERANCE
                    : NUMERIC_COMPARISON_TOLERANCE;
            return existingDecimal.subtract(candidateDecimal).abs()
                    .compareTo(tolerance) <= 0;
        }
        return existingValue.equals(candidateValue);
    }

    private boolean isIntegral(Number value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    private static String httpErrorDetail(RestClientResponseException exception) {
        try {
            byte[] raw = exception.getResponseBodyAsByteArray();
            if (raw == null || raw.length == 0) {
                return "";
            }
            String body = new String(raw, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            if (body.length() > 400) {
                body = body.substring(0, 400) + "...";
            }
            return "Response: " + body;
        } catch (Exception ignored) {
            return "";
        }
    }

    public record IngestionSummary(
            String assetId,
            String dataSourceId,
            String sourceSymbol,
            int fetchedRecords,
            int insertedRecords,
            int skippedRecords,
            int pagesFetched,
            LocalDate firstBusinessDate,
            LocalDate lastBusinessDate,
            boolean providerExhausted) {
    }

    public static class IngestionException extends RuntimeException {

        public IngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private enum Provider {
        BINANCE("Binance Public API", "binance-spot-klines"),
        YAHOO("Yahoo Finance Chart API", "yahoo-finance-chart");

        private final String displayName;
        private final String dataset;

        Provider(String displayName, String dataset) {
            this.displayName = displayName;
            this.dataset = dataset;
        }

        String displayName() {
            return displayName;
        }

        String dataset() {
            return dataset;
        }
    }

    private record AssetRoute(
            String assetId,
            String name,
            String description,
            String assetClass,
            String region,
            String dataSourceId,
            String sourceSymbol,
            Provider provider,
            boolean invertPrice,
            Map<String, String> attributes) {
    }
}
