package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.service.connector.ConnectorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Maps Jira work items (Story, Bug, Task) to system components in the SKG.
 *
 * Jira is SECONDARY enrichment only:
 *   Story → IMPLEMENTS → Component
 *   Bug   → AFFECTS    → Service
 *   Task  → MODIFIES   → Module
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkgJiraIngestionService {

    private final SystemKnowledgeGraphService skg;
    private final ConnectorRegistry connectorRegistry;
    private final IngestionStatusService statusService;

    @Async
    public void ingestAsync(String tenantId, String connectorId) {
        ingest(tenantId, connectorId, null);
    }

    /**
     * Async variant that accepts pre-fetched entries so the caller (e.g.
     * {@link com.bank.aiassistant.service.ingestion.ConnectorIngestionScheduler}) can
     * share a single {@code fetchAll()} call between vector ingestion and SKG node creation,
     * avoiding a second round-trip to the Jira API.
     */
    @Async
    public void ingestAsync(String tenantId, String connectorId,
                            List<Map.Entry<String, Map<String, Object>>> preloadedEntries) {
        ingest(tenantId, connectorId, preloadedEntries);
    }

    public IngestionStatusService.IngestionSummary ingest(String tenantId, String connectorId) {
        return ingest(tenantId, connectorId, null);
    }

    /**
     * Core ingestion logic. If {@code preloadedEntries} is non-null it is used directly;
     * otherwise the entries are fetched from the connector via {@link ConnectorRegistry#fetchAll}.
     */
    public IngestionStatusService.IngestionSummary ingest(String tenantId, String connectorId,
            List<Map.Entry<String, Map<String, Object>>> preloadedEntries) {
        statusService.markRunning(tenantId, "JIRA", connectorId);
        int created = 0, edges = 0;
        try {
            List<Map.Entry<String, Map<String, Object>>> entries = preloadedEntries != null
                    ? preloadedEntries
                    : connectorRegistry.fetchAll(connectorId);

            for (Map.Entry<String, Map<String, Object>> entry : entries) {
                String summary = entry.getKey();                   // Issue summary text
                Map<String, Object> meta = entry.getValue();

                String key      = String.valueOf(meta.getOrDefault("key", meta.getOrDefault("id", "")));
                String type     = normalizeType(String.valueOf(meta.getOrDefault("issueType",
                        meta.getOrDefault("type", "Task"))));
                String status   = String.valueOf(meta.getOrDefault("status", "Unknown"));
                String priority = String.valueOf(meta.getOrDefault("priority", "Medium"));
                String assignee = String.valueOf(meta.getOrDefault("assignee", ""));
                String url      = String.valueOf(meta.getOrDefault("url",
                        meta.getOrDefault("webUrl", "")));
                String component = String.valueOf(meta.getOrDefault("component", ""));
                List<String> labels = extractLabels(meta);

                String nodeId = tenantId + ":Work:" + (key.isBlank() ? UUID.randomUUID() : key);
                skg.upsertNode(tenantId, nodeId, type,
                        (key.isBlank() ? "" : key + ": ") + summary,
                        summary,
                        Map.of("key", key, "status", status, "priority", priority,
                                "assignee", assignee, "url", url,
                                "labels", String.join(",", labels)));
                created++;

                // Link to system nodes based on component field + labels + summary
                String searchText = summary + " " + component + " " + String.join(" ", labels);
                edges += linkWorkToSystem(tenantId, nodeId, type, searchText);
            }

            statusService.markCompleted(tenantId, "JIRA", connectorId, created, edges,
                    "Mapped " + entries.size() + " Jira issues to system components");
            return new IngestionStatusService.IngestionSummary(created, edges, List.of());

        } catch (Exception e) {
            log.error("Jira ingestion failed for {}: {}", connectorId, e.getMessage(), e);
            statusService.markFailed(tenantId, "JIRA", connectorId, e.getMessage());
            return new IngestionStatusService.IngestionSummary(created, edges, List.of(e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int linkWorkToSystem(String tenantId, String workNodeId, String workType, String text) {
        int edges = 0;
        String relType = switch (workType) {
            case "Story" -> "IMPLEMENTS";
            case "Bug"   -> "AFFECTS";
            default      -> "MODIFIES";
        };
        List<String> targetTypes = switch (workType) {
            case "Story" -> List.of("Component", "Module", "Service");
            case "Bug"   -> List.of("Service", "Api", "Component");
            default      -> List.of("Module", "Component");
        };

        Set<String> candidates = new LinkedHashSet<>();
        for (String word : text.split("\\s+")) {
            if (word.length() > 5 && Character.isUpperCase(word.charAt(0))) {
                candidates.add(word.replaceAll("[^a-zA-Z]", ""));
            }
        }

        for (String candidate : candidates) {
            for (String targetType : targetTypes) {
                skg.searchNodes(tenantId, candidate, targetType).stream()
                        .limit(2)
                        .forEach(n -> skg.upsertEdge(tenantId, workNodeId, n.id(), relType, Map.of()));
                edges++;
            }
        }
        return edges;
    }

    private String normalizeType(String raw) {
        return switch (raw.toLowerCase().replace(" ", "")) {
            case "story", "userstory", "epic" -> "Story";
            case "bug", "defect"              -> "Bug";
            default                           -> "Task";
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> extractLabels(Map<String, Object> meta) {
        Object labels = meta.get("labels");
        if (labels instanceof List<?> l) return l.stream().map(Object::toString).toList();
        String raw = String.valueOf(meta.getOrDefault("labels", ""));
        if (raw.isBlank() || "null".equals(raw)) return List.of();
        return Arrays.asList(raw.split("[,;\\s]+"));
    }
}
