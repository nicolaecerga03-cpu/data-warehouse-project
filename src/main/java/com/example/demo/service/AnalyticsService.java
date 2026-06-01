package com.example.demo.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.demo.model.AnalyticsSummary;
import com.example.demo.model.PredictionResult;
import com.example.demo.repository.AnalyticsSummaryRepository;
import com.example.demo.repository.PredictionResultRepository;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private static final String CLOSE = "Close";

    private final DataWarehouseQueryService dataWarehouseQueryService;
    private final AnalyticsSummaryRepository analyticsSummaryRepository;
    private final PredictionResultRepository predictionResultRepository;

    public AnalyticsService(
            DataWarehouseQueryService dataWarehouseQueryService,
            AnalyticsSummaryRepository analyticsSummaryRepository,
            PredictionResultRepository predictionResultRepository) {
        this.dataWarehouseQueryService = dataWarehouseQueryService;
        this.analyticsSummaryRepository = analyticsSummaryRepository;
        this.predictionResultRepository = predictionResultRepository;
    }

    public List<AnalyticsSummary> computeYearlySummary(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        List<PointSnapshot> points = loadCloseSnapshots(assetId, dataSourceId, startBusinessDate, endBusinessDate);
        Map<Integer, List<PointSnapshot>> byYear = new TreeMap<>();
        for (PointSnapshot point : points) {
            byYear.computeIfAbsent(point.businessDate().getYear(), ignored -> new ArrayList<>()).add(point);
        }

        Instant systemDate = Instant.now();
        List<AnalyticsSummary> summaries = new ArrayList<>();
        for (Map.Entry<Integer, List<PointSnapshot>> entry : byYear.entrySet()) {
            List<PointSnapshot> yearPoints = entry.getValue();
            yearPoints.sort(Comparator.comparing(PointSnapshot::businessDate));
            DoubleSummaryStatistics stats = yearPoints.stream()
                    .mapToDouble(PointSnapshot::close)
                    .summaryStatistics();

            AnalyticsSummary summary = new AnalyticsSummary();
            summary.setAssetId(assetId);
            summary.setDataSourceId(dataSourceId);
            summary.setBusinessYear(entry.getKey());
            summary.setSystemDate(systemDate);
            summary.setRecordCount(stats.getCount());
            summary.setMinimumClose(stats.getMin());
            summary.setMaximumClose(stats.getMax());
            summary.setAverageClose(stats.getAverage());
            summary.setFirstClose(yearPoints.get(0).close());
            summary.setLastClose(yearPoints.get(yearPoints.size() - 1).close());
            summary.setReturnPercent(percentChange(summary.getLastClose(), summary.getFirstClose()));
            summaries.add(summary);
        }

        if (!summaries.isEmpty()) {
            analyticsSummaryRepository.saveAll(summaries);
        }
        return summaries;
    }

    public PredictionResult predictNextClose(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        List<PointSnapshot> points = loadCloseSnapshots(assetId, dataSourceId, startBusinessDate, endBusinessDate);
        if (points.size() < 2) {
            throw new IllegalArgumentException("At least two Close values are required to create a prediction");
        }

        points.sort(Comparator.comparing(PointSnapshot::businessDate));
        double[] coefficients = linearRegression(points);
        double nextX = points.size();
        double predictedClose = coefficients[0] + coefficients[1] * nextX;

        PredictionResult result = new PredictionResult();
        result.setAssetId(assetId);
        result.setDataSourceId(dataSourceId);
        result.setGeneratedAt(Instant.now());
        result.setInputStartDate(startBusinessDate);
        result.setInputEndDate(endBusinessDate);
        result.setTargetBusinessDate(nextBusinessDate(points.get(points.size() - 1).businessDate()));
        result.setPredictedClose(predictedClose);
        result.setTrainingRecordCount(points.size());
        result.setModelName("Simple linear regression over latest Close values");

        return predictionResultRepository.save(result);
    }

    public Map<String, Object> riskSignal(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        List<PointSnapshot> points = loadCloseSnapshots(assetId, dataSourceId, startBusinessDate, endBusinessDate);
        points.sort(Comparator.comparing(PointSnapshot::businessDate));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("assetId", assetId);
        response.put("dataSourceId", dataSourceId);
        response.put("recordCount", points.size());

        if (points.size() < 2) {
            response.put("riskLevel", "UNKNOWN");
            response.put("summary", "At least two Close values are required to calculate risk.");
            return response;
        }

        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            double previous = points.get(i - 1).close();
            double current = points.get(i).close();
            if (previous != 0) {
                returns.add((current - previous) / previous);
            }
        }

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0.0);
        double volatilityPercent = Math.sqrt(variance) * 100.0;
        String riskLevel = riskLevel(volatilityPercent);

        response.put("volatilityPercent", volatilityPercent);
        response.put("averageDailyReturnPercent", mean * 100.0);
        response.put("riskLevel", riskLevel);
        response.put("summary", "%s shows %s risk with %.2f%% daily volatility."
                .formatted(assetId, riskLevel.toLowerCase(), volatilityPercent));
        return response;
    }

    public Map<String, Object> compareAssets(
            String firstAssetId,
            String secondAssetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        return compareAssets(
                firstAssetId,
                dataSourceId,
                secondAssetId,
                dataSourceId,
                startBusinessDate,
                endBusinessDate);
    }

    public Map<String, Object> compareAssets(
            String firstAssetId,
            String firstDataSourceId,
            String secondAssetId,
            String secondDataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        Map<String, Object> first = describeTrend(firstAssetId, firstDataSourceId, startBusinessDate, endBusinessDate);
        Map<String, Object> second = describeTrend(secondAssetId, secondDataSourceId, startBusinessDate, endBusinessDate);
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("firstAsset", first);
        comparison.put("secondAsset", second);
        comparison.put("strongerAsset", strongerAsset(firstAssetId, secondAssetId, first, second));
        comparison.put("basis", "Compared percentage change in latest Close values for the requested interval.");
        return comparison;
    }

    private Map<String, Object> describeTrend(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        List<PointSnapshot> points = loadCloseSnapshots(assetId, dataSourceId, startBusinessDate, endBusinessDate);
        points.sort(Comparator.comparing(PointSnapshot::businessDate));
        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("assetId", assetId);
        trend.put("dataSourceId", dataSourceId);
        trend.put("recordCount", points.size());
        if (points.isEmpty()) {
            trend.put("summary", "No records found.");
            return trend;
        }
        PointSnapshot first = points.get(0);
        PointSnapshot last = points.get(points.size() - 1);
        Double change = last.close() - first.close();
        Double percentChange = percentChange(last.close(), first.close());
        trend.put("startBusinessDate", first.businessDate());
        trend.put("endBusinessDate", last.businessDate());
        trend.put("startClose", first.close());
        trend.put("endClose", last.close());
        trend.put("absoluteCloseChange", change);
        trend.put("percentCloseChange", percentChange);
        return trend;
    }

    private List<PointSnapshot> loadCloseSnapshots(
            String assetId,
            String dataSourceId,
            LocalDate startBusinessDate,
            LocalDate endBusinessDate) {
        return new ArrayList<>(dataWarehouseQueryService.findLatestTimeSeriesData(
                        assetId, dataSourceId, startBusinessDate, endBusinessDate, true)
                .stream()
                .map(this::toPointSnapshot)
                .filter(point -> point.close() != null)
                .toList());
    }

    @SuppressWarnings("unchecked")
    private PointSnapshot toPointSnapshot(Map<String, Object> record) {
        LocalDate businessDate = LocalDate.parse(record.get("businessDate").toString());
        Object valuesObject = record.get("values");
        if (!(valuesObject instanceof Map<?, ?> values)) {
            return new PointSnapshot(businessDate, null);
        }
        Object close = ((Map<String, Object>) values).get(CLOSE);
        if (close instanceof Number number) {
            return new PointSnapshot(businessDate, number.doubleValue());
        }
        if (close != null) {
            return new PointSnapshot(businessDate, Double.parseDouble(close.toString()));
        }
        return new PointSnapshot(businessDate, null);
    }

    private double[] linearRegression(List<PointSnapshot> points) {
        int n = points.size();
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumX2 = 0.0;

        for (int i = 0; i < points.size(); i++) {
            double x = i;
            double y = points.get(i).close();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = n * sumX2 - sumX * sumX;
        double slope = denominator == 0.0 ? 0.0 : (n * sumXY - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / n;
        return new double[] {intercept, slope};
    }

    private Double percentChange(Double endValue, Double startValue) {
        if (endValue == null || startValue == null || startValue == 0.0) {
            return null;
        }
        return ((endValue - startValue) / startValue) * 100.0;
    }

    private String riskLevel(double volatilityPercent) {
        if (volatilityPercent >= 5.0) {
            return "HIGH";
        }
        if (volatilityPercent >= 2.0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String strongerAsset(
            String firstAssetId,
            String secondAssetId,
            Map<String, Object> first,
            Map<String, Object> second) {
        Double firstPercent = (Double) first.get("percentCloseChange");
        Double secondPercent = (Double) second.get("percentCloseChange");
        if (firstPercent == null && secondPercent == null) {
            return "Not enough Close data to compare";
        }
        if (firstPercent == null) {
            return secondAssetId;
        }
        if (secondPercent == null) {
            return firstAssetId;
        }
        return firstPercent >= secondPercent ? firstAssetId : secondAssetId;
    }

    private LocalDate nextBusinessDate(LocalDate date) {
        LocalDate next = date.plusDays(1);
        if (next.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return next.plusDays(2);
        }
        if (next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return next.plusDays(1);
        }
        return next;
    }

    private record PointSnapshot(LocalDate businessDate, Double close) {
    }
}
