package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.model.dto.skg.SkgEdgeDto;
import com.bank.aiassistant.model.dto.skg.SkgNodeDto;
import com.bank.aiassistant.model.dto.skg.SystemGraphDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.types.MapAccessor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core System Knowledge Graph service backed by Neo4j.
 *
 * All nodes share the :SkgNode label and are differentiated by nodeType property.
 * Relationship types are fixed: CONTAINS, CALLS, DEPENDS_ON, EXPOSED_BY,
 * USES_DB, HAS_FIELD, DESCRIBES, IMPLEMENTS, AFFECTS, MODIFIES, RELATED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemKnowledgeGraphService {

    private final Neo4jClient neo4jClient;

    // ─────────────────────────────────────────────────────────────────────────
    // Node Operations
    // ─────────────────────────────────────────────────────────────────────────

    public void upsertNode(String tenantId, String nodeId, String nodeType,
                           String name, String description, Map<String, Object> extra) {
        Map<String, Object> props = new HashMap<>();
        if (extra != null) props.putAll(extra);
        props.put("nodeType", nodeType);
        props.put("name", name);
        props.put("description", description != null ? description : "");
        props.put("layer", SkgNodeDto.layerFor(nodeType));
        props.put("updatedAt", Instant.now().toString());

        try {
            neo4jClient.query("""
                    MERGE (n:SkgNode {id: $id, tenantId: $tenantId})
                    ON CREATE SET n.createdAt = $now
                    SET n += $props
                    """)
                    .bind(nodeId).to("id")
                    .bind(tenantId).to("tenantId")
                    .bind(Instant.now().toString()).to("now")
                    .bind(props).to("props")
                    .run();
        } catch (Exception e) {
            log.error("Failed to upsert node {} ({}): {}", name, nodeType, e.getMessage());
        }
    }

    public void upsertEdge(String tenantId, String sourceId, String targetId,
                           String relType, Map<String, Object> edgeProps) {
        String query = edgeMergeQuery(relType);
        Map<String, Object> props = edgeProps != null ? new HashMap<>(edgeProps) : new HashMap<>();
        props.put("updatedAt", Instant.now().toString());
        try {
            neo4jClient.query(query)
                    .bind(tenantId).to("tenantId")
                    .bind(sourceId).to("sourceId")
                    .bind(targetId).to("targetId")
                    .bind(props).to("props")
                    .run();
        } catch (Exception e) {
            log.error("Failed to upsert edge {}-[{}]->{}: {}", sourceId, relType, targetId, e.getMessage());
        }
    }

    private String edgeMergeQuery(String relType) {
        String rel = switch (relType.toUpperCase()) {
            case "CONTAINS"    -> "CONTAINS";
            case "CALLS"       -> "CALLS";
            case "DEPENDS_ON"  -> "DEPENDS_ON";
            case "EXPOSED_BY"  -> "EXPOSED_BY";
            case "USES_DB"     -> "USES_DB";
            case "HAS_FIELD"   -> "HAS_FIELD";
            case "DESCRIBES"   -> "DESCRIBES";
            case "IMPLEMENTS"  -> "IMPLEMENTS";
            case "AFFECTS"     -> "AFFECTS";
            case "MODIFIES"    -> "MODIFIES";
            default            -> "RELATED";
        };
        return """
                MATCH (a:SkgNode {id: $sourceId, tenantId: $tenantId})
                MATCH (b:SkgNode {id: $targetId, tenantId: $tenantId})
                MERGE (a)-[r:%s]->(b)
                SET r += $props
                """.formatted(rel);
    }

    public void deleteNode(String tenantId, String nodeId) {
        try {
            neo4jClient.query("""
                    MATCH (n:SkgNode {id: $id, tenantId: $tenantId})
                    DETACH DELETE n
                    """)
                    .bind(nodeId).to("id")
                    .bind(tenantId).to("tenantId")
                    .run();
        } catch (Exception e) {
            log.error("Failed to delete node {}: {}", nodeId, e.getMessage());
        }
    }

    public void clearTenantGraph(String tenantId) {
        try {
            neo4jClient.query("MATCH (n:SkgNode {tenantId: $tenantId}) DETACH DELETE n")
                    .bind(tenantId).to("tenantId")
                    .run();
        } catch (Exception e) {
            log.error("Failed to clear graph for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph Queries
    // ─────────────────────────────────────────────────────────────────────────

    public SystemGraphDto getSystemGraph(String tenantId, String nodeTypeFilter,
                                         String layerFilter, int limit) {
        String nodeWhere = buildNodeWhereClause(nodeTypeFilter, layerFilter);

        // Fetch nodes
        String nodeQuery = """
                MATCH (n:SkgNode {tenantId: $tenantId})
                """ + nodeWhere + """
                RETURN n.id as id, n.nodeType as nodeType, n.layer as layer,
                       n.name as name, n.description as description,
                       n.sourceRef as sourceRef, n.createdAt as createdAt, n.updatedAt as updatedAt
                ORDER BY n.updatedAt DESC
                LIMIT $limit
                """;

        List<SkgNodeDto> nodes = fetchNodes(nodeQuery, tenantId, limit);
        Set<String> nodeIds = nodes.stream().map(SkgNodeDto::id).collect(Collectors.toSet());

        // Fetch edges between returned nodes
        List<SkgEdgeDto> edges = nodeIds.isEmpty() ? List.of() : fetchEdgesAmong(tenantId, nodeIds);

        // Stats
        Map<String, Long> byType  = countByType(tenantId);
        Map<String, Long> byEdge  = countEdgesByType(tenantId);

        return new SystemGraphDto(nodes, edges, byType, byEdge,
                nodes.size(), edges.size(), tenantId, Instant.now().toString());
    }

    public Optional<SkgNodeDto> getNode(String tenantId, String nodeId) {
        return neo4jClient.query("""
                        MATCH (n:SkgNode {id: $id, tenantId: $tenantId})
                        RETURN n.id as id, n.nodeType as nodeType, n.layer as layer,
                               n.name as name, n.description as description,
                               n.sourceRef as sourceRef, n.createdAt as createdAt, n.updatedAt as updatedAt
                        """)
                .bind(nodeId).to("id")
                .bind(tenantId).to("tenantId")
                .fetchAs(SkgNodeDto.class)
                .mappedBy((ts, r) -> mapNode(r))
                .one();
    }

    /** BFS traversal from a set of seed node IDs up to maxHops hops.
     *  NOTE: Neo4j does not allow parameters in variable-length path bounds ([*0..$hops]),
     *  so the hop count is inlined as a literal in the query string. */
    public List<SkgNodeDto> traverse(String tenantId, List<String> seedIds, int maxHops) {
        int hops = Math.min(maxHops, 5);
        // Inline the hop count as a literal — parameters are forbidden in [*min..max] syntax
        String cypher = "MATCH (seed:SkgNode {tenantId: $tenantId}) " +
                        "WHERE seed.id IN $seedIds " +
                        "MATCH (seed)-[*0.." + hops + "]-(neighbor:SkgNode {tenantId: $tenantId}) " +
                        "RETURN DISTINCT neighbor.id as id, neighbor.nodeType as nodeType, " +
                        "neighbor.layer as layer, neighbor.name as name, " +
                        "neighbor.description as description, neighbor.sourceRef as sourceRef, " +
                        "neighbor.createdAt as createdAt, neighbor.updatedAt as updatedAt " +
                        "LIMIT 200";
        try {
            return neo4jClient.query(cypher)
                    .bind(tenantId).to("tenantId")
                    .bind(seedIds).to("seedIds")
                    .fetchAs(SkgNodeDto.class)
                    .mappedBy((ts, r) -> mapNode(r))
                    .all()
                    .stream().toList();
        } catch (Exception e) {
            log.error("Traversal failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Find nodes reachable via dependency/call chains — for impact analysis. */
    public List<Map<String, Object>> findImpactedNodes(String tenantId, String nodeId) {
        try {
            return neo4jClient.query("""
                            MATCH (start:SkgNode {id: $nodeId, tenantId: $tenantId})
                            MATCH path = (start)-[:CALLS|DEPENDS_ON|USES_DB|CONTAINS*1..5]->(impact:SkgNode {tenantId: $tenantId})
                            RETURN DISTINCT impact.id as id, impact.nodeType as nodeType,
                                   impact.name as name, impact.layer as layer,
                                   impact.description as description,
                                   min(length(path)) as distance,
                                   [r in relationships(path) | type(r)] as relChain
                            ORDER BY distance
                            LIMIT 50
                            """)
                    .bind(nodeId).to("nodeId")
                    .bind(tenantId).to("tenantId")
                    .fetch()
                    .all()
                    .stream()
                    .map(r -> (Map<String, Object>) new HashMap<>(r))
                    .toList();
        } catch (Exception e) {
            log.error("Impact analysis failed for node {}: {}", nodeId, e.getMessage());
            return List.of();
        }
    }

    /** Find nodes whose name OR description contains the keyword. */
    public List<SkgNodeDto> searchNodesByDescription(String tenantId, String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        try {
            return neo4jClient.query("""
                            MATCH (n:SkgNode {tenantId: $tenantId})
                            WHERE toLower(n.description) CONTAINS toLower($keyword)
                              AND NOT toLower(n.name) CONTAINS toLower($keyword)
                            RETURN n.id as id, n.nodeType as nodeType, n.layer as layer,
                                   n.name as name, n.description as description,
                                   n.sourceRef as sourceRef, n.createdAt as createdAt, n.updatedAt as updatedAt
                            LIMIT 30
                            """)
                    .bind(tenantId).to("tenantId")
                    .bind(keyword).to("keyword")
                    .fetchAs(SkgNodeDto.class)
                    .mappedBy((ts, r) -> mapNode(r))
                    .all()
                    .stream().toList();
        } catch (Exception e) {
            log.warn("Description search failed for keyword {}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /** Find nodes whose names match the keyword. */
    public List<SkgNodeDto> searchNodes(String tenantId, String keyword, String nodeType) {
        String typeClause = nodeType != null ? "AND n.nodeType = $nodeType " : "";
        try {
            return neo4jClient.query("""
                            MATCH (n:SkgNode {tenantId: $tenantId})
                            WHERE toLower(n.name) CONTAINS toLower($keyword)
                            """ + typeClause + """
                            RETURN n.id as id, n.nodeType as nodeType, n.layer as layer,
                                   n.name as name, n.description as description,
                                   n.sourceRef as sourceRef, n.createdAt as createdAt, n.updatedAt as updatedAt
                            LIMIT 50
                            """)
                    .bind(tenantId).to("tenantId")
                    .bind(keyword).to("keyword")
                    .bind(nodeType != null ? nodeType : "").to("nodeType")
                    .fetchAs(SkgNodeDto.class)
                    .mappedBy((ts, r) -> mapNode(r))
                    .all()
                    .stream().toList();
        } catch (Exception e) {
            log.error("Search failed for keyword {}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    public List<SkgEdgeDto> getEdgesForNode(String tenantId, String nodeId) {
        try {
            return neo4jClient.query("""
                            MATCH (a:SkgNode {id: $nodeId, tenantId: $tenantId})-[r]->(b:SkgNode {tenantId: $tenantId})
                            RETURN a.id as sourceId, b.id as targetId, type(r) as relType,
                                   toString(id(r)) as edgeId
                            UNION
                            MATCH (a:SkgNode {tenantId: $tenantId})-[r]->(b:SkgNode {id: $nodeId, tenantId: $tenantId})
                            RETURN a.id as sourceId, b.id as targetId, type(r) as relType,
                                   toString(id(r)) as edgeId
                            """)
                    .bind(nodeId).to("nodeId")
                    .bind(tenantId).to("tenantId")
                    .fetchAs(SkgEdgeDto.class)
                    .mappedBy((ts, r) -> new SkgEdgeDto(
                            r.get("edgeId").asString(""),
                            r.get("sourceId").asString(""),
                            r.get("targetId").asString(""),
                            r.get("relType").asString("RELATED"),
                            Map.of()))
                    .all()
                    .stream().toList();
        } catch (Exception e) {
            log.error("Edge query failed for node {}: {}", nodeId, e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Long> countByType(String tenantId) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            neo4jClient.query("""
                            MATCH (n:SkgNode {tenantId: $tenantId})
                            RETURN n.nodeType as nodeType, count(n) as cnt
                            ORDER BY cnt DESC
                            """)
                    .bind(tenantId).to("tenantId")
                    .fetch().all()
                    .forEach(r -> result.put(
                            String.valueOf(r.get("nodeType")),
                            ((Number) r.get("cnt")).longValue()));
        } catch (Exception e) {
            log.warn("Count-by-type failed: {}", e.getMessage());
        }
        return result;
    }

    public Map<String, Long> countEdgesByType(String tenantId) {
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            neo4jClient.query("""
                            MATCH (a:SkgNode {tenantId: $tenantId})-[r]->(b:SkgNode {tenantId: $tenantId})
                            RETURN type(r) as relType, count(r) as cnt
                            ORDER BY cnt DESC
                            """)
                    .bind(tenantId).to("tenantId")
                    .fetch().all()
                    .forEach(r -> result.put(
                            String.valueOf(r.get("relType")),
                            ((Number) r.get("cnt")).longValue()));
        } catch (Exception e) {
            log.warn("Edge count-by-type failed: {}", e.getMessage());
        }
        return result;
    }

    public long totalNodes(String tenantId) {
        try {
            return neo4jClient.query("MATCH (n:SkgNode {tenantId: $tenantId}) RETURN count(n) as cnt")
                    .bind(tenantId).to("tenantId")
                    .fetchAs(Long.class)
                    .mappedBy((ts, r) -> r.get("cnt").asLong())
                    .one().orElse(0L);
        } catch (Exception e) { return 0L; }
    }

    public long totalEdges(String tenantId) {
        try {
            return neo4jClient.query("""
                            MATCH (a:SkgNode {tenantId: $tenantId})-[r]->(b:SkgNode {tenantId: $tenantId})
                            RETURN count(r) as cnt
                            """)
                    .bind(tenantId).to("tenantId")
                    .fetchAs(Long.class)
                    .mappedBy((ts, r) -> r.get("cnt").asLong())
                    .one().orElse(0L);
        } catch (Exception e) { return 0L; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildNodeWhereClause(String nodeType, String layer) {
        List<String> clauses = new ArrayList<>();
        if (nodeType != null && !nodeType.isBlank()) clauses.add("n.nodeType = '" + nodeType + "'");
        if (layer    != null && !layer.isBlank())    clauses.add("n.layer = '" + layer + "'");
        return clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses);
    }

    private List<SkgNodeDto> fetchNodes(String query, String tenantId, int limit) {
        try {
            return neo4jClient.query(query)
                    .bind(tenantId).to("tenantId")
                    .bind(limit).to("limit")
                    .fetchAs(SkgNodeDto.class)
                    .mappedBy((ts, r) -> mapNode(r))
                    .all()
                    .stream().toList();
        } catch (Exception e) {
            log.error("Node fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SkgEdgeDto> fetchEdgesAmong(String tenantId, Set<String> nodeIds) {
        try {
            return neo4jClient.query("""
                            MATCH (a:SkgNode {tenantId: $tenantId})-[r]->(b:SkgNode {tenantId: $tenantId})
                            WHERE a.id IN $ids AND b.id IN $ids
                            RETURN a.id as sourceId, b.id as targetId, type(r) as relType,
                                   toString(id(r)) as edgeId
                            """)
                    .bind(tenantId).to("tenantId")
                    .bind(new ArrayList<>(nodeIds)).to("ids")
                    .fetchAs(SkgEdgeDto.class)
                    .mappedBy((ts, r) -> new SkgEdgeDto(
                            r.get("edgeId").asString(""),
                            r.get("sourceId").asString(""),
                            r.get("targetId").asString(""),
                            r.get("relType").asString("RELATED"),
                            Map.of()))
                    .all()
                    .stream().toList();
        } catch (Exception e) {
            log.error("Edge fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private SkgNodeDto mapNode(MapAccessor r) {
        return new SkgNodeDto(
                safeStr(r, "id"),
                safeStr(r, "nodeType"),
                safeStr(r, "layer"),
                safeStr(r, "name"),
                safeStr(r, "description"),
                null,
                Map.of(),
                safeStr(r, "sourceRef"),
                safeStr(r, "createdAt"),
                safeStr(r, "updatedAt")
        );
    }

    private String safeStr(MapAccessor r, String key) {
        try {
            var v = r.get(key);
            return v.isNull() ? null : v.asString();
        } catch (Exception e) { return null; }
    }
}
