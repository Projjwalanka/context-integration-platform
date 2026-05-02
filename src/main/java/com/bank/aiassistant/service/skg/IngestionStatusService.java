package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.model.dto.skg.RefreshStatusDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tracker for SKG ingestion job status.
 * Keyed by tenantId + ":" + type (CODE, DOCS, JIRA).
 */
@Service
public class IngestionStatusService {

    private final Map<String, StatusEntry> statusMap = new ConcurrentHashMap<>();

    public record IngestionSummary(int nodesCreated, int edgesCreated, List<String> errors) {}

    private record StatusEntry(String type, String status, String lastRunAt,
                               int nodesCreated, int edgesCreated, int nodesUpdated,
                               String message, String connectorId) {}

    public void markRunning(String tenantId, String type, String connectorId) {
        statusMap.put(key(tenantId, type),
                new StatusEntry(type, "RUNNING", Instant.now().toString(), 0, 0, 0, "Ingestion in progress…", connectorId));
    }

    public void markCompleted(String tenantId, String type, String connectorId,
                              int nodes, int edges, String message) {
        statusMap.put(key(tenantId, type),
                new StatusEntry(type, "COMPLETED", Instant.now().toString(), nodes, edges, 0, message, connectorId));
    }

    public void markFailed(String tenantId, String type, String connectorId, String error) {
        statusMap.put(key(tenantId, type),
                new StatusEntry(type, "FAILED", Instant.now().toString(), 0, 0, 0,
                        "Error: " + error, connectorId));
    }

    public List<RefreshStatusDto> getAll(String tenantId) {
        List<RefreshStatusDto> result = new ArrayList<>();
        for (String t : List.of("CODE", "DOCS", "JIRA")) {
            StatusEntry e = statusMap.getOrDefault(key(tenantId, t),
                    new StatusEntry(t, "IDLE", null, 0, 0, 0, "Not yet run", null));
            result.add(new RefreshStatusDto(e.type(), e.status(), e.lastRunAt(),
                    e.nodesCreated(), e.edgesCreated(), e.nodesUpdated(), e.message(), e.connectorId()));
        }
        return result;
    }

    public RefreshStatusDto get(String tenantId, String type) {
        StatusEntry e = statusMap.getOrDefault(key(tenantId, type.toUpperCase()),
                new StatusEntry(type.toUpperCase(), "IDLE", null, 0, 0, 0, "Not yet run", null));
        return new RefreshStatusDto(e.type(), e.status(), e.lastRunAt(),
                e.nodesCreated(), e.edgesCreated(), e.nodesUpdated(), e.message(), e.connectorId());
    }

    private String key(String tenantId, String type) {
        return tenantId + ":" + type;
    }
}
