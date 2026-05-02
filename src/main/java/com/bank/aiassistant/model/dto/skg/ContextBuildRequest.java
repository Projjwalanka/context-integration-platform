package com.bank.aiassistant.model.dto.skg;

public record ContextBuildRequest(
        String query,
        int maxDepth,         // default 3
        boolean includeCode,
        boolean includeDocs,
        boolean includeJira
) {
    public ContextBuildRequest {
        if (maxDepth <= 0) maxDepth = 3;
    }
}
