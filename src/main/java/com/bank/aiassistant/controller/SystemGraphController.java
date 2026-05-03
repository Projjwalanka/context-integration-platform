package com.bank.aiassistant.controller;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.dto.skg.*;
import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.service.skg.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for the System Knowledge Graph (Neo4j-backed).
 *
 * Endpoints:
 *   GET  /api/graph/system            — full system graph (nodes + edges)
 *   GET  /api/graph/node/{id}         — single node with neighbours
 *   GET  /api/graph/search            — keyword search
 *   POST /api/graph/refresh/code      — trigger code ingestion
 *   POST /api/graph/refresh/docs      — trigger docs ingestion
 *   POST /api/graph/refresh/jira      — trigger Jira mapping
 *   GET  /api/graph/refresh/status    — ingestion status for all types
 *   POST /api/graph/impact/{nodeId}   — impact analysis
 *   POST /api/graph/node              — manual node upsert
 *   DELETE /api/graph/node/{id}       — delete node
 *   POST /api/context/build           — context engineering
 *   GET  /api/explain/{queryId}       — explainability panel data
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SystemGraphController {

    private final SystemKnowledgeGraphService skgService;
    private final SkgCodeIngestionService     codeIngestionService;
    private final SkgDocsIngestionService     docsIngestionService;
    private final SkgJiraIngestionService     jiraIngestionService;
    private final SkgContextService           contextService;
    private final ImpactAnalysisService       impactService;
    private final IngestionStatusService      statusService;
    private final ConnectorConfigRepository   connectorConfigRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // Graph Queries
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/graph/system")
    public ResponseEntity<SystemGraphDto> getSystemGraph(
            @RequestParam(required = false) String nodeType,
            @RequestParam(required = false) String layer,
            @RequestParam(defaultValue = "500") int limit,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        SystemGraphDto graph = skgService.getSystemGraph(tenantId, nodeType, layer, Math.min(limit, 500));
        return ResponseEntity.ok(graph);
    }

    @GetMapping("/api/graph/node/{id}")
    public ResponseEntity<Map<String, Object>> getNode(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int hops,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        Optional<SkgNodeDto> node = skgService.getNode(tenantId, id);
        if (node.isEmpty()) return ResponseEntity.notFound().build();

        List<SkgNodeDto> neighbors = skgService.traverse(tenantId, List.of(id), Math.min(hops, 3));
        List<SkgEdgeDto> edges     = skgService.getEdgesForNode(tenantId, id);

        return ResponseEntity.ok(Map.of(
                "node", node.get(),
                "neighbors", neighbors,
                "edges", edges
        ));
    }

    @GetMapping("/api/graph/search")
    public ResponseEntity<List<SkgNodeDto>> search(
            @RequestParam String q,
            @RequestParam(required = false) String nodeType,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        return ResponseEntity.ok(skgService.searchNodes(tenantId, q, nodeType));
    }

    @GetMapping("/api/graph/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        return ResponseEntity.ok(Map.of(
                "nodeCountByType",  skgService.countByType(tenantId),
                "edgeCountByType",  skgService.countEdgesByType(tenantId),
                "totalNodes",       skgService.totalNodes(tenantId),
                "totalEdges",       skgService.totalEdges(tenantId)
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manual Node Management
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/api/graph/node")
    public ResponseEntity<Map<String, String>> upsertNode(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String nodeId   = String.valueOf(body.getOrDefault("id", UUID.randomUUID().toString()));
        String nodeType = String.valueOf(body.getOrDefault("nodeType", "Component"));
        String name     = String.valueOf(body.getOrDefault("name", "Unnamed"));
        Object descObj  = body.get("description");
        String desc     = descObj != null ? String.valueOf(descObj) : null;

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) body.getOrDefault("properties", Map.of());

        skgService.upsertNode(tenantId, nodeId, nodeType, name, desc, props);
        return ResponseEntity.ok(Map.of("id", nodeId, "status", "upserted"));
    }

    @PostMapping("/api/graph/edge")
    public ResponseEntity<Map<String, String>> upsertEdge(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String sourceId = String.valueOf(body.get("sourceId"));
        String targetId = String.valueOf(body.get("targetId"));
        String relType  = String.valueOf(body.getOrDefault("relType", "RELATED"));

        skgService.upsertEdge(tenantId, sourceId, targetId, relType, Map.of());
        return ResponseEntity.ok(Map.of("status", "created"));
    }

    @DeleteMapping("/api/graph/node/{id}")
    public ResponseEntity<Void> deleteNode(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        skgService.deleteNode(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ingestion — Refresh
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/api/graph/refresh/code")
    public ResponseEntity<Map<String, Object>> refreshCode(
            @RequestParam(required = false) String connectorId,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String email    = principal.getUsername();
        String cid = resolveConnector(email, connectorId, "GITHUB", "CODE");
        if (cid == null) return ResponseEntity.badRequest()
                .body(Map.of("error", "No CODE/GITHUB connector configured. Add one in Settings."));

        codeIngestionService.ingestAsync(tenantId, cid);
        return ResponseEntity.accepted()
                .body(Map.of("status", "RUNNING", "connectorId", cid,
                        "message", "Code ingestion started in background"));
    }

    @PostMapping("/api/graph/refresh/docs")
    public ResponseEntity<Map<String, Object>> refreshDocs(
            @RequestParam(required = false) String connectorId,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String email    = principal.getUsername();
        String cid = resolveConnector(email, connectorId, "CONFLUENCE", "DOCS");
        if (cid == null) return ResponseEntity.badRequest()
                .body(Map.of("error", "No CONFLUENCE connector configured. Add one in Settings."));

        docsIngestionService.ingestAsync(tenantId, cid);
        return ResponseEntity.accepted()
                .body(Map.of("status", "RUNNING", "connectorId", cid,
                        "message", "Docs ingestion started in background"));
    }

    @PostMapping("/api/graph/refresh/documents")
    public ResponseEntity<Map<String, Object>> refreshDocuments(
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String email    = principal.getUsername();

        docsIngestionService.refreshUploadedDocuments(tenantId, email);
        return ResponseEntity.accepted()
                .body(Map.of("status", "RUNNING",
                        "message", "Uploaded document refresh started — documents will appear in the knowledge graph shortly"));
    }

    @PostMapping("/api/graph/refresh/jira")
    public ResponseEntity<Map<String, Object>> refreshJira(
            @RequestParam(required = false) String connectorId,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String email    = principal.getUsername();
        String cid = resolveConnector(email, connectorId, "JIRA", "JIRA");
        if (cid == null) return ResponseEntity.badRequest()
                .body(Map.of("error", "No JIRA connector configured. Add one in Settings."));

        jiraIngestionService.ingestAsync(tenantId, cid);
        return ResponseEntity.accepted()
                .body(Map.of("status", "RUNNING", "connectorId", cid,
                        "message", "Jira mapping started in background"));
    }

    @PostMapping("/api/graph/refresh/all")
    public ResponseEntity<Map<String, Object>> refreshAll(
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        String email    = principal.getUsername();
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> triggered = new ArrayList<>();
        List<String> skipped   = new ArrayList<>();

        String codeCid = resolveConnector(email, null, "GITHUB", "CODE");
        if (codeCid != null) { codeIngestionService.ingestAsync(tenantId, codeCid); result.put("code", codeCid); triggered.add("CODE/GITHUB"); }
        else { result.put("code", "skipped"); skipped.add("GITHUB"); }

        String docsCid = resolveConnector(email, null, "CONFLUENCE", "DOCS");
        if (docsCid != null) { docsIngestionService.ingestAsync(tenantId, docsCid); result.put("docs", docsCid); triggered.add("DOCS/CONFLUENCE"); }
        else { result.put("docs", "skipped"); skipped.add("CONFLUENCE"); }

        String jiraCid = resolveConnector(email, null, "JIRA", "JIRA");
        if (jiraCid != null) { jiraIngestionService.ingestAsync(tenantId, jiraCid); result.put("jira", jiraCid); triggered.add("JIRA"); }
        else { result.put("jira", "skipped"); skipped.add("JIRA"); }

        // Always re-ingest manually uploaded documents — no connector required
        docsIngestionService.refreshUploadedDocuments(tenantId, email);
        result.put("uploadedDocuments", "RUNNING");
        triggered.add("UPLOADED_DOCUMENTS");

        // Warn (but don't block) when no external connectors are configured.
        // Uploaded documents are always refreshed regardless.
        boolean noExternalConnectors = skipped.containsAll(List.of("GITHUB", "CONFLUENCE", "JIRA"))
                && !triggered.contains("CODE/GITHUB")
                && !triggered.contains("DOCS/CONFLUENCE")
                && !triggered.contains("JIRA");
        if (noExternalConnectors) {
            log.warn("refreshAll: no external connectors configured for user={}", email);
            result.put("warning", "No GitHub / Confluence / Jira connectors configured. " +
                    "Only uploaded documents are being refreshed. " +
                    "Add connectors under Settings → Connectors for full knowledge graph coverage.");
        }

        log.info("refreshAll: triggered={} skipped={} user={}", triggered, skipped, email);
        result.put("status", "RUNNING");
        result.put("triggered", triggered);
        result.put("skipped", skipped);
        result.put("message", "Refresh started for: " + String.join(", ", triggered)
                + (skipped.isEmpty() ? "" : ". Skipped (no connector): " + String.join(", ", skipped)));
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/api/graph/refresh/status")
    public ResponseEntity<List<RefreshStatusDto>> refreshStatus(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(statusService.getAll(tenantId(principal)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Impact Analysis
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/api/graph/impact/{nodeId}")
    public ResponseEntity<ImpactAnalysisDto> analyzeImpact(
            @PathVariable String nodeId,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        return ResponseEntity.ok(impactService.analyze(tenantId, nodeId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context Engineering + Explainability
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/api/context/build")
    public ResponseEntity<ContextBuildResponse> buildContext(
            @RequestBody ContextBuildRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        String tenantId = tenantId(principal);
        return ResponseEntity.ok(contextService.buildContext(tenantId, request));
    }

    @GetMapping("/api/explain/{queryId}")
    public ResponseEntity<ContextBuildResponse> explain(
            @PathVariable String queryId,
            @AuthenticationPrincipal UserDetails principal) {

        return contextService.getExplanation(queryId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String tenantId(UserDetails principal) {
        return TenantContext.fromEmail(principal.getUsername());
    }

    private String resolveConnector(String ownerEmail, String explicitId,
                                     String connectorType, String fallbackType) {
        if (explicitId != null) return explicitId;
        return connectorConfigRepository
                .findByOwnerEmailAndConnectorTypeIgnoreCaseAndEnabledTrue(ownerEmail, connectorType)
                .stream()
                .findFirst()
                .map(ConnectorConfig::getId)
                .orElse(null);
    }
}
