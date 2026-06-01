package com.example.demo.repository;

import java.util.Collection;

import com.example.demo.model.FinancialAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FinancialAssetRepository extends MongoRepository<FinancialAsset, String> {

    Page<FinancialAsset> findAll(Pageable pageable);

    Page<FinancialAsset> findByAssetIdIn(Collection<String> assetIds, Pageable pageable);

    Page<FinancialAsset> findByAssetClassIgnoreCase(String assetClass, Pageable pageable);

    Page<FinancialAsset> findByAssetIdInAndAssetClassIgnoreCase(
            Collection<String> assetIds, String assetClass, Pageable pageable);

    Page<FinancialAsset> findByRegionIgnoreCase(String region, Pageable pageable);

    Page<FinancialAsset> findByAssetIdInAndRegionIgnoreCase(
            Collection<String> assetIds, String region, Pageable pageable);

    Page<FinancialAsset> findByAssetClassIgnoreCaseAndRegionIgnoreCase(
            String assetClass, String region, Pageable pageable);

    Page<FinancialAsset> findByAssetIdInAndAssetClassIgnoreCaseAndRegionIgnoreCase(
            Collection<String> assetIds, String assetClass, String region, Pageable pageable);
}
