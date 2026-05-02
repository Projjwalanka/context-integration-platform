package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.model.dto.skg.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * System-centric context engineering service.
 *
 * Execution flow for every query:
 *  A) Intent Understanding  — extract entities + classify intent
 *  B) Graph Traversal       — find relevant nodes via Neo4j BFS
 *  C) Enrichment            — pull code signals, docs, Jira history
 *  D) Context Assembly      — rank + merge into LLM-ready context block
 *
 * Every step is recorded for full explainability.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkgContextService {

    private final SystemKnowledgeGraphService skg;

    // In-memory query store for /explain/{queryId}
    private final Map<String, ContextBuildResponse> queryStore = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public ContextBuildResponse buildContext(String tenantId, ContextBuildRequest req) {
        String queryId = UUID.randomUUID().toString();
        List<ContextBuildResponse.ExplainStep> steps = new ArrayList<>();

        // ── Step A: Intent ──────────────────────────────────────────────────
        long t0 = System.currentTimeMillis();
        List<String> entities = extractEntities(req.query());
        String intent = detectIntent(req.query(), entities);
        steps.add(new ContextBuildResponse.ExplainStep(1, "INTENT",
                "Identified query intent: " + intent + " with " + entities.size() + " entity mention(s)",
                entities, System.currentTimeMillis() - t0));

        // ── Step B: Graph Traversal ─────────────────────────────────────────
        long t1 = System.currentTimeMillis();
        List<SkgNodeDto> seedNodes = findSeedNodes(tenantId, entities);
        List<String> seedIds = seedNodes.stream().map(SkgNodeDto::id).toList();

        int depth = Math.min(req.maxDepth(), 4);
        List<SkgNodeDto> traversedNodes = seedIds.isEmpty() ? seedNodes :
                skg.traverse(tenantId, seedIds, depth);

        Set<String> traversedIds = traversedNodes.stream().map(SkgNodeDto::id).collect(Collectors.toSet());
        List<SkgEdgeDto> relevantEdges = seedIds.isEmpty() ? List.of() :
                traversedNodes.stream()
                        .flatMap(n -> skg.getEdgesForNode(tenantId, n.id()).stream())
                        .filter(e -> traversedIds.contains(e.sourceId()) && traversedIds.contains(e.targetId()))
                        .distinct()
                        .limit(200)
                        .toList();

        steps.add(new ContextBuildResponse.ExplainStep(2, "GRAPH_TRAVERSAL",
                "Traversed " + depth + " hops from " + seedIds.size() + " seed node(s), found " +
                        traversedNodes.size() + " node(s) and " + relevantEdges.size() + " edge(s)",
                traversedNodes.stream().limit(10).map(n -> n.nodeType() + ":" + n.name()).toList(),
                System.currentTimeMillis() - t1));

        // ── Step C: Enrichment ──────────────────────────────────────────────
        long t2 = System.currentTimeMillis();
        // Separate by layer
        List<SkgNodeDto> codeNodes = traversedNodes.stream()
                .filter(n -> "CODE".equals(n.layer()) || "SYSTEM".equals(n.layer())).toList();
        List<SkgNodeDto> docNodes = traversedNodes.stream()
                .filter(n -> "DOCUMENTATION".equals(n.layer())).toList();
        List<SkgNodeDto> workNodes = traversedNodes.stream()
                .filter(n -> "WORK".equals(n.layer())).toList();

        List<String> codeFiles  = codeNodes.stream().map(n -> n.properties() != null ?
                String.valueOf(n.properties().getOrDefault("path", n.name())) : n.name()).limit(20).toList();
        List<String> confluenceDocs = docNodes.stream().map(SkgNodeDto::name).limit(10).toList();
        List<String> jiraTickets    = workNodes.stream().map(SkgNodeDto::name).limit(10).toList();

        steps.add(new ContextBuildResponse.ExplainStep(3, "ENRICHMENT",
                "Classified sources: " + codeNodes.size() + " code/system, " +
                        docNodes.size() + " doc, " + workNodes.size() + " work nodes",
                List.of("Code: " + codeFiles.size(), "Docs: " + confluenceDocs.size(),
                        "Jira: " + jiraTickets.size()),
                System.currentTimeMillis() - t2));

        // ── Step D: Context Assembly ────────────────────────────────────────
        long t3 = System.currentTimeMillis();
        String assembledContext = assembleContext(req.query(), intent, entities,
                traversedNodes, relevantEdges, docNodes, workNodes);

        Map<String, Object> rankingMeta = Map.of(
                "intent", intent,
                "seedNodes", seedIds.size(),
                "totalNodes", traversedNodes.size(),
                "traversalDepth", depth,
                "filterApplied", intent.contains("IMPACT") ? "dependency-chain" : "proximity"
        );

        steps.add(new ContextBuildResponse.ExplainStep(4, "ASSEMBLY",
                "Assembled context block (" + assembledContext.length() + " chars) using " +
                        "graph traversal + documentation enrichment",
                List.of("Ranking: by proximity + layer weight", "Filtered: top " + traversedNodes.size() + " nodes"),
                System.currentTimeMillis() - t3));

        ContextBuildResponse response = new ContextBuildResponse(
                queryId, req.query(),
                entities, intent,
                traversedNodes, relevantEdges,
                codeFiles, confluenceDocs, jiraTickets,
                steps,
                assembledContext, rankingMeta
        );

        queryStore.put(queryId, response);
        return response;
    }

    public Optional<ContextBuildResponse> getExplanation(String queryId) {
        return Optional.ofNullable(queryStore.get(queryId));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intent + Entity extraction
    // ─────────────────────────────────────────────────────────────────────────

    List<String> extractEntities(String query) {
        Set<String> entities = new LinkedHashSet<>();

        // PascalCase / camelCase identifiers (likely component names)
        Pattern pascal = Pattern.compile("\\b([A-Z][a-zA-Z]{2,}(?:Service|Controller|Manager|Handler|" +
                "Repository|Gateway|Client|Module|Component|API|Processor|Engine)s?)\\b");
        Matcher m1 = pascal.matcher(query);
        while (m1.find()) entities.add(m1.group(1));

        // Quoted strings
        Pattern quoted = Pattern.compile("[\"']([^\"']{2,50})[\"']");
        Matcher m2 = quoted.matcher(query);
        while (m2.find()) entities.add(m2.group(1));

        // Generic PascalCase words (fallback)
        if (entities.isEmpty()) {
            Pattern gen = Pattern.compile("\\b([A-Z][a-zA-Z]{3,})\\b");
            Matcher m3 = gen.matcher(query);
            while (m3.find()) entities.add(m3.group(1));
        }

        return new ArrayList<>(entities);
    }

    String detectIntent(String query, List<String> entities) {
        String lower = query.toLowerCase();
        if (lower.matches(".*\\b(impact|affect|break|change|modify|depend)\\b.*")) return "IMPACT_ANALYSIS";
        if (lower.matches(".*\\b(structure|architecture|overview|diagram|map)\\b.*")) return "SYSTEM_STRUCTURE";
        if (lower.matches(".*\\b(history|when|who|commit|bug|ticket|jira)\\b.*")) return "HISTORICAL";
        if (!entities.isEmpty() && lower.matches(".*\\b(what|how|describe|explain|detail)\\b.*")) return "COMPONENT_DETAIL";
        return "GENERAL";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<SkgNodeDto> findSeedNodes(String tenantId, List<String> entities) {
        List<SkgNodeDto> seeds = new ArrayList<>();
        for (String entity : entities) {
            seeds.addAll(skg.searchNodes(tenantId, entity, null));
        }
        return seeds.stream()
                .collect(Collectors.toMap(SkgNodeDto::id, n -> n, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context assembly
    // ─────────────────────────────────────────────────────────────────────────

    private String assembleContext(String query, String intent,
                                   List<String> entities,
                                   List<SkgNodeDto> nodes, List<SkgEdgeDto> edges,
                                   List<SkgNodeDto> docNodes, List<SkgNodeDto> workNodes) {
        StringBuilder ctx = new StringBuilder();

        ctx.append("=== SYSTEM KNOWLEDGE GRAPH CONTEXT ===\n");
        ctx.append("Query: ").append(query).append("\n");
        ctx.append("Intent: ").append(intent).append("\n");
        ctx.append("Entities: ").append(String.join(", ", entities)).append("\n\n");

        // System components
        ctx.append("--- SYSTEM COMPONENTS ---\n");
        nodes.stream()
                .filter(n -> List.of("SYSTEM", "CODE").contains(n.layer()))
                .limit(30)
                .forEach(n -> ctx.append(String.format("[%s] %s — %s\n",
                        n.nodeType(), n.name(),
                        n.description() != null ? n.description() : "")));

        // Relationships (dependency graph)
        if (!edges.isEmpty()) {
            ctx.append("\n--- RELATIONSHIPS ---\n");
            Map<String, String> idToName = nodes.stream()
                    .collect(Collectors.toMap(SkgNodeDto::id, SkgNodeDto::name, (a, b) -> a));
            edges.stream().limit(50).forEach(e ->
                    ctx.append(String.format("%s -[%s]-> %s\n",
                            idToName.getOrDefault(e.sourceId(), e.sourceId()),
                            e.relType(),
                            idToName.getOrDefault(e.targetId(), e.targetId()))));
        }

        // Documentation
        if (!docNodes.isEmpty()) {
            ctx.append("\n--- DOCUMENTATION ---\n");
            docNodes.stream().limit(10).forEach(d ->
                    ctx.append(String.format("[%s] %s\n", d.nodeType(), d.name())));
        }

        // Historical Jira context
        if (!workNodes.isEmpty()) {
            ctx.append("\n--- HISTORICAL JIRA CONTEXT ---\n");
            workNodes.stream().limit(10).forEach(w ->
                    ctx.append(String.format("[%s] %s — %s\n",
                            w.nodeType(), w.name(),
                            w.description() != null ? w.description() : "")));
        }

        ctx.append("\n=== END OF GRAPH CONTEXT ===\n");
        return ctx.toString();
    }
}
