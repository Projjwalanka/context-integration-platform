package com.bank.aiassistant.service.kg;

import com.bank.aiassistant.model.entity.KgEntity;
import com.bank.aiassistant.model.entity.KgRelationship;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Extracts entities and relationships from raw text using an LLM (OpenAI).
 *
 * Implements hybrid extraction:
 *  - Structured sources (GitHub JSON, Jira) → direct property mapping
 *  - Unstructured text (PDF, docs, emails)  → LLM-based NER + relation extraction
 *
 * Runs @Async so it never blocks the ingestion pipeline caller.
 */
@Slf4j
@Service
public class EntityExtractionService {

    private final ChatClient chatClient;
    private final KnowledgeGraphService kgService;
    private final ObjectMapper objectMapper;

    private static final int MAX_CONTENT_CHARS = 3_000;

    private static final String EXTRACTION_PROMPT = """
            Analyze the following content and extract a knowledge graph.

            Return ONLY a valid JSON object (no markdown, no explanation) with this exact structure:
            {
              "entities": [
                {
                  "name": "string",
                  "type": "one of: PERSON|CODE_COMPONENT|FUNCTION|CLASS_DEF|TICKET|DOCUMENT|CONCEPT|REPOSITORY|API_ENDPOINT|MODULE",
                  "description": "brief description ≤ 120 chars"
                }
              ],
              "relationships": [
                {
                  "sourceName": "entity name from entities list",
                  "targetName": "entity name from entities list",
                  "type": "one of: IMPLEMENTS|DEPENDS_ON|DESCRIBES|RELATED_TO|AUTHORED_BY|BELONGS_TO|CONTAINS|REFERENCES|RESOLVES|TESTED_BY"
                }
              ]
            }

            Rules:
            - Maximum 12 entities, 16 relationships
            - Only include entities/relationships clearly supported by the text
            - Use the exact names as written in the text
            - sourceName and targetName MUST appear in the entities list

            Content:
            %s
            """;

    public EntityExtractionService(@Qualifier("kgChatClient") ChatClient chatClient,
                                   KnowledgeGraphService kgService,
                                   ObjectMapper objectMapper) {
        this.chatClient   = chatClient;
        this.kgService    = kgService;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract entities + relationships from free-form text and persist to KG.
     * Runs asynchronously — caller gets a future with the extraction result.
     */
    @Async
    public CompletableFuture<ExtractionResult> extractAndStore(
            String content,
            String tenantId,
            String connectorId,
            String sourceRef,
            String sourceType) {

        String truncated = content.length() > MAX_CONTENT_CHARS
                ? content.substring(0, MAX_CONTENT_CHARS)
                : content;

        try {
            String prompt   = EXTRACTION_PROMPT.formatted(truncated);
            String response = chatClient.prompt().user(prompt).call().content();
            ExtractionResult result = parseResponse(response, tenantId, connectorId, sourceRef, sourceType);
            persistResult(result);

            log.info("KG extraction: {} entities, {} rels from '{}' (tenant={})",
                    result.entities().size(), result.relationships().size(), sourceRef, tenantId);

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.warn("Entity extraction failed for '{}': {}", sourceRef, e.getMessage());
            return CompletableFuture.completedFuture(ExtractionResult.empty());
        }
    }

    /**
     * Direct structured ingestion for well-known schemas (GitHub, Jira).
     * Skips LLM and maps fields directly to KgEntity.
     */
    @Async
    public CompletableFuture<Void> ingestStructured(KgEntity entity, List<KgRelationship> relationships) {
        try {
            KgEntity saved = kgService.upsertEntity(entity);
            for (KgRelationship rel : relationships) {
                rel.setTenantId(entity.getTenantId());
                kgService.addRelationship(rel);
            }
        } catch (Exception e) {
            log.warn("Structured KG ingest failed for '{}': {}", entity.getName(), e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse + persist
    // ─────────────────────────────────────────────────────────────────────────

    private ExtractionResult parseResponse(String json, String tenantId, String connectorId,
                                            String sourceRef, String sourceType) {
        try {
            // Strip possible markdown code fences
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                int start = cleaned.indexOf('{');
                int end   = cleaned.lastIndexOf('}');
                if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
            }

            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode entitiesNode = root.path("entities");
            JsonNode relsNode     = root.path("relationships");

            // Build entity name → KgEntity map
            Map<String, KgEntity> entityByName = new java.util.LinkedHashMap<>();

            if (entitiesNode.isArray()) {
                for (JsonNode n : entitiesNode) {
                    String name = n.path("name").asText("").trim();
                    if (name.isBlank()) continue;

                    KgEntity e = KgEntity.builder()
                            .tenantId(tenantId)
                            .entityType(sanitizeType(n.path("type").asText("CONCEPT")))
                            .name(name)
                            .description(n.path("description").asText(""))
                            .sourceConnectorId(connectorId)
                            .sourceRef(sourceRef + "#" + name)
                            .sourceType(sourceType)
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();

                    entityByName.put(name, e);
                }
            }

            List<KgRelationship> relationships = new ArrayList<>();
            if (relsNode.isArray()) {
                for (JsonNode n : relsNode) {
                    String srcName = n.path("sourceName").asText("").trim();
                    String tgtName = n.path("targetName").asText("").trim();
                    String type    = n.path("type").asText("RELATED_TO").trim();

                    // Relationship will be wired after persist (we need persisted IDs)
                    KgRelationship rel = KgRelationship.builder()
                            .tenantId(tenantId)
                            .relationshipType(sanitizeRelType(type))
                            .weight(1.0)
                            .properties(Map.of("srcName", srcName, "tgtName", tgtName))
                            .build();
                    relationships.add(rel);
                }
            }

            return new ExtractionResult(new ArrayList<>(entityByName.values()), relationships);

        } catch (Exception e) {
            log.warn("Failed to parse LLM extraction response: {}", e.getMessage());
            return ExtractionResult.empty();
        }
    }

    private void persistResult(ExtractionResult result) {
        // Persist entities and build name → saved-id map
        Map<String, String> nameToId = new java.util.LinkedHashMap<>();
        for (KgEntity entity : result.entities()) {
            KgEntity saved = kgService.upsertEntity(entity);
            // Resolve name from description/sourceRef suffix
            String name = saved.getName();
            nameToId.put(name, saved.getId());
        }

        // Wire relationships using persisted IDs
        for (KgRelationship rel : result.relationships()) {
            if (rel.getProperties() == null) continue;
            String srcName = (String) rel.getProperties().get("srcName");
            String tgtName = (String) rel.getProperties().get("tgtName");
            String srcId   = nameToId.get(srcName);
            String tgtId   = nameToId.get(tgtName);
            if (srcId == null || tgtId == null) continue;

            rel.setSourceEntityId(srcId);
            rel.setTargetEntityId(tgtId);
            rel.setProperties(null);
            kgService.addRelationship(rel);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sanitisers
    // ─────────────────────────────────────────────────────────────────────────

    private static final java.util.Set<String> VALID_ENTITY_TYPES = java.util.Set.of(
            "PERSON","CODE_COMPONENT","FUNCTION","CLASS_DEF","TICKET","DOCUMENT",
            "CONCEPT","REPOSITORY","API_ENDPOINT","MODULE","STORY","SPRINT");

    private static final java.util.Set<String> VALID_REL_TYPES = java.util.Set.of(
            "IMPLEMENTS","DEPENDS_ON","DESCRIBES","RELATED_TO","AUTHORED_BY",
            "BELONGS_TO","CONTAINS","REFERENCES","RESOLVES","TESTED_BY","FOLLOWS");

    private String sanitizeType(String t) {
        return VALID_ENTITY_TYPES.contains(t.toUpperCase()) ? t.toUpperCase() : "CONCEPT";
    }

    private String sanitizeRelType(String t) {
        return VALID_REL_TYPES.contains(t.toUpperCase()) ? t.toUpperCase() : "RELATED_TO";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value type
    // ─────────────────────────────────────────────────────────────────────────

    public record ExtractionResult(List<KgEntity> entities, List<KgRelationship> relationships) {
        static ExtractionResult empty() { return new ExtractionResult(List.of(), List.of()); }
    }
}
