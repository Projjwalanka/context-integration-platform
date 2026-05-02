package com.bank.aiassistant.repository;

import com.bank.aiassistant.model.entity.IngestionDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IngestionDocumentRepository extends MongoRepository<IngestionDocument, String> {
    List<IngestionDocument> findByConnectorIdOrderByUploadedAtDesc(String connectorId);
}
