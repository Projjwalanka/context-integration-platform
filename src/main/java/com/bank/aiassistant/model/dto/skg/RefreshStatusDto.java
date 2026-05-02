package com.bank.aiassistant.model.dto.skg;

public record RefreshStatusDto(
        String type,        // CODE, DOCS, JIRA
        String status,      // IDLE, RUNNING, COMPLETED, FAILED
        String lastRunAt,
        int nodesCreated,
        int edgesCreated,
        int nodesUpdated,
        String message,
        String connectorId
) {}
