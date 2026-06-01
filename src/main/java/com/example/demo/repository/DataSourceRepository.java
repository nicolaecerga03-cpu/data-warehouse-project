package com.example.demo.repository;

import java.util.Collection;

import com.example.demo.model.DataSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DataSourceRepository extends MongoRepository<DataSource, String> {

    Page<DataSource> findByIdIn(Collection<String> ids, Pageable pageable);
}
