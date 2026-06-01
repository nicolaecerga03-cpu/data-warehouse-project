package com.example.demo.repository;

import com.example.demo.model.AnalyticsSummary;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AnalyticsSummaryRepository extends MongoRepository<AnalyticsSummary, String> {
}
