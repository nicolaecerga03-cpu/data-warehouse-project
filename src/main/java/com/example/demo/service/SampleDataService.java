package com.example.demo.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.demo.model.DataSource;
import com.example.demo.model.FinancialAsset;
import com.example.demo.model.TimeSeriesPoint;
import com.example.demo.repository.DataSourceRepository;
import com.example.demo.repository.FinancialAssetRepository;
import com.example.demo.repository.TimeSeriesPointRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class SampleDataService {

    private final FinancialAssetRepository financialAssetRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TimeSeriesPointRepository timeSeriesPointRepository;

    public SampleDataService(
            FinancialAssetRepository financialAssetRepository,
            DataSourceRepository dataSourceRepository,
            TimeSeriesPointRepository timeSeriesPointRepository) {
        this.financialAssetRepository = financialAssetRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.timeSeriesPointRepository = timeSeriesPointRepository;
    }

    public SampleDataSummary seedDemoData() {
        int assetsCreated = 0;
        int dataSourcesCreated = 0;
        int insertedPoints = 0;

        dataSourcesCreated += ensureDataSource(
                "BINANCE",
                "Binance Public API",
                "No-key public crypto daily kline data",
                Set.of("Open", "High", "Low", "Close", "Volume", "QuoteVolume", "TradeCount"));
        dataSourcesCreated += ensureDataSource(
                "YAHOO",
                "Yahoo Finance Chart API",
                "No-key public daily OHLCV chart data from Yahoo Finance",
                Set.of("Open", "High", "Low", "Close", "Volume", "AdjustedClose"));

        assetsCreated += ensureAsset("USDT", "Tether USD", "USDT stablecoin priced in US dollars", "Crypto", "Global",
                Map.of("symbol", "USDT", "sourceSymbol", "USDCUSDT", "quoteCurrency", "USD"));
        assetsCreated += ensureAsset("BTC", "Bitcoin", "Bitcoin priced in US dollars", "Crypto", "Global",
                Map.of("symbol", "BTC", "sourceSymbol", "BTCUSDT", "quoteCurrency", "USD"));
        assetsCreated += ensureAsset("AAPL", "Apple Inc.", "Apple common stock", "Stock", "US",
                Map.of("symbol", "AAPL", "exchange", "NASDAQ", "currency", "USD"));

        insertedPoints += seedSeries("USDT", "BINANCE", List.of(
                values("2024-01-02", 1.0001, 1.0003, 0.9997, 1.0000, 2267078480.0, "QuoteVolume", 2267078480.0),
                values("2024-01-03", 1.0000, 1.0002, 0.9996, 0.9999, 1800000000.0, "QuoteVolume", 1800000000.0),
                values("2024-01-04", 0.9999, 1.0004, 0.9997, 1.0002, 2100000000.0, "QuoteVolume", 2100000000.0)));
        insertedPoints += seedSeries("BTC", "BINANCE", List.of(
                values("2024-01-02", 44167.33, 45922.41, 44101.55, 44957.97, 28540.0, "QuoteVolume", 1283000000.0),
                values("2024-01-03", 44957.97, 45509.18, 40813.53, 42848.18, 51210.0, "QuoteVolume", 2194000000.0),
                values("2024-01-04", 42848.18, 44770.02, 42675.21, 44157.02, 33190.0, "QuoteVolume", 1466000000.0)));
        insertedPoints += seedSeries("AAPL", "YAHOO", List.of(
                values("2024-01-02", 187.15, 188.44, 183.89, 185.64, 82488700.0),
                values("2024-01-03", 184.22, 185.88, 183.43, 184.25, 58414500.0),
                values("2024-01-04", 182.15, 183.09, 180.88, 181.91, 71983600.0)));

        return new SampleDataSummary(assetsCreated, dataSourcesCreated, insertedPoints);
    }

    private int ensureAsset(
            String assetId,
            String name,
            String description,
            String assetClass,
            String region,
            Map<String, String> attributes) {
        Optional<FinancialAsset> existing = financialAssetRepository.findById(assetId);
        if (existing.isPresent()) {
            return 0;
        }
        Map<String, String> savedAttributes = new HashMap<>(attributes);
        savedAttributes.put("assetClass", assetClass);
        FinancialAsset asset = new FinancialAsset(assetId, name, description, assetClass, region, savedAttributes);
        try {
            financialAssetRepository.insert(asset);
            return 1;
        } catch (DuplicateKeyException ignored) {
            return 0;
        }
    }

    private int ensureDataSource(String id, String name, String description, Set<String> supportedAttributes) {
        if (dataSourceRepository.existsById(id)) {
            return 0;
        }
        try {
            dataSourceRepository.insert(new DataSource(id, name, description, new HashSet<>(supportedAttributes)));
            return 1;
        } catch (DuplicateKeyException ignored) {
            return 0;
        }
    }

    private int seedSeries(String assetId, String dataSourceId, List<Map<String, Object>> rows) {
        int inserted = 0;
        Instant systemDate = Instant.now();
        for (Map<String, Object> row : rows) {
            LocalDate businessDate = LocalDate.parse(row.remove("Date").toString());
            Optional<TimeSeriesPoint> latest =
                    timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
                            assetId, dataSourceId, businessDate);
            if (latest.isPresent() && latest.get().getValues().equals(row)) {
                continue;
            }

            TimeSeriesPoint point = new TimeSeriesPoint();
            point.setAssetId(assetId);
            point.setDataSourceId(dataSourceId);
            point.setBusinessDate(businessDate);
            point.setSystemDate(systemDate);
            point.setValues(row);
            point.setMetadata(Map.of(
                    "provider", dataSourceId,
                    "dataset", "demo-seed",
                    "ingestedAt", systemDate.toString()));
            timeSeriesPointRepository.save(point);
            inserted++;
        }
        return inserted;
    }

    private Map<String, Object> values(
            String date,
            double open,
            double high,
            double low,
            double close,
            Double volume,
            Object... extraPairs) {
        Map<String, Object> values = new HashMap<>();
        values.put("Date", date);
        values.put("Open", open);
        values.put("High", high);
        values.put("Low", low);
        values.put("Close", close);
        if (volume != null) {
            values.put("Volume", volume);
        }
        for (int i = 0; i < extraPairs.length; i += 2) {
            values.put(extraPairs[i].toString(), extraPairs[i + 1]);
        }
        return values;
    }

    public record SampleDataSummary(int assetsCreated, int dataSourcesCreated, int insertedPoints) {
    }
}
