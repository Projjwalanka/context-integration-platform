package com.bank.aiassistant.service.connector.documents;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.service.connector.spi.ConnectorHealth;
import com.bank.aiassistant.service.connector.spi.ConnectorType;
import com.bank.aiassistant.service.connector.spi.DataSourceConnector;
import com.bank.aiassistant.service.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Static document connector — serves as the "live query" bridge to the vector store
 * for already-ingested documents.
 *
 * <p>Unlike other connectors (Jira, Confluence, etc.) which call external APIs,
 * this connector queries the local pgvector store for static documents
 * (PDFs, DOCX, TXT) that were ingested via the ingestion pipeline.
 *
 * <p>Does not require credentials — no external API calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentConnector implements DataSourceConnector {

    private final VectorStoreService vectorStoreService;

    @Override
    public ConnectorType getType() { return ConnectorType.DOCUMENTS; }

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) {
        return ConnectorHealth.ok(0); // Always healthy — local store
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                               String query, int maxResults) {
        List<Document> docs = vectorStoreService.hybridSearch(
                query, Map.of("source_type", "DOCUMENTS"), maxResults);
        return docs.stream()
                .map(d -> (Map.Entry<String, Map<String, Object>>) Map.entry(
                        d.getText(), d.getMetadata()))
                .toList();
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) {
        return List.of(); // Already in vector store — no bulk fetch needed
    }

    @Override
    public boolean supportsLiveQuery() { return true; }

    @Override
    public boolean supportsBatchIngestion() { return false; } // Ingested via IngestionPipeline
}
