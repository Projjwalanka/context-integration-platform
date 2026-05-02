package com.bank.aiassistant.model.dto.skg;

import java.util.List;
import java.util.Map;

public record ContextBuildResponse(
        String queryId,
        String query,

        // Step A — Query interpretation
        List<String> detectedEntities,
        String detectedIntent,   // IMPACT_ANALYSIS, SYSTEM_STRUCTURE, COMPONENT_DETAIL, HISTORICAL, GENERAL

        // Step B — Graph traversal
        List<SkgNodeDto> relevantNodes,
        List<SkgEdgeDto> relevantEdges,

        // Step C — Sources used
        List<String> codeFilesUsed,
        List<String> confluenceDocsUsed,
        List<String> jiraTicketsUsed,

        // Step D — Context engineering steps (explainability)
        List<ExplainStep> explainSteps,

        // Step E — Final assembled context
        String assembledContext,
        Map<String, Object> rankingMetadata
) {
    public record ExplainStep(
            int stepNumber,
            String phase,        // INTENT, GRAPH_TRAVERSAL, ENRICHMENT, ASSEMBLY
            String description,
            List<String> details,
            long durationMs
    ) {}
}
