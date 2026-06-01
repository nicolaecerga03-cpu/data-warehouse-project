package com.example.demo.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class DataWarehouseQueryService {

    private final FinancialAssetRepository financialAssetRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TimeSeriesPointRepository timeSeriesPointRepository;

    public DataWarehouseQueryService(
            FinancialAssetRepository financialAssetRepository,
            DataSourceRepository dataSourceRepository,
            TimeSeriesPointRepository timeSeriesPointRepository) {
        this.financialAssetRepository = financialAssetRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.timeSeriesPointRepository = timeSeriesPointRepository;
    }

    public List<String> findAssetIds(int offset, int limit) {
        Pageable pageable = pageFromOffset(offset, limit, Sort.by(Sort.Direction.ASC, "assetId"));
        return financialAssetRepository.findByAssetIdIn(WarehouseCatalog.ALLOWED_ASSET_IDS, pageable).stream()
                .map(FinancialAsset::getAssetId)
                .toList();
    }

    public Optional<FinancialAsset> findAsset(String assetId) {
        if (!WarehouseCatalog.isAllowedAsset(assetId)) {
            return Optional.empty();
        }
        return financialAssetRepository.findById(assetId);
    }

    public List<AssetSummary> findAssetCatalog(
            int offset,
            int limit,
            String assetClass,
            String region,
            String sortBy,
            String direction) {
        Sort sort = Sort.by(sortDirection(direction), sortableAssetField(sortBy));
        Pageable pageable = pageFromOffset(offset, limit, sort);
        String normalizedClass = normalizeFilter(assetClass);
        String normalizedRegion = normalizeFilter(region);

        if (normalizedClass != null && normalizedRegion != null) {
            return financialAssetRepository
                    .findByAssetIdInAndAssetClassIgnoreCaseAndRegionIgnoreCase(
                            WarehouseCatalog.ALLOWED_ASSET_IDS, normalizedClass, normalizedRegion, pageable)
                    .stream()
                    .map(this::toAssetSummary)
                    .toList();
        }
        if (normalizedClass != null) {
            return financialAssetRepository
                    .findByAssetIdInAndAssetClassIgnoreCase(
                            WarehouseCatalog.ALLOWED_ASSET_IDS, normalizedClass, pageable).stream()
                    .map(this::toAssetSummary)
                    .toList();
        }
        if (normalizedRegion != null) {
            return financialAssetRepository
                    .findByAssetIdInAndRegionIgnoreCase(
                            WarehouseCatalog.ALLOWED_ASSET_IDS, normalizedRegion, pageable).stream()
                    .map(this::toAssetSummary)
                    .toList();
        }

        return financialAssetRepository.findByAssetIdIn(WarehouseCatalog.ALLOWED_ASSET_IDS, pageable).stream()
                .map(this::toAssetSummary)
                .toList();
    }

    public List<String> findDataSourceIds(int offset, int limit) {
        Pageable pageable = pageFromOffset(offset, limit, Sort.by(Sort.Direction.ASC, "id"));
        return dataSourceRepository.findByIdIn(WarehouseCatalog.ALLOWED_DATA_SOURCE_IDS, pageable).stream()
                .map(DataSource::getId)
                .toList();
    }

    public Optional<DataSource> findDataSource(String dataSourceId) {
        if (!WarehouseCatalog.isAllowedDataSource(dataSourceId)) {
            return Optional.empty();
        }
        return dataSourceRepository.findById(dataSourceId);
    }

    public List<Map<String, Object>> findLatestTimeSeriesData(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate,
            boolean includeAttributes) {
        return findLatestTimeSeriesData(assetId, dataSourceId, startBusinessDate, endBusinessDate, includeAttributes, 0,
                Integer.MAX_VALUE);
    }

    public List<Map<String, Object>> findLatestTimeSeriesData(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate,
            boolean includeAttributes,
            int offset,
            int limit) {
        return findLatestTimeSeriesData(
                assetId, dataSourceId, startBusinessDate, endBusinessDate, includeAttributes, offset, limit,
                "desc", null);
    }

    public List<Map<String, Object>> findLatestTimeSeriesData(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate,
            boolean includeAttributes,
            int offset,
            int limit,
            String sortDirection,
            String indicator) {
        validateAllowedMarket(assetId, dataSourceId);
        if (!startBusinessDate.isBefore(endBusinessDate)) {
            throw new IllegalArgumentException("startBusinessDate must be before endBusinessDate");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }

        List<TimeSeriesPoint> allVersions =
                timeSeriesPointRepository.findVersionsForBusinessDateWindow(
                        assetId, dataSourceId, startBusinessDate, endBusinessDate);

        List<Map<String, Object>> latestRecords = new ArrayList<>();
        Set<LocalDate> seenBusinessDates = new java.util.HashSet<>();

        for (TimeSeriesPoint point : allVersions) {
            if (!seenBusinessDates.add(point.getBusinessDate())) {
                continue;
            }

            if (!isDeletionMarker(point) && matchesIndicator(point, indicator)) {
                latestRecords.add(toResponse(point, includeAttributes));
            }
        }

        if ("asc".equalsIgnoreCase(sortDirection)) {
            latestRecords = new ArrayList<>(latestRecords);
            latestRecords.sort((left, right) -> ((LocalDate) left.get("businessDate"))
                    .compareTo((LocalDate) right.get("businessDate")));
        }

        int startIndex = Math.min(offset, latestRecords.size());
        long requestedEnd = (long) startIndex + (long) limit;
        int endIndex = (int) Math.min(requestedEnd, latestRecords.size());
        return latestRecords.subList(startIndex, endIndex);
    }

    public Optional<Map<String, Object>> findMostCurrentTimeSeriesData(
            String assetId,
            String dataSourceId,
            boolean includeAttributes,
            String indicator) {
        validateAllowedMarket(assetId, dataSourceId);
        List<TimeSeriesPoint> versions =
                timeSeriesPointRepository.findVersionsForAssetAndDataSource(
                        assetId, dataSourceId, Pageable.ofSize(1000));
        Set<LocalDate> seenBusinessDates = new java.util.HashSet<>();

        for (TimeSeriesPoint point : versions) {
            if (!seenBusinessDates.add(point.getBusinessDate())) {
                continue;
            }
            if (!isDeletionMarker(point) && matchesIndicator(point, indicator)) {
                return Optional.of(toResponse(point, includeAttributes));
            }
        }

        return Optional.empty();
    }

    public TimeSeriesDateRange findTimeSeriesDateRange(String assetId, String dataSourceId) {
        validateAllowedMarket(assetId, dataSourceId);
        Optional<TimeSeriesPoint> providerEarliest =
                timeSeriesPointRepository.findProviderPointsByAssetIdAndDataSourceIdOrderByBusinessDateAsc(
                        assetId, dataSourceId, Pageable.ofSize(1)).stream().findFirst();
        Optional<TimeSeriesPoint> providerLatest =
                timeSeriesPointRepository.findProviderPointsByAssetIdAndDataSourceIdOrderByBusinessDateDesc(
                        assetId, dataSourceId, Pageable.ofSize(1)).stream().findFirst();

        Optional<TimeSeriesPoint> earliest = providerEarliest.isPresent() && providerLatest.isPresent()
                ? providerEarliest
                : timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdOrderByBusinessDateAscSystemDateDesc(
                        assetId, dataSourceId);
        Optional<TimeSeriesPoint> latest = providerEarliest.isPresent() && providerLatest.isPresent()
                ? providerLatest
                : timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdOrderByBusinessDateDescSystemDateDesc(
                        assetId, dataSourceId);

        if (earliest.isEmpty() || latest.isEmpty()) {
            return new TimeSeriesDateRange(assetId, dataSourceId, null, null, null, null, 0, false);
        }

        boolean providerData = providerEarliest.isPresent() && providerLatest.isPresent();
        long recordCount = providerData
                ? timeSeriesPointRepository.countProviderPointsByAssetIdAndDataSourceId(assetId, dataSourceId)
                : timeSeriesPointRepository.countByAssetIdAndDataSourceId(assetId, dataSourceId);

        LocalDate latestDate = latest.get().getBusinessDate();
        LocalDate suggestedStartDate = latestDate.minusMonths(1);
        if (suggestedStartDate.isBefore(earliest.get().getBusinessDate())) {
            suggestedStartDate = earliest.get().getBusinessDate();
        }

        return new TimeSeriesDateRange(
                assetId,
                dataSourceId,
                earliest.get().getBusinessDate(),
                latestDate,
                suggestedStartDate,
                latestDate.plusDays(1),
                recordCount,
                !providerData);
    }

    public TimeSeriesDateRange findTimeSeriesDateRangeIncludingDemo(String assetId, String dataSourceId) {
        validateAllowedMarket(assetId, dataSourceId);
        Optional<TimeSeriesPoint> earliest =
                timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdOrderByBusinessDateAscSystemDateDesc(
                        assetId, dataSourceId);
        Optional<TimeSeriesPoint> latest =
                timeSeriesPointRepository.findFirstByAssetIdAndDataSourceIdOrderByBusinessDateDescSystemDateDesc(
                        assetId, dataSourceId);

        if (earliest.isEmpty() || latest.isEmpty()) {
            return new TimeSeriesDateRange(assetId, dataSourceId, null, null, null, null, 0, true);
        }

        LocalDate latestDate = latest.get().getBusinessDate();
        LocalDate suggestedStartDate = latestDate.minusMonths(1);
        if (suggestedStartDate.isBefore(earliest.get().getBusinessDate())) {
            suggestedStartDate = earliest.get().getBusinessDate();
        }

        return new TimeSeriesDateRange(
                assetId,
                dataSourceId,
                earliest.get().getBusinessDate(),
                latestDate,
                suggestedStartDate,
                latestDate.plusDays(1),
                timeSeriesPointRepository.countByAssetIdAndDataSourceId(assetId, dataSourceId),
                true);
    }

    private Pageable pageFromOffset(int offset, int limit, Sort sort) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be greater than or equal to 0");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }

        return new OffsetLimitPageable(offset, limit, sort);
    }

    private AssetSummary toAssetSummary(FinancialAsset asset) {
        return new AssetSummary(
                asset.getAssetId(),
                asset.getName(),
                asset.getDescription(),
                asset.getAssetClass(),
                asset.getRegion(),
                asset.getAttributes());
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        return value.trim();
    }

    private Sort.Direction sortDirection(String direction) {
        if ("desc".equalsIgnoreCase(direction)) {
            return Sort.Direction.DESC;
        }
        return Sort.Direction.ASC;
    }

    private String sortableAssetField(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "assetId";
        }
        return switch (sortBy.trim().toLowerCase(Locale.ROOT)) {
            case "name" -> "name";
            case "class", "assetclass", "asset_class" -> "assetClass";
            case "region" -> "region";
            default -> "assetId";
        };
    }

    private Map<String, Object> toResponse(TimeSeriesPoint point, boolean includeAttributes) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", point.getId());
        response.put("assetId", point.getAssetId());
        response.put("dataSourceId", point.getDataSourceId());
        response.put("businessDate", point.getBusinessDate());
        response.put("systemDate", point.getSystemDate());

        if (includeAttributes) {
            response.put("values", point.getValues());
        }

        return response;
    }

    private boolean isDeletionMarker(TimeSeriesPoint point) {
        return Boolean.TRUE.equals(point.getMetadata().get("deleted"));
    }

    private boolean matchesIndicator(TimeSeriesPoint point, String indicator) {
        return indicator == null || indicator.isBlank() || "all".equalsIgnoreCase(indicator)
                || point.getValues().containsKey(indicator);
    }

    private void validateAllowedMarket(String assetId, String dataSourceId) {
        if (!WarehouseCatalog.isAllowedAsset(assetId)) {
            throw new IllegalArgumentException("Unsupported asset. Only USDT, BTC, and AAPL are allowed.");
        }
        if (!WarehouseCatalog.isAllowedDataSource(dataSourceId)) {
            throw new IllegalArgumentException("Unsupported data source. Only BINANCE and YAHOO are allowed.");
        }
    }

    private record OffsetLimitPageable(int offset, int pageSize, Sort sort) implements Pageable {

        @Override
        public int getPageNumber() {
            return offset / pageSize;
        }

        @Override
        public int getPageSize() {
            return pageSize;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return sort;
        }

        @Override
        public Pageable next() {
            return new OffsetLimitPageable(offset + pageSize, pageSize, sort);
        }

        @Override
        public Pageable previousOrFirst() {
            if (!hasPrevious()) {
                return first();
            }
            return new OffsetLimitPageable(Math.max(offset - pageSize, 0), pageSize, sort);
        }

        @Override
        public Pageable first() {
            return new OffsetLimitPageable(0, pageSize, sort);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            return new OffsetLimitPageable(pageNumber * pageSize, pageSize, sort);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }

    public record AssetSummary(
            String assetId,
            String name,
            String description,
            String assetClass,
            String region,
            Map<String, String> attributes) {
    }

    public record TimeSeriesDateRange(
            String assetId,
            String dataSourceId,
            LocalDate earliestBusinessDate,
            LocalDate latestBusinessDate,
            LocalDate suggestedStartBusinessDate,
            LocalDate endExclusiveBusinessDate,
            long recordCount,
            boolean demoFallback) {
    }
}
