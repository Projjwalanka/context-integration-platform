package com.bank.aiassistant.service.vector;

import com.bank.aiassistant.model.entity.IngestionDocument;
import com.bank.aiassistant.repository.IngestionDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Abstraction over the Spring AI {@link VectorStore} (Pinecone backend).
 *
 * <p>Implements a <b>hybrid retrieval</b> strategy:
 * <ol>
 *   <li>Dense (semantic) retrieval via Pinecone similarity search</li>
 *   <li>Metadata filtering for user and connector scoping</li>
 *   <li>Stable rank-fusion helper retained for future hybrid extensions</li>
 * </ol>
 *
 * <p>Metadata filter keys supported:
 * <ul>
 *   <li>{@code source_type} — JIRA, CONFLUENCE, GITHUB, DOCUMENTS, etc.</li>
 *   <li>{@code connector_id} — connector config UUID</li>
 *   <li>{@code user_id} — for user-scoped documents</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final VectorStore vectorStore;
    private final IngestionDocumentRepository ingestionDocumentRepository;

    private static final int DEFAULT_TOP_K = 8;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.30;

    // ─────────────────────────────────────────────────────────────────────────
    // Write
    // ─────────────────────────────────────────────────────────────────────────

    public void store(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;
        log.info("Storing {} document chunks in vector store", documents.size());
        vectorStore.add(documents);
    }

    public void delete(List<String> ids) {
        vectorStore.delete(ids);
        log.info("Deleted {} chunks from vector store", ids.size());
    }

    /**
     * Deletes all Pinecone vectors tracked by IngestionDocuments for the given
     * connector IDs, then removes the IngestionDocument records themselves.
     */
    public void clearForConnectors(String ownerId, List<String> connectorIds) {
        if (connectorIds == null || connectorIds.isEmpty()) return;
        List<IngestionDocument> docs = ingestionDocumentRepository
                .findByOwnerIdAndConnectorIdIn(ownerId, connectorIds);
        List<String> vectorIds = docs.stream()
                .filter(d -> d.getVectorIds() != null)
                .flatMap(d -> d.getVectorIds().stream())
                .collect(Collectors.toList());
        if (!vectorIds.isEmpty()) {
            vectorStore.delete(vectorIds);
            log.info("Cleared {} Pinecone vectors for owner={} connectors={}", vectorIds.size(), ownerId, connectorIds);
        }
        ingestionDocumentRepository.deleteByConnectorIdIn(connectorIds);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read — Hybrid Search
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hybrid retrieval: dense + sparse with RRF merging.
     *
     * @param query           natural-language query
     * @param metadataFilters key-value pairs to pre-filter by metadata
     * @param topK            maximum results to return after fusion
     */
    public List<Document> hybridSearch(String query,
                                       Map<String, Object> metadataFilters,
                                       int topK) {
        // ── 1. Dense (semantic) retrieval ───────────────────────────────────
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK * 2)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD);

        if (metadataFilters != null && !metadataFilters.isEmpty()) {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            var conditions = metadataFilters.entrySet().stream()
                    .map(e -> b.eq(e.getKey(), e.getValue()))
                    .toList();
            if (conditions.size() == 1) {
                builder.filterExpression(conditions.get(0).build());
            } else {
                var combined = conditions.get(0);
                for (int i = 1; i < conditions.size(); i++) {
                    combined = b.and(combined, conditions.get(i));
                }
                builder.filterExpression(combined.build());
            }
        }

        List<Document> denseResults = vectorStore.similaritySearch(builder.build());

        // ── 2. Sparse retrieval hook (not used in current Mongo + Pinecone stack) ─

        // ── 3. RRF merge ─────────────────────────────────────────────────────
        List<Document> merged = reciprocalRankFusion(denseResults, List.of(), topK);

        log.debug("Hybrid search for '{}': {} results (dense={}, topK={})",
                query.substring(0, Math.min(60, query.length())),
                merged.size(), denseResults.size(), topK);
        return merged;
    }

    public List<Document> hybridSearch(String query, Map<String, Object> metadataFilters) {
        return hybridSearch(query, metadataFilters, DEFAULT_TOP_K);
    }

    public List<Document> hybridSearch(String query) {
        return hybridSearch(query, null, DEFAULT_TOP_K);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reciprocal Rank Fusion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fuses two ranked lists using RRF: score(d) = Σ 1/(k + rank(d)).
     * k=60 is the standard constant from the original RRF paper.
     */
    private List<Document> reciprocalRankFusion(List<Document> dense,
                                                 List<Document> sparse,
                                                 int topK) {
        final int K = 60;
        Map<String, Double> scores = new java.util.HashMap<>();
        Map<String, Document> docMap = new java.util.HashMap<>();

        for (int i = 0; i < dense.size(); i++) {
            Document d = dense.get(i);
            String key = d.getId();
            scores.merge(key, 1.0 / (K + i + 1), Double::sum);
            docMap.put(key, d);
        }
        for (int i = 0; i < sparse.size(); i++) {
            Document d = sparse.get(i);
            String key = d.getId();
            scores.merge(key, 1.0 / (K + i + 1), Double::sum);
            docMap.put(key, d);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .toList();
    }
}
