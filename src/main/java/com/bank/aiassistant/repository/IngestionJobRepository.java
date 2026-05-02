package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.IngestionJob;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IngestionJobRepository extends MongoRepository<IngestionJob, String> {
    List<IngestionJob> findByStatusOrderByCreatedAtDesc(IngestionJob.JobStatus status);
    List<IngestionJob> findTop20ByOrderByCreatedAtDesc();
    List<IngestionJob> findByConnectorIdOrderByCreatedAtDesc(String connectorId);
    List<IngestionJob> findByConnectorTypeOrderByCreatedAtDesc(String connectorType);
}
