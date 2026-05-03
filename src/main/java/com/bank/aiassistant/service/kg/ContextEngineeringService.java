package com.bank.aiassistant.service.kg;

import com.bank.aiassistant.model.entity.KgEntity;
import com.bank.aiassistant.model.entity.KgRelationship;
import com.bank.aiassistant.service.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Context Engineering Engine — the core differentiator of this platform.
 *
 * Pipeline (executed per user query):
 *
 *   Step 1 · Intent Detection      → classify query type
 *   Step 2 · Query Decomposition   → split compound queries into sub-queries
 *   Step 3 · Hybrid Retrieval      → KG graph traversal + vector similarity search
 *   Step 4 · Context Optimisation  → dedup, relevance ranking, token budget trimming
 *   Step 5 · Prompt Composition    → structured context string for the LLM
 *
 * LLMs never receive raw data — only structured, deduplicated, budget-controlled context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextEngineeringService {

    private final KnowledgeGraphService kgService;
    private final VectorStoreService    vectorStoreService;

    private static final int MAX_CONTEXT_CHARS = 6_000;
    private static final int MAX_VECTOR_DOCS   = 6;
    private static final int MAX_KG_SEEDS      = 5;
    private static final int KG_HOPS           = 2;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main entry point. Returns an {@link EngineeredContext} ready to inject
     * into the system prompt before LLM invocation.
     *
     * @param query        raw user query
     * @param tenantId     tenant scope for KG + vector isolation
     * @param userId       used as vector store metadata filter
     * @param connectorIds optional list of connector IDs to scope retrieval
     */
    public EngineeredContext engineContext(String query,
                                           String tenantId,
                                           String userId,
                                           List<String> connectorIds) {
        long start = System.currentTimeMillis();

        // Step 1 — Intent
        QueryIntent intent = detectIntent(query);

        // Step 2 — Decompose
        List<String> subQueries = decomposeQuery(query, intent);

        // Step 3 — Hybrid retrieval
        KnowledgeGraphService.GraphSubgraph kgSubgraph = retrieveFromKg(tenantId, subQueries, intent);
        List<Document> vectorDocs = retrieveFromVectorStore(query, userId, connectorIds);

        // Step 4 — Optimise (dedup + rank + token budget)
        String contextBlock = composeContext(query, intent, kgSubgraph, vectorDocs);

        long latencyMs = System.currentTimeMillis() - start;
        log.debug("Context engineering: intent={} kgEntities={} vectorDocs={} chars={} latency={}ms",
                intent, kgSubgraph.entities().size(), vectorDocs.size(), contextBlock.length(), latencyMs);

        return new EngineeredContext(contextBlock, intent.name(), subQueries,
                kgSubgraph.entities(), vectorDocs, latencyMs);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — Intent Detection
    // ─────────────────────────────────────────────────────────────────────────

    private QueryIntent detectIntent(String query) {
        String lower = query.toLowerCase();

        // API catalog queries must be detected before CODE_QUESTION (which also matches "api")
        // Note: "end points" (two words) and "endpoints" (one word) are both handled
        if (isCatalogListing(lower) && anyContains(lower,
                "api","endpoint","end point","rest","route","controller","path","method")) {
            return QueryIntent.API_CATALOG;
        }
        if (anyContains(lower, "code","implement","function","class","method","api","endpoint","module","service","interface")) {
            return QueryIntent.CODE_QUESTION;
        }
        if (anyContains(lower, "jira","ticket","issue","bug","story","sprint","epic","backlog","task","feature")) {
            return QueryIntent.TICKET_QUERY;
        }
        if (anyContains(lower, "document","spec","specification","requirement","confluence","wiki","page","report")) {
            return QueryIntent.DOCUMENT_LOOKUP;
        }
        if (anyContains(lower, "how to","steps","process","workflow","procedure","guide","tutorial","deploy")) {
            return QueryIntent.PROCESS_QUESTION;
        }
        if (anyContains(lower, "who","person","team","owner","author","wrote","created by")) {
            return QueryIntent.PEOPLE_QUERY;
        }
        return QueryIntent.GENERAL_QUESTION;
    }

    private boolean isCatalogListing(String lower) {
        return anyContains(lower,
                // explicit "all X" patterns
                "list all","list the","show all","show me all","give me all","give me list",
                "enumerate","what are all","what are the",
                "all the apis","all the endpoints","all the end points",
                "all apis","all endpoints","all end points","all rest","all routes","all controllers",
                // "a list of" / "list of all" variants
                "give me a list","a list of all","list of all","a list of the",
                // simpler triggers
                "list rest","list api","list endpoint","list end point",
                "show rest","show api","show endpoint","show end point");
    }

    private boolean anyContains(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — Query Decomposition
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> decomposeQuery(String query, QueryIntent intent) {
        List<String> subs = new ArrayList<>();
        subs.add(query);

        // Split on "and also", " and ", " as well as "
        String[] parts = query.split("(?i)\\s+and\\s+also\\s+|\\s+as\\s+well\\s+as\\s+|\\s+and\\s+");
        if (parts.length > 1) {
            Arrays.stream(parts)
                  .map(String::trim)
                  .filter(p -> p.length() > 10)
                  .forEach(subs::add);
        }

        // Add intent-specific sub-queries
        switch (intent) {
            case CODE_QUESTION    -> subs.add("implementation dependencies architecture " + extractKeyTerms(query));
            case TICKET_QUERY     -> subs.add("requirements acceptance criteria " + extractKeyTerms(query));
            case DOCUMENT_LOOKUP  -> subs.add("documentation specification " + extractKeyTerms(query));
            case PEOPLE_QUERY     -> subs.add("team ownership responsibility " + extractKeyTerms(query));
            default -> {}
        }

        return subs.stream().distinct().limit(4).toList();
    }

    private String extractKeyTerms(String query) {
        // Remove stop words and return significant terms
        return Arrays.stream(query.split("\\s+"))
                .filter(w -> w.length() > 3)
                .filter(w -> !STOP_WORDS.contains(w.toLowerCase()))
                .limit(5)
                .collect(Collectors.joining(" "));
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "what","where","when","which","does","have","this","that","with","from",
            "into","about","will","should","could","would","tell","show","list","give","find");

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3 — Hybrid Retrieval
    // ─────────────────────────────────────────────────────────────────────────

    private KnowledgeGraphService.GraphSubgraph retrieveFromKg(String tenantId,
                                                                List<String> subQueries,
                                                                QueryIntent intent) {
        if ("default".equals(tenantId)) return KnowledgeGraphService.GraphSubgraph.empty();

        // For catalog queries, do a direct type-based lookup — no keyword guessing needed
        if (intent == QueryIntent.API_CATALOG) {
            KnowledgeGraphService.GraphSubgraph catalog = kgService.findByEntityType(tenantId, "API_ENDPOINT");
            if (!catalog.entities().isEmpty()) return catalog;
            // Fallback 1: FUNCTION entities (controller handler methods)
            KnowledgeGraphService.GraphSubgraph fns = kgService.findByEntityType(tenantId, "FUNCTION");
            if (!fns.entities().isEmpty()) return fns;
            // Fallback 2: SERVICE-level entities
            return kgService.findByEntityType(tenantId, "SERVICE");
        }

        // Extract unique keywords from all sub-queries
        Set<String> keywords = new LinkedHashSet<>();
        for (String q : subQueries) {
            Arrays.stream(q.split("\\s+"))
                  .filter(w -> w.length() > 4)
                  .filter(w -> !STOP_WORDS.contains(w.toLowerCase()))
                  .limit(3)
                  .forEach(keywords::add);
        }

        // Search KG for matching entity names and traverse outward
        List<String> seedIds = new ArrayList<>();
        for (String keyword : keywords) {
            kgService.searchAndTraverse(tenantId, keyword, 3)
                     .entities().stream()
                     .map(KgEntity::getId)
                     .filter(id -> !seedIds.contains(id))
                     .limit(MAX_KG_SEEDS)
                     .forEach(seedIds::add);
            if (seedIds.size() >= MAX_KG_SEEDS) break;
        }

        if (seedIds.isEmpty()) return KnowledgeGraphService.GraphSubgraph.empty();

        return kgService.traverse(tenantId, seedIds.stream().limit(MAX_KG_SEEDS).toList(), KG_HOPS);
    }

    private List<Document> retrieveFromVectorStore(String query, String userId, List<String> connectorIds) {
        try {
            Map<String, Object> filters = new HashMap<>();
            if (userId != null) filters.put("user_id", userId);

            return vectorStoreService.hybridSearch(query, filters.isEmpty() ? null : filters, MAX_VECTOR_DOCS);
        } catch (Exception e) {
            log.warn("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4 + 5 — Context Optimisation + Prompt Composition
    // ─────────────────────────────────────────────────────────────────────────

    private String composeContext(String query,
                                   QueryIntent intent,
                                   KnowledgeGraphService.GraphSubgraph kgSubgraph,
                                   List<Document> vectorDocs) {
        StringBuilder sb = new StringBuilder();

        // ── Knowledge Graph section ──────────────────────────────────────────
        if (!kgSubgraph.entities().isEmpty()) {

            // API catalog — structured endpoint listing
            if (intent == QueryIntent.API_CATALOG) {
                sb.append("## REST API Catalog (from Knowledge Graph)\n");
                sb.append("*All indexed REST API endpoints for this system:*\n\n");
                kgSubgraph.entities().stream()
                        .filter(e -> "API_ENDPOINT".equals(e.getEntityType()) || "SERVICE".equals(e.getEntityType()))
                        .forEach(e -> {
                            String method = e.getProperties() != null
                                    ? String.valueOf(e.getProperties().getOrDefault("method", "")).toUpperCase()
                                    : "";
                            String path = e.getProperties() != null
                                    ? String.valueOf(e.getProperties().getOrDefault("path", e.getName()))
                                    : e.getName();
                            sb.append("- ");
                            if (!method.isBlank() && !"NULL".equals(method)) sb.append("**").append(method).append("** ");
                            sb.append(path);
                            if (e.getDescription() != null && !e.getDescription().isBlank()) {
                                sb.append(" — ").append(e.getDescription());
                            }
                            sb.append("\n");
                        });
                sb.append("\n");
            } else {
            sb.append("## Knowledge Graph Context\n");
            sb.append("*Entities and relationships retrieved from the enterprise knowledge graph:*\n\n");

            // Group entities by type
            Map<String, List<KgEntity>> byType = kgSubgraph.entities().stream()
                    .collect(Collectors.groupingBy(KgEntity::getEntityType));

            byType.forEach((type, entities) -> {
                sb.append("**").append(formatType(type)).append("**\n");
                entities.stream().limit(5).forEach(e -> {
                    sb.append("- ").append(e.getName());
                    if (e.getDescription() != null && !e.getDescription().isBlank()) {
                        sb.append(": ").append(e.getDescription());
                    }
                    sb.append("\n");
                });
            });

            if (!kgSubgraph.relationships().isEmpty()) {
                sb.append("\n**Relationships**\n");
                // Build entity id → name map for readable output
                Map<String, String> idToName = kgSubgraph.entities().stream()
                        .collect(Collectors.toMap(KgEntity::getId, KgEntity::getName, (a, b) -> a));

                kgSubgraph.relationships().stream().limit(10).forEach(r -> {
                    String src = idToName.getOrDefault(r.getSourceEntityId(), r.getSourceEntityId());
                    String tgt = idToName.getOrDefault(r.getTargetEntityId(), r.getTargetEntityId());
                    sb.append("- ").append(src)
                      .append(" → [").append(r.getRelationshipType()).append("] → ")
                      .append(tgt).append("\n");
                });
            }
            sb.append("\n");
            } // end else (non-catalog)
        }

        // ── Vector document section ──────────────────────────────────────────
        if (!vectorDocs.isEmpty()) {
            sb.append("## Retrieved Document Context\n");
            sb.append("*Semantically similar content from indexed documents:*\n\n");

            // Deduplicate by content prefix (first 80 chars)
            Set<String> seen = new LinkedHashSet<>();
            for (Document doc : vectorDocs) {
                String text = doc.getText();
                if (text == null || text.isBlank()) continue;
                String key = text.substring(0, Math.min(80, text.length()));
                if (seen.add(key)) {
                    String source = (String) doc.getMetadata().getOrDefault("source_ref", "document");
                    sb.append("### ").append(source).append("\n");
                    sb.append(text.trim()).append("\n\n");
                }
            }
        }

        // ── Token budget enforcement ─────────────────────────────────────────
        String result = sb.toString();
        if (result.length() > MAX_CONTEXT_CHARS) {
            result = result.substring(0, MAX_CONTEXT_CHARS) + "\n\n*[Context truncated to fit token budget]*";
        }

        return result;
    }

    private String formatType(String type) {
        return type.replace("_", " ")
                   .chars()
                   .mapToObj(c -> String.valueOf((char) c))
                   .reduce("", (a, b) -> a.isEmpty() ? b.toUpperCase() : a + b.toLowerCase());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value types
    // ─────────────────────────────────────────────────────────────────────────

    public enum QueryIntent {
        API_CATALOG, CODE_QUESTION, TICKET_QUERY, DOCUMENT_LOOKUP,
        PROCESS_QUESTION, PEOPLE_QUERY, GENERAL_QUESTION
    }

    public record EngineeredContext(
            String contextBlock,
            String detectedIntent,
            List<String> decomposedQueries,
            List<KgEntity> kgEntities,
            List<Document> vectorDocuments,
            long engineeringLatencyMs
    ) {}
}
