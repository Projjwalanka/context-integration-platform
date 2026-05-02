package com.bank.aiassistant.model.dto.skg;

import java.util.List;

public record ImpactAnalysisDto(
        String sourceNodeId,
        String sourceNodeName,
        String sourceNodeType,
        List<ImpactedNode> impactedNodes,
        List<TraversalStep> traversalPath,
        String summary,
        String riskLevel   // LOW, MEDIUM, HIGH, CRITICAL
) {
    public record ImpactedNode(
            SkgNodeDto node,
            String impactType,   // DIRECT, INDIRECT
            int distance,
            String reason,
            String relationshipChain
    ) {}

    public record TraversalStep(
            String nodeId,
            String nodeName,
            String nodeType,
            String viaRelationship,
            int depth
    ) {}
}
