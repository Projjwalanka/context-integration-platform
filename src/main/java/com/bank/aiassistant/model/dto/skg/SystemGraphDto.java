package com.bank.aiassistant.model.dto.skg;

import java.util.List;
import java.util.Map;

public record SystemGraphDto(
        List<SkgNodeDto> nodes,
        List<SkgEdgeDto> edges,
        Map<String, Long> nodeCountByType,
        Map<String, Long> edgeCountByType,
        long totalNodes,
        long totalEdges,
        String tenantId,
        String generatedAt
) {}
