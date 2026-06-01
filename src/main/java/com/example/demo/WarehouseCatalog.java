package com.example.demo;

import java.util.List;

public final class WarehouseCatalog {

    public static final List<String> ALLOWED_ASSET_IDS = List.of("USDT", "BTC", "AAPL");
    public static final List<String> ALLOWED_DATA_SOURCE_IDS = List.of("BINANCE", "YAHOO");

    private WarehouseCatalog() {
    }

    public static boolean isAllowedAsset(String assetId) {
        return ALLOWED_ASSET_IDS.contains(assetId);
    }

    public static boolean isAllowedDataSource(String dataSourceId) {
        return ALLOWED_DATA_SOURCE_IDS.contains(dataSourceId);
    }
}
