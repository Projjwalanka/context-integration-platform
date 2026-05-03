package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.model.dto.skg.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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

        // For API catalog queries with no entity matches, fetch all Api nodes directly
        if (seedNodes.isEmpty() && "API_CATALOG".equals(intent)) {
            seedNodes = skg.searchNodes(tenantId, "", "Api");
        }

        // Generic fallback: if no seeds found yet, run keyword search over name + description
        if (seedNodes.isEmpty()) {
            seedNodes = keywordFallbackSearch(tenantId, req.query());
            if (!seedNodes.isEmpty()) {
                log.debug("SKG keyword fallback found {} seed nodes for query: {}", seedNodes.size(), req.query());
            }
        }

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

        // 1. PascalCase component identifiers (e.g. UserService, Neo4jTransactionManager)
        Pattern pascal = Pattern.compile("\\b([A-Z][a-zA-Z]{2,}(?:Service|Controller|Manager|Handler|" +
                "Repository|Gateway|Client|Module|Component|API|Processor|Engine)s?)\\b");
        Matcher m1 = pascal.matcher(query);
        while (m1.find()) entities.add(m1.group(1));

        // 2. Quoted strings (user explicitly quoted a name)
        Pattern quoted = Pattern.compile("[\"']([^\"']{2,50})[\"']");
        Matcher m2 = quoted.matcher(query);
        while (m2.find()) entities.add(m2.group(1));

        // 3. Known technology / infrastructure names (case-insensitive)
        //    These are never PascalCase in natural language but map directly to graph nodes
        Matcher m3 = TECH_PATTERN.matcher(query);
        while (m3.find()) entities.add(m3.group(1).toLowerCase());

        // 4. Generic PascalCase words (e.g. "UserGraph", "PaymentFlow")
        if (entities.isEmpty()) {
            Pattern gen = Pattern.compile("\\b([A-Z][a-zA-Z]{3,})\\b");
            Matcher m4 = gen.matcher(query);
            while (m4.find()) entities.add(m4.group(1));
        }

        // 5. Last-resort: significant lower-case domain keywords from the query
        //    Used when the query has no capitalised identifiers at all
        if (entities.isEmpty()) {
            Arrays.stream(query.split("[\\s,;:!?.]+"))
                    .map(String::toLowerCase)
                    .filter(w -> w.length() > 4)
                    .filter(w -> !ENTITY_STOP_WORDS.contains(w))
                    .limit(6)
                    .forEach(entities::add);
        }

        return new ArrayList<>(entities);
    }

    // Technology / database / middleware names that appear lowercase in natural language
    private static final Pattern TECH_PATTERN = Pattern.compile(
            "\\b(neo4j|mongodb|mongo|postgres|postgresql|mysql|redis|kafka|rabbitmq|" +
            "elasticsearch|pinecone|cassandra|dynamodb|arangodb|janusgraph|tigergraph|" +
            "spring|hibernate|jackson|netty|tomcat|docker|kubernetes)\\b",
            Pattern.CASE_INSENSITIVE);

    // Words that carry no entity signal even if they're long enough
    private static final Set<String> ENTITY_STOP_WORDS = Set.of(
            "what", "where", "when", "which", "does", "have", "this", "that", "with",
            "from", "into", "about", "will", "should", "could", "would", "tell", "show",
            "list", "give", "find", "want", "change", "other", "there", "their", "these",
            "make", "need", "then", "class", "classes", "method", "methods",
            "graph", "database", "system", "application", "layer", "level", "using",
            "replace", "migrate", "update", "modify", "another", "different");

    String detectIntent(String query, List<String> entities) {
        String lower = query.toLowerCase();
        // API catalog queries — detect before other checks
        if (isApiCatalogQuery(lower)) return "API_CATALOG";
        if (lower.matches(".*\\b(impact|affect|break|change|modify|depend)\\b.*")) return "IMPACT_ANALYSIS";
        if (lower.matches(".*\\b(structure|architecture|overview|diagram|map)\\b.*")) return "SYSTEM_STRUCTURE";
        if (lower.matches(".*\\b(history|when|who|commit|bug|ticket|jira)\\b.*")) return "HISTORICAL";
        // Business-to-technical: "which service handles payments", "what component manages auth"
        if (hasFunctionalMappingSignal(lower)) return "BUSINESS_TO_TECHNICAL";
        if (!entities.isEmpty() && lower.matches(".*\\b(what|how|describe|explain|detail)\\b.*")) return "COMPONENT_DETAIL";
        return "GENERAL";
    }

    /**
     * Returns true when the query describes a business capability and asks to
     * locate the technical component(s) that implement it.
     *
     * <p>Two independent triggers:
     * <ul>
     *   <li>Explicit mapping phrases — "which service handles X", "what component
     *       manages Y", "responsible for Z", etc.</li>
     *   <li>Business vocabulary + a question signal — "payment" + "what/which/where"</li>
     * </ul>
     */
    private boolean hasFunctionalMappingSignal(String lower) {
        boolean hasExplicitMapping =
                lower.contains("which service")    || lower.contains("which component") ||
                lower.contains("which class")      || lower.contains("which module")    ||
                lower.contains("what service")     || lower.contains("what component")  ||
                lower.contains("what class")       || lower.contains("what module")     ||
                lower.contains("what handles")     || lower.contains("what manages")    ||
                lower.contains("what processes")   || lower.contains("responsible for") ||
                lower.contains("implements the")   || lower.contains("component for")   ||
                lower.contains("service for")      || lower.contains("handles the")     ||
                lower.contains("manages the")      || lower.contains("processes the")   ||
                lower.contains("where is the logic")|| lower.contains("where does the") ||
                lower.contains("find the class")   || lower.contains("find the service")||
                lower.contains("show the service") || lower.contains("show the component");

        boolean hasBizVocab =
                lower.contains("payment")        || lower.contains("transaction")    ||
                lower.contains("authentication") || lower.contains("authorization")  ||
                lower.contains("login")          || lower.contains("account")        ||
                lower.contains("notification")   || lower.contains("report")         ||
                lower.contains("validation")     || lower.contains("calculation")    ||
                lower.contains("customer")       || lower.contains("order")          ||
                lower.contains("loan")           || lower.contains("credit")         ||
                lower.contains("audit")          || lower.contains("compliance")     ||
                lower.contains("upload")         || lower.contains("download")       ||
                lower.contains("export")         || lower.contains("import")         ||
                lower.contains("schedule")       || lower.contains("workflow")       ||
                lower.contains("onboarding")     || lower.contains("reconciliation");

        boolean hasQuestion =
                lower.contains("which") || lower.contains("what") ||
                lower.contains("where") || lower.contains("how")  ||
                lower.contains("find")  || lower.contains("show") ||
                lower.contains("list");

        return hasExplicitMapping || (hasBizVocab && hasQuestion);
    }

    private boolean isApiCatalogQuery(String lower) {
        boolean isListing =
                lower.contains("list all")       || lower.contains("list the")      ||
                lower.contains("show all")       || lower.contains("show me all")   ||
                lower.contains("give me all")    || lower.contains("give me list")  ||
                lower.contains("give me a list") || lower.contains("a list of all") ||
                lower.contains("list of all")    || lower.contains("a list of the") ||
                lower.contains("what are all")   || lower.contains("all apis")      ||
                lower.contains("all endpoints")  || lower.contains("all end points")||
                lower.contains("all rest")       || lower.contains("list rest")     ||
                lower.contains("list api")       || lower.contains("list endpoint") ||
                lower.contains("list end point") || lower.contains("enumerate");
        boolean isApiRelated =
                lower.contains("api")        || lower.contains("endpoint") ||
                lower.contains("end point")  || lower.contains("rest")     ||
                lower.contains("route")      || lower.contains("controller");
        return isListing && isApiRelated;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Graph helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<SkgNodeDto> findSeedNodes(String tenantId, List<String> entities) {
        List<SkgNodeDto> seeds = new ArrayList<>();
        for (String entity : entities) {
            seeds.addAll(skg.searchNodes(tenantId, entity, null));
        }
        return dedup(seeds);
    }

    /**
     * Keyword-based fallback search: splits the raw query into words and searches
     * the graph for nodes whose name OR description contain any of those words.
     * Called when entity extraction produced no graph hits.
     */
    private List<SkgNodeDto> keywordFallbackSearch(String tenantId, String query) {
        List<String> keywords = Arrays.stream(query.split("[\\s,;:!?.]+"))
                .map(String::toLowerCase)
                .filter(w -> w.length() > 3)
                .filter(w -> !ENTITY_STOP_WORDS.contains(w))
                .distinct()
                .limit(8)
                .toList();

        List<SkgNodeDto> hits = new ArrayList<>();
        for (String kw : keywords) {
            List<SkgNodeDto> byName = skg.searchNodes(tenantId, kw, null);
            List<SkgNodeDto> byDesc = skg.searchNodesByDescription(tenantId, kw);
            hits.addAll(byName);
            hits.addAll(byDesc);
            if (hits.size() >= 20) break; // enough seeds
        }
        return dedup(hits);
    }

    private List<SkgNodeDto> dedup(List<SkgNodeDto> nodes) {
        return nodes.stream()
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

        // API catalog — dedicated structured listing
        if ("API_CATALOG".equals(intent)) {
            ctx.append("--- REST API ENDPOINTS ---\n");
            nodes.stream()
                    .filter(n -> "Api".equals(n.nodeType()))
                    .forEach(n -> {
                        String method = n.properties() != null
                                ? String.valueOf(n.properties().getOrDefault("method", "")).toUpperCase()
                                : "";
                        String path = n.properties() != null
                                ? String.valueOf(n.properties().getOrDefault("path", n.name()))
                                : n.name();
                        ctx.append("- ");
                        if (!method.isBlank() && !"NULL".equals(method)) ctx.append(method).append(" ");
                        ctx.append(path);
                        if (n.description() != null && !n.description().isBlank()) {
                            ctx.append(" — ").append(n.description());
                        }
                        ctx.append("\n");
                    });
            // Also include Function nodes that look like handlers
            nodes.stream()
                    .filter(n -> "Function".equals(n.nodeType()))
                    .limit(20)
                    .forEach(n -> ctx.append(String.format("  [handler] %s — %s\n",
                            n.name(), n.description() != null ? n.description() : "")));
        } else if ("IMPACT_ANALYSIS".equals(intent)) {

            // For impact/change queries: show exactly WHICH classes/files would need modification
            ctx.append("--- CLASSES / FILES THAT WOULD REQUIRE CHANGES ---\n");
            ctx.append("(Based on the indexed codebase — answer ONLY from this list)\n\n");
            nodes.stream()
                    .filter(n -> List.of("CODE", "SYSTEM").contains(n.layer()))
                    .forEach(n -> {
                        String path = n.properties() != null
                                ? String.valueOf(n.properties().getOrDefault("path", ""))
                                : "";
                        ctx.append(String.format("• [%s] %s", n.nodeType(), n.name()));
                        if (!path.isBlank() && !"null".equals(path)) ctx.append("  (").append(path).append(")");
                        if (n.description() != null && !n.description().isBlank()) {
                            ctx.append("\n  ↳ ").append(n.description());
                        }
                        ctx.append("\n");
                    });

        } else if ("BUSINESS_TO_TECHNICAL".equals(intent)) {

            // Business-to-technical mapping: show doc nodes and the technical
            // components they describe via DESCRIBES edges, then also list the
            // technical components directly so the LLM has full context.
            ctx.append("--- BUSINESS CAPABILITY → TECHNICAL COMPONENT MAPPING ---\n");
            ctx.append("(Mapped from indexed documentation and codebase knowledge graph)\n\n");

            // Build a lookup: id → node for edge resolution
            Map<String, SkgNodeDto> idToNode = nodes.stream()
                    .collect(Collectors.toMap(SkgNodeDto::id, n -> n, (a, b) -> a));

            // For each documentation node, show which technical nodes it describes
            List<SkgNodeDto> docNodes2 = nodes.stream()
                    .filter(n -> "DOCUMENTATION".equals(n.layer()))
                    .limit(10)
                    .toList();

            if (!docNodes2.isEmpty()) {
                ctx.append("Business / Functional Specifications:\n");
                for (SkgNodeDto doc : docNodes2) {
                    ctx.append(String.format("\n[%s] %s\n", doc.nodeType(), doc.name()));
                    if (doc.description() != null && !doc.description().isBlank()) {
                        ctx.append("  Context: ").append(doc.description()).append("\n");
                    }
                    // Find DESCRIBES edges from this doc to technical nodes
                    List<SkgNodeDto> described = edges.stream()
                            .filter(e -> e.sourceId().equals(doc.id())
                                      && "DESCRIBES".equals(e.relType()))
                            .map(e -> idToNode.get(e.targetId()))
                            .filter(Objects::nonNull)
                            .toList();
                    if (!described.isEmpty()) {
                        ctx.append("  Implements / relates to:\n");
                        described.forEach(n -> ctx.append(String.format(
                                "    • [%s] %-40s — %s\n",
                                n.nodeType(), n.name(),
                                n.description() != null ? n.description() : "")));
                    } else {
                        ctx.append("  (No direct DESCRIBES edges yet — see technical components below)\n");
                    }
                }
                ctx.append("\n");
            }

            // Always include the technical components so the LLM can reason about them
            List<SkgNodeDto> techNodes = nodes.stream()
                    .filter(n -> List.of("SYSTEM", "CODE").contains(n.layer()))
                    .limit(25)
                    .toList();
            if (!techNodes.isEmpty()) {
                ctx.append("Relevant Technical Components:\n");
                techNodes.forEach(n -> ctx.append(String.format(
                        "  [%s] %s — %s\n",
                        n.nodeType(), n.name(),
                        n.description() != null ? n.description() : "")));
            }

            if (docNodes2.isEmpty() && techNodes.isEmpty()) {
                ctx.append("(No indexed documentation or components matched the query terms. " +
                        "Ingest relevant documents or Confluence pages to enable business-to-technical mapping.)\n");
            }

        } else {

        // System components (generic / SYSTEM_STRUCTURE / COMPONENT_DETAIL)
        ctx.append("--- SYSTEM COMPONENTS ---\n");
        nodes.stream()
                .filter(n -> List.of("SYSTEM", "CODE").contains(n.layer()))
                .limit(30)
                .forEach(n -> ctx.append(String.format("[%s] %s — %s\n",
                        n.nodeType(), n.name(),
                        n.description() != null ? n.description() : "")));
        } // end non-catalog / non-impact

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
