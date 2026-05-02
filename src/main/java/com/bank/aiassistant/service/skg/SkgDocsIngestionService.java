package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.service.connector.ConnectorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Ingests Confluence documentation into the System Knowledge Graph.
 *
 * For each page: creates Document/DesignDoc nodes, extracts Section sub-nodes,
 * and links documents to existing system nodes via DESCRIBES relationships.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkgDocsIngestionService {

    private final SystemKnowledgeGraphService skg;
    private final ConnectorRegistry connectorRegistry;
    private final IngestionStatusService statusService;

    @Async
    public void ingestAsync(String tenantId, String connectorId) {
        ingest(tenantId, connectorId);
    }

    public IngestionStatusService.IngestionSummary ingest(String tenantId, String connectorId) {
        statusService.markRunning(tenantId, "DOCS", connectorId);
        int created = 0, edges = 0;
        try {
            // fetchAll returns List<Map.Entry<content, metadata>>
            List<Map.Entry<String, Map<String, Object>>> entries =
                    connectorRegistry.fetchAll(connectorId);

            for (Map.Entry<String, Map<String, Object>> entry : entries) {
                String body = entry.getKey();                       // page content / body
                Map<String, Object> meta = entry.getValue();

                String pageId = String.valueOf(meta.getOrDefault("id",
                        meta.getOrDefault("pageId", UUID.randomUUID().toString())));
                String title  = String.valueOf(meta.getOrDefault("title", "Untitled"));
                String url    = String.valueOf(meta.getOrDefault("url",
                        meta.getOrDefault("webUrl", meta.getOrDefault("htmlUrl", ""))));
                String space  = String.valueOf(meta.getOrDefault("space",
                        meta.getOrDefault("spaceKey", "")));

                String docType  = classifyDocType(title, body);
                String docNodeId = tenantId + ":Doc:" + pageId;

                skg.upsertNode(tenantId, docNodeId, docType, title, truncate(body, 500),
                        Map.of("pageId", pageId, "url", url, "space", space));
                created++;

                // Section sub-nodes from headings
                List<String> sections = extractSections(body);
                for (String section : sections) {
                    String secId = tenantId + ":Section:" + pageId + ":" + Math.abs(section.hashCode());
                    skg.upsertNode(tenantId, secId, "Section", section,
                            "Section in: " + title, Map.of("parentDoc", pageId));
                    skg.upsertEdge(tenantId, docNodeId, secId, "CONTAINS", Map.of());
                    created++; edges++;
                }

                // Link document to matching system nodes (DESCRIBES relationship)
                edges += linkToSystemNodes(tenantId, docNodeId, title + " " + body);
            }

            statusService.markCompleted(tenantId, "DOCS", connectorId, created, edges,
                    "Ingested " + entries.size() + " Confluence pages");
            return new IngestionStatusService.IngestionSummary(created, edges, List.of());

        } catch (Exception e) {
            log.error("Docs ingestion failed for {}: {}", connectorId, e.getMessage(), e);
            statusService.markFailed(tenantId, "DOCS", connectorId, e.getMessage());
            return new IngestionStatusService.IngestionSummary(created, edges, List.of(e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String classifyDocType(String title, String body) {
        String lower = (title + " " + body).toLowerCase();
        if (lower.contains("architecture") || lower.contains("design") || lower.contains("adr")) return "DesignDoc";
        if (lower.contains("api") || lower.contains("contract") || lower.contains("openapi"))   return "Document";
        return "Document";
    }

    private List<String> extractSections(String body) {
        List<String> sections = new ArrayList<>();
        // Confluence storage XML headings
        Pattern h = Pattern.compile("<h[23][^>]*>([^<]+)</h[23]>", Pattern.CASE_INSENSITIVE);
        Matcher m = h.matcher(body);
        while (m.find() && sections.size() < 20) {
            String heading = m.group(1).trim();
            if (!heading.isBlank()) sections.add(heading);
        }
        // Markdown fallback
        if (sections.isEmpty()) {
            Pattern md = Pattern.compile("^#{2,3}\\s+(.+)$", Pattern.MULTILINE);
            Matcher mm = md.matcher(body);
            while (mm.find() && sections.size() < 20) sections.add(mm.group(1).trim());
        }
        return sections;
    }

    private int linkToSystemNodes(String tenantId, String docNodeId, String text) {
        int edges = 0;
        Pattern p = Pattern.compile("\\b([A-Z][a-zA-Z]{2,}(?:Service|Controller|Manager|Handler|" +
                "Repository|Gateway|Client|Module|Component|API|Processor|Engine)s?)\\b");
        Matcher m = p.matcher(text);
        Set<String> mentioned = new LinkedHashSet<>();
        while (m.find()) mentioned.add(m.group(1));

        for (String name : mentioned) {
            skg.searchNodes(tenantId, name, null).stream()
                    .filter(n -> List.of("Service", "Component", "Module", "Api", "Database").contains(n.nodeType()))
                    .limit(3)
                    .forEach(n -> skg.upsertEdge(tenantId, docNodeId, n.id(), "DESCRIBES", Map.of()));
            edges++;
        }
        return edges;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String plain = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= max ? plain : plain.substring(0, max) + "…";
    }
}
