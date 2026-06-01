package com.example.demo.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.demo.model.TimeSeriesPoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface TimeSeriesPointRepository extends MongoRepository<TimeSeriesPoint, String> {

    List<TimeSeriesPoint> findByAssetIdAndDataSourceIdAndBusinessDateBetweenOrderByBusinessDateAscSystemDateDesc(
            String assetId, String dataSourceId, LocalDate startBusinessDate, LocalDate endBusinessDate);

    Page<TimeSeriesPoint> findByAssetIdAndDataSourceIdAndBusinessDateBetween(
            String assetId, String dataSourceId, LocalDate startBusinessDate, LocalDate endBusinessDate,
            Pageable pageable);

    @Query(value = "{ 'asset_id': ?0, 'data_source_id': ?1, 'business_date': { $gte: ?2, $lt: ?3 } }",
            sort = "{ 'business_date': -1, 'system_date': -1 }")
    List<TimeSeriesPoint> findVersionsForBusinessDateWindow(
            String assetId, String dataSourceId, LocalDate startBusinessDate, LocalDate endBusinessDate);

    @Query(value = "{ 'asset_id': ?0, 'data_source_id': ?1 }",
            sort = "{ 'business_date': -1, 'system_date': -1 }")
    List<TimeSeriesPoint> findVersionsForAssetAndDataSource(
            String assetId, String dataSourceId, Pageable pageable);

    Optional<TimeSeriesPoint> findFirstByAssetIdAndBusinessDateOrderBySystemDateDesc(
            String assetId, LocalDate businessDate);

    Optional<TimeSeriesPoint> findFirstByAssetIdAndDataSourceIdAndBusinessDateOrderBySystemDateDesc(
            String assetId, String dataSourceId, LocalDate businessDate);

    Optional<TimeSeriesPoint> findFirstByAssetIdAndDataSourceIdOrderByBusinessDateAscSystemDateDesc(
            String assetId, String dataSourceId);

    Optional<TimeSeriesPoint> findFirstByAssetIdAndDataSourceIdOrderByBusinessDateDescSystemDateDesc(
            String assetId, String dataSourceId);

    @Query(value = "{ 'asset_id': ?0, 'data_source_id': ?1, 'metadata.dataset': { $ne: 'demo-seed' }, '_id': { $not: { $regex: '^codex-sample-' } } }",
            sort = "{ 'business_date': 1, 'system_date': -1 }")
    List<TimeSeriesPoint> findProviderPointsByAssetIdAndDataSourceIdOrderByBusinessDateAsc(
            String assetId, String dataSourceId, Pageable pageable);

    @Query(value = "{ 'asset_id': ?0, 'data_source_id': ?1, 'metadata.dataset': { $ne: 'demo-seed' }, '_id': { $not: { $regex: '^codex-sample-' } } }",
            sort = "{ 'business_date': -1, 'system_date': -1 }")
    List<TimeSeriesPoint> findProviderPointsByAssetIdAndDataSourceIdOrderByBusinessDateDesc(
            String assetId, String dataSourceId, Pageable pageable);

    long countByAssetIdAndDataSourceId(String assetId, String dataSourceId);

    @Query(value = "{ 'asset_id': ?0, 'data_source_id': ?1, 'metadata.dataset': { $ne: 'demo-seed' }, '_id': { $not: { $regex: '^codex-sample-' } } }",
            count = true)
    long countProviderPointsByAssetIdAndDataSourceId(String assetId, String dataSourceId);

    default TimeSeriesPoint saveDeletionMarker(String assetId, String dataSourceId, LocalDate businessDate) {
        return saveDeletionMarker(assetId, dataSourceId, businessDate, null);
    }

    default TimeSeriesPoint saveDeletionMarker(String assetId, String dataSourceId, LocalDate businessDate,
            String deletedRecordId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deleted", true);
        if (deletedRecordId != null) {
            metadata.put("deletedRecordId", deletedRecordId);
        }

        TimeSeriesPoint deletionMarker = new TimeSeriesPoint();
        deletionMarker.setAssetId(assetId);
        deletionMarker.setDataSourceId(dataSourceId);
        deletionMarker.setBusinessDate(businessDate);
        deletionMarker.setSystemDate(Instant.now());
        deletionMarker.setMetadata(metadata);

        return save(deletionMarker);
    }

    @Override
    default void deleteById(String id) {
        findById(id).ifPresent(this::delete);
    }

    @Override
    default void delete(TimeSeriesPoint entity) {
        if (entity == null || isDeletionMarker(entity)) {
            return;
        }

        saveDeletionMarker(entity.getAssetId(), entity.getDataSourceId(), entity.getBusinessDate(), entity.getId());
    }

    @Override
    default void deleteAllById(Iterable<? extends String> ids) {
        ids.forEach(this::deleteById);
    }

    @Override
    default void deleteAll(Iterable<? extends TimeSeriesPoint> entities) {
        entities.forEach(this::delete);
    }

    @Override
    default void deleteAll() {
        findAll().forEach(this::delete);
    }

    private boolean isDeletionMarker(TimeSeriesPoint point) {
        return Boolean.TRUE.equals(point.getMetadata().get("deleted"));
    }
}
