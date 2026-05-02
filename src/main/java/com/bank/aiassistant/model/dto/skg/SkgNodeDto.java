package com.bank.aiassistant.model.dto.skg;

import java.util.Map;

public record SkgNodeDto(
        String id,
        String nodeType,   // Service, Module, Component, Class, Function, Api, Database, Table, Document, Story, Bug, Repository
        String layer,      // SYSTEM, CODE, DOCUMENTATION, WORK
        String name,
        String description,
        String tenantId,
        Map<String, Object> properties,
        String sourceRef,
        String createdAt,
        String updatedAt
) {
    public static SkgNodeDto of(String id, String nodeType, String name) {
        return new SkgNodeDto(id, nodeType, layerFor(nodeType), name, null, null, Map.of(), null, null, null);
    }

    public static String layerFor(String nodeType) {
        return switch (nodeType == null ? "" : nodeType) {
            case "Service", "Module", "Component", "Database", "Table", "Field", "Config", "Environment", "Api" -> "SYSTEM";
            case "Class", "Function", "Repository_Node", "Branch", "Commit", "PullRequest" -> "CODE";
            case "Document", "Section", "DesignDoc", "ArchitectureDiagram" -> "DOCUMENTATION";
            case "Story", "Bug", "Task", "Sprint" -> "WORK";
            default -> "SYSTEM";
        };
    }
}
