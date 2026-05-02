package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.model.dto.skg.ImpactAnalysisDto;
import com.bank.aiassistant.model.dto.skg.SkgNodeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Answers "What breaks if component X changes?" by traversing
 * CALLS → DEPENDS_ON → USES_DB chains in the Neo4j graph.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactAnalysisService {

    private final SystemKnowledgeGraphService skg;

    public ImpactAnalysisDto analyze(String tenantId, String nodeId) {
        Optional<SkgNodeDto> sourceOpt = skg.getNode(tenantId, nodeId);
        if (sourceOpt.isEmpty()) {
            return new ImpactAnalysisDto(nodeId, "Unknown", "Unknown",
                    List.of(), List.of(), "Node not found in graph", "UNKNOWN");
        }

        SkgNodeDto source = sourceOpt.get();
        List<Map<String, Object>> rawImpact = skg.findImpactedNodes(tenantId, nodeId);

        List<ImpactAnalysisDto.ImpactedNode> impactedNodes = rawImpact.stream()
                .map(r -> buildImpactedNode(r, source))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<ImpactAnalysisDto.TraversalStep> path = buildTraversalPath(source, rawImpact);
        String riskLevel = computeRiskLevel(impactedNodes);
        String summary = generateSummary(source, impactedNodes);

        return new ImpactAnalysisDto(
                nodeId, source.name(), source.nodeType(),
                impactedNodes, path, summary, riskLevel
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ImpactAnalysisDto.ImpactedNode buildImpactedNode(Map<String, Object> raw, SkgNodeDto source) {
        try {
            String id       = String.valueOf(raw.get("id"));
            String nodeType = String.valueOf(raw.get("nodeType"));
            String name     = String.valueOf(raw.get("name"));
            String layer    = String.valueOf(raw.getOrDefault("layer", SkgNodeDto.layerFor(nodeType)));
            String desc     = String.valueOf(raw.getOrDefault("description", ""));
            int distance    = ((Number) raw.getOrDefault("distance", 1)).intValue();

            @SuppressWarnings("unchecked")
            List<String> relChain = raw.get("relChain") instanceof List<?> l
                    ? l.stream().map(Object::toString).toList()
                    : List.of();

            SkgNodeDto node = new SkgNodeDto(id, nodeType, layer, name, desc,
                    null, Map.of(), null, null, null);
            String impactType = distance == 1 ? "DIRECT" : "INDIRECT";
            String reason = "Reached via: " + String.join(" → ", relChain);
            return new ImpactAnalysisDto.ImpactedNode(node, impactType, distance, reason,
                    String.join(" → ", relChain));
        } catch (Exception e) {
            log.warn("Failed to build impacted node from raw result: {}", e.getMessage());
            return null;
        }
    }

    private List<ImpactAnalysisDto.TraversalStep> buildTraversalPath(
            SkgNodeDto source, List<Map<String, Object>> rawImpact) {
        List<ImpactAnalysisDto.TraversalStep> steps = new ArrayList<>();
        steps.add(new ImpactAnalysisDto.TraversalStep(
                source.id(), source.name(), source.nodeType(), "START", 0));

        rawImpact.stream()
                .sorted(Comparator.comparingInt(r -> ((Number) r.getOrDefault("distance", 0)).intValue()))
                .limit(10)
                .forEach(r -> {
                    @SuppressWarnings("unchecked")
                    List<String> relChain = r.get("relChain") instanceof List<?> l
                            ? l.stream().map(Object::toString).toList()
                            : List.of();
                    steps.add(new ImpactAnalysisDto.TraversalStep(
                            String.valueOf(r.get("id")),
                            String.valueOf(r.get("name")),
                            String.valueOf(r.get("nodeType")),
                            relChain.isEmpty() ? "RELATED" : relChain.get(relChain.size() - 1),
                            ((Number) r.getOrDefault("distance", 1)).intValue()));
                });

        return steps;
    }

    private String computeRiskLevel(List<ImpactAnalysisDto.ImpactedNode> impacted) {
        long criticalCount = impacted.stream()
                .filter(n -> List.of("Service", "Api", "Database").contains(n.node().nodeType()))
                .count();
        long directCount = impacted.stream().filter(n -> "DIRECT".equals(n.impactType())).count();

        if (criticalCount >= 5 || directCount >= 10) return "CRITICAL";
        if (criticalCount >= 3 || directCount >= 5)  return "HIGH";
        if (criticalCount >= 1 || directCount >= 2)  return "MEDIUM";
        return "LOW";
    }

    private String generateSummary(SkgNodeDto source,
                                    List<ImpactAnalysisDto.ImpactedNode> impacted) {
        if (impacted.isEmpty()) return "No downstream dependencies found for " + source.name() + ".";

        long serviceCount   = impacted.stream().filter(n -> "Service".equals(n.node().nodeType())).count();
        long apiCount       = impacted.stream().filter(n -> "Api".equals(n.node().nodeType())).count();
        long dbCount        = impacted.stream().filter(n -> "Database".equals(n.node().nodeType())).count();
        long directCount    = impacted.stream().filter(n -> "DIRECT".equals(n.impactType())).count();

        String names = impacted.stream()
                .filter(n -> "DIRECT".equals(n.impactType()))
                .limit(5)
                .map(n -> n.node().name())
                .collect(Collectors.joining(", "));

        return String.format(
                "Changing %s (%s) will directly affect %d component(s): %s. " +
                "Total downstream impact: %d service(s), %d API(s), %d database(s) across %d nodes.",
                source.name(), source.nodeType(), directCount, names,
                serviceCount, apiCount, dbCount, impacted.size());
    }
}
