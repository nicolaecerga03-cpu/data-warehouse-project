package com.example.demo.repository;

import com.example.demo.model.PredictionResult;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PredictionResultRepository extends MongoRepository<PredictionResult, String> {
}
