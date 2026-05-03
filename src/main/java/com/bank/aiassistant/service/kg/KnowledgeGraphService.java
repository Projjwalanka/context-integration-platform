package com.bank.aiassistant.service.kg;

import com.bank.aiassistant.model.entity.KgEntity;
import com.bank.aiassistant.model.entity.KgRelationship;
import com.bank.aiassistant.repository.KgEntityRepository;
import com.bank.aiassistant.repository.KgRelationshipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core Knowledge Graph service.
 *
 * Provides:
 * - CRUD for entities and relationships
 * - BFS graph traversal (up to 2 hops) via MongoDB relationship queries
 * - Tenant-isolated read/write operations
 * - Incremental upsert (CDC-friendly — updates existing nodes without full rebuild)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final KgEntityRepository entityRepo;
    private final KgRelationshipRepository relationshipRepo;

    // ─────────────────────────────────────────────────────────────────────────
    // Entity CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upsert an entity. If an entity with the same (tenantId, sourceRef, sourceType)
     * already exists it is updated in-place (incremental CDC behaviour).
     */
    public KgEntity upsertEntity(KgEntity entity) {
        if (entity.getSourceRef() != null && entity.getSourceType() != null) {
            Optional<KgEntity> existing = entityRepo.findByTenantIdAndSourceRefAndSourceType(
                    entity.getTenantId(), entity.getSourceRef(), entity.getSourceType());

            if (existing.isPresent()) {
                KgEntity e = existing.get();
                e.setName(entity.getName());
                e.setDescription(entity.getDescription());
                e.setProperties(entity.getProperties());
                e.setDeprecated(false);
                e.setVersion(e.getVersion() + 1);
                e.setUpdatedAt(Instant.now());
                return entityRepo.save(e);
            }
        }
        return entityRepo.save(entity);
    }

    /**
     * Add a directed relationship. Skips if an identical
     * (tenant, source, target, type) triple already exists.
     */
    public KgRelationship addRelationship(KgRelationship rel) {
        List<KgRelationship> existing = relationshipRepo
                .findByTenantIdAndSourceEntityIdAndDeprecatedFalse(
                        rel.getTenantId(), rel.getSourceEntityId());

        boolean duplicate = existing.stream().anyMatch(r ->
                r.getTargetEntityId().equals(rel.getTargetEntityId()) &&
                r.getRelationshipType().equals(rel.getRelationshipType()));

        if (duplicate) return rel;
        return relationshipRepo.save(rel);
    }

    /** Soft-deprecate all entities for a connector (used before re-ingestion). */
    public void deprecateConnectorEntities(String tenantId, String connectorId) {
        List<KgEntity> entities = entityRepo
                .findByTenantIdAndSourceConnectorIdAndDeprecatedFalse(tenantId, connectorId);
        Instant now = Instant.now();
        entities.forEach(e -> {
            e.setDeprecated(true);
            e.setUpdatedAt(now);
        });
        entityRepo.saveAll(entities);
        log.info("Deprecated {} entities for connector={} tenant={}", entities.size(), connectorId, tenantId);
    }

    /** Hard-delete an entity and all its edges. */
    public void deleteEntity(String tenantId, String entityId) {
        relationshipRepo.deleteByTenantIdAndSourceEntityId(tenantId, entityId);
        relationshipRepo.deleteByTenantIdAndTargetEntityId(tenantId, entityId);
        entityRepo.deleteById(entityId);
    }

    /** Hard-delete ALL entities and relationships for a tenant from MongoDB. */
    public void clearAllForTenant(String tenantId) {
        relationshipRepo.deleteByTenantId(tenantId);
        entityRepo.deleteByTenantId(tenantId);
        log.info("Cleared all KG entities and relationships for tenant={}", tenantId);
    }

    /** Retrieve all entities of a given type — used for catalog/listing queries. */
    public GraphSubgraph findByEntityType(String tenantId, String entityType) {
        List<KgEntity> entities = entityRepo.findByTenantIdAndEntityTypeAndDeprecatedFalse(
                tenantId, entityType.toUpperCase());
        if (entities.isEmpty()) return GraphSubgraph.empty();
        List<String> ids = entities.stream().map(KgEntity::getId).limit(50).toList();
        List<KgRelationship> rels = relationshipRepo
                .findByTenantIdAndSourceEntityIdInAndDeprecatedFalse(tenantId, ids);
        return new GraphSubgraph(entities, rels);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph Traversal — BFS up to 2 hops
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * BFS traversal starting from a set of seed entity IDs.
     * Returns all entities reachable within {@code maxHops} hops (inclusive of seeds).
     */
    public GraphSubgraph traverse(String tenantId, List<String> seedEntityIds, int maxHops) {
        Set<String> visited = new LinkedHashSet<>(seedEntityIds);
        Set<String> frontier = new LinkedHashSet<>(seedEntityIds);
        List<KgRelationship> allEdges = new ArrayList<>();

        for (int hop = 0; hop < maxHops && !frontier.isEmpty(); hop++) {
            List<String> frontierList = new ArrayList<>(frontier);

            List<KgRelationship> outgoing = relationshipRepo
                    .findByTenantIdAndSourceEntityIdInAndDeprecatedFalse(tenantId, frontierList);
            List<KgRelationship> incoming = relationshipRepo
                    .findByTenantIdAndTargetEntityIdInAndDeprecatedFalse(tenantId, frontierList);

            allEdges.addAll(outgoing);
            allEdges.addAll(incoming);

            Set<String> nextFrontier = new LinkedHashSet<>();
            Stream.concat(outgoing.stream(), incoming.stream()).forEach(r -> {
                String neighbor = visited.contains(r.getSourceEntityId())
                        ? r.getTargetEntityId() : r.getSourceEntityId();
                if (!visited.contains(neighbor)) {
                    nextFrontier.add(neighbor);
                    visited.add(neighbor);
                }
            });
            frontier = nextFrontier;
        }

        List<KgEntity> entities = visited.isEmpty()
                ? List.of()
                : entityRepo.findByIdIn(new ArrayList<>(visited));

        return new GraphSubgraph(entities, allEdges);
    }

    /**
     * Keyword search for entities whose name contains the given term,
     * then traverse 1 hop to enrich context.
     */
    public GraphSubgraph searchAndTraverse(String tenantId, String keyword, int maxSeeds) {
        List<KgEntity> seeds = entityRepo
                .findByTenantIdAndNameContainingIgnoreCaseAndDeprecatedFalse(tenantId, keyword);

        if (seeds.isEmpty()) return GraphSubgraph.empty();

        List<String> seedIds = seeds.stream()
                .limit(maxSeeds)
                .map(KgEntity::getId)
                .toList();

        return traverse(tenantId, seedIds, 1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    public KgStats stats(String tenantId) {
        long entities    = entityRepo.countByTenantIdAndDeprecatedFalse(tenantId);
        long relations   = relationshipRepo.countByTenantIdAndDeprecatedFalse(tenantId);
        Map<String, Long> byType = new LinkedHashMap<>();
        for (String t : List.of("CODE_COMPONENT","TICKET","DOCUMENT","PERSON","CONCEPT","REPOSITORY","FUNCTION","API_ENDPOINT")) {
            long c = entityRepo.countByTenantIdAndEntityTypeAndDeprecatedFalse(tenantId, t);
            if (c > 0) byType.put(t, c);
        }
        return new KgStats(entities, relations, byType);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value types
    // ─────────────────────────────────────────────────────────────────────────

    public record GraphSubgraph(List<KgEntity> entities, List<KgRelationship> relationships) {
        static GraphSubgraph empty() { return new GraphSubgraph(List.of(), List.of()); }
    }

    public record KgStats(long totalEntities, long totalRelationships, Map<String, Long> entitiesByType) {}
}
