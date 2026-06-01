package com.example.demo;

import java.util.Map;
import java.util.Set;

import com.example.demo.model.DataSource;
import com.example.demo.model.FinancialAsset;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootApplication
public class DemoApplication {

	@Autowired(required = false)
	private MongoTemplate mongoTemplate;

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@PostConstruct
	public void initializeAllowedWarehouseCatalog() {
		if (mongoTemplate == null) {
			return;
		}

		insertCatalogAssetIfMissing(new FinancialAsset(
				"USDT",
				"Tether USD",
				"USDT stablecoin priced in USD using public Binance daily klines.",
				"Crypto",
				"Global",
				Map.of(
						"symbol", "USDT",
						"assetClass", "Crypto",
						"sourceSymbol", "USDCUSDT",
						"quoteCurrency", "USD")));
		insertCatalogAssetIfMissing(new FinancialAsset(
				"BTC",
				"Bitcoin",
				"Bitcoin priced in USD using public Binance BTCUSDT daily klines.",
				"Crypto",
				"Global",
				Map.of(
						"symbol", "BTC",
						"assetClass", "Crypto",
						"sourceSymbol", "BTCUSDT",
						"quoteCurrency", "USD")));
		insertCatalogAssetIfMissing(new FinancialAsset(
				"AAPL",
				"Apple Inc.",
				"Apple common stock daily OHLCV data from Yahoo Finance.",
				"Stock",
				"US",
				Map.of(
						"symbol", "AAPL",
						"assetClass", "Stock",
						"exchange", "NASDAQ",
						"source", "Yahoo Finance public chart API",
						"currency", "USD")));

		insertCatalogDataSourceIfMissing(new DataSource(
				"BINANCE",
				"Binance Public API",
				"No-key public daily kline data from Binance Spot API.",
				Set.of("Open", "High", "Low", "Close", "Volume", "QuoteVolume", "TradeCount")));
		insertCatalogDataSourceIfMissing(new DataSource(
				"YAHOO",
				"Yahoo Finance Chart API",
				"No-key public daily OHLCV chart data from Yahoo Finance.",
				Set.of("Open", "High", "Low", "Close", "Volume", "AdjustedClose")));
	}

	private void insertCatalogAssetIfMissing(FinancialAsset asset) {
		if (mongoTemplate.exists(new Query(Criteria.where("_id").is(asset.getAssetId())), FinancialAsset.class)) {
			return;
		}
		try {
			mongoTemplate.insert(asset);
		} catch (DuplicateKeyException ignored) {
			// Another application instance inserted the same catalog row first.
		}
	}

	private void insertCatalogDataSourceIfMissing(DataSource dataSource) {
		if (mongoTemplate.exists(new Query(Criteria.where("_id").is(dataSource.getId())), DataSource.class)) {
			return;
		}
		try {
			mongoTemplate.insert(dataSource);
		} catch (DuplicateKeyException ignored) {
			// Another application instance inserted the same catalog row first.
		}
	}
}
