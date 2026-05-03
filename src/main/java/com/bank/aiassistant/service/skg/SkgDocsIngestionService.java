package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.dto.skg.SkgNodeDto;
import com.bank.aiassistant.model.entity.IngestionDocument;
import com.bank.aiassistant.repository.IngestionDocumentRepository;
import com.bank.aiassistant.service.connector.ConnectorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

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
    private final IngestionDocumentRepository ingestionDocumentRepository;

    // ── Technical node types that documents can DESCRIBE ──────────────────────
    private static final Set<String> TECHNICAL_NODE_TYPES =
            Set.of("Service", "Component", "Module", "Api", "Database",
                   "Function", "Class", "Config", "Table");

    // Stop-words for domain keyword extraction (short/structural words to skip)
    private static final Set<String> DOC_STOP_WORDS = Set.of(
            "this","that","with","from","into","about","will","should","could","would",
            "have","been","were","they","them","their","when","what","which","where",
            "section","page","chapter","document","content","following","above","below",
            "please","note","example","such","also","more","than","other","some",
            "used","using","uses","each","every","both","only","must","need","needs",
            "then","there","these","those","here","like","just","very","much","many",
            "first","last","next","back","true","false","null","void","return",
            "http","https","www","html","title","body","href","span","class");

    // ─────────────────────────────────────────────────────────────────────────

    @Async
    public void ingestAsync(String tenantId, String connectorId) {
        ingest(tenantId, connectorId);
    }

    /**
     * Ingests a single document (uploaded PDF, connector page, etc.) directly
     * into Neo4j SKG without going through the full connector fetch pipeline.
     * Runs {@code @Async} so the caller (e.g. {@code IngestionPipeline}) is not blocked.
     */
    @Async
    public void ingestDocument(String tenantId, String title, String body,
                               String sourceRef, String connectorId) {
        doIngestDocument(tenantId, title, body, sourceRef, connectorId);
    }

    /**
     * Re-ingests all successfully uploaded documents for a given owner into Neo4j.
     *
     * <p>Reads {@link IngestionDocument} records (status=COMPLETED) from MongoDB
     * and uses the stored {@code textPreview} to reconstruct Document nodes and
     * {@code DESCRIBES} edges in Neo4j. This is the hook called by the KG refresh
     * endpoint so manually-uploaded PDFs appear in the knowledge graph document
     * section alongside connector-sourced pages.
     *
     * <p>Runs {@code @Async} — the refresh endpoint returns 202 immediately.
     *
     * @param tenantId  tenant scope derived from owner email
     * @param ownerEmail  email of the user whose uploaded documents to refresh
     */
    @Async
    public void refreshUploadedDocuments(String tenantId, String ownerEmail) {
        List<IngestionDocument> docs = ingestionDocumentRepository
                .findByOwnerIdAndStatus(ownerEmail, IngestionDocument.DocStatus.COMPLETED);

        if (docs.isEmpty()) {
            log.debug("SKG document refresh: no completed documents for owner={}", ownerEmail);
            return;
        }

        log.info("SKG document refresh: processing {} documents for owner={}", docs.size(), ownerEmail);
        int ingested = 0, skipped = 0;
        for (IngestionDocument doc : docs) {
            if (doc.getTextPreview() == null || doc.getTextPreview().isBlank()) {
                // Document was ingested before textPreview was added — create a minimal node
                // from filename metadata so it at least appears in the graph
                doIngestDocument(tenantId, doc.getFileName(), "",
                        doc.getFileName(), doc.getConnectorId());
                skipped++;   // counted separately — no content for DESCRIBES edges
            } else {
                doIngestDocument(tenantId, doc.getFileName(), doc.getTextPreview(),
                        doc.getFileName(), doc.getConnectorId());
                ingested++;
            }
        }
        log.info("SKG document refresh complete: ingested={} metadata-only={} owner={}",
                ingested, skipped, ownerEmail);
    }

    /**
     * Synchronous core logic for document ingestion into Neo4j.
     * Called from {@link #ingestDocument} (async proxy) and from
     * {@link #refreshUploadedDocuments} (same-bean call — Spring proxy
     * does not apply, so the method runs synchronously as required).
     */
    private void doIngestDocument(String tenantId, String title, String body,
                                  String sourceRef, String connectorId) {
        try {
            String docType   = classifyDocType(title, body);
            String docNodeId = tenantId + ":Doc:" + sanitizeNodeId(sourceRef);
            String effective = connectorId != null ? connectorId : "UPLOAD";

            skg.upsertNode(tenantId, docNodeId, docType, title, truncate(body, 500),
                    Map.of("sourceRef", sourceRef, "connectorId", effective));

            // Section sub-nodes extracted from headings
            List<String> sections = extractSections(body);
            for (String section : sections) {
                String secId = tenantId + ":Section:" + sanitizeNodeId(sourceRef) + ":"
                        + Math.abs(section.hashCode());
                skg.upsertNode(tenantId, secId, "Section", section,
                        "Section in: " + title, Map.of("parentDoc", sourceRef));
                skg.upsertEdge(tenantId, docNodeId, secId, "CONTAINS", Map.of());
            }

            int edgeCount = body.isBlank() ? 0 : linkToSystemNodes(tenantId, docNodeId, title + " " + body);

            log.info("SKG: ingested document '{}' → nodeId={} sections={} describes-edges={}",
                    title, docNodeId, sections.size(), edgeCount);
        } catch (Exception e) {
            log.error("SKG ingestDocument failed for '{}': {}", sourceRef, e.getMessage(), e);
        }
    }

    /** Makes a sourceRef safe for use as a Neo4j node ID property value. */
    private static String sanitizeNodeId(String raw) {
        if (raw == null) return "unknown";
        return raw.replaceAll("[^a-zA-Z0-9_\\-]", "_");
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

    /**
     * Creates {@code DESCRIBES} edges from a document node to relevant technical
     * system nodes in Neo4j.
     *
     * <p>Two-pass strategy:
     * <ol>
     *   <li><b>PascalCase pass</b> — finds explicit class/service names in the text
     *       (e.g. {@code ChatService}, {@code PaymentProcessor}) and does an exact
     *       name lookup.</li>
     *   <li><b>Domain keyword pass</b> — strips HTML, splits text into significant
     *       words, and searches both node name AND description fields in Neo4j.
     *       This bridges documents that use business language (e.g. "payment validation")
     *       to technical nodes (e.g. {@code PaymentValidationService}).</li>
     * </ol>
     */
    private int linkToSystemNodes(String tenantId, String docNodeId, String text) {
        Set<SkgNodeDto> candidates = new LinkedHashSet<>();

        // ── Pass 1: PascalCase class/service names ─────────────────────────
        Pattern pascal = Pattern.compile(
                "\\b([A-Z][a-zA-Z]{2,}(?:Service|Controller|Manager|Handler|" +
                "Repository|Gateway|Client|Module|Component|API|Processor|Engine)s?)\\b");
        Matcher m = pascal.matcher(text);
        Set<String> pascalNames = new LinkedHashSet<>();
        while (m.find()) pascalNames.add(m.group(1));

        for (String name : pascalNames) {
            skg.searchNodes(tenantId, name, null).stream()
                    .filter(n -> TECHNICAL_NODE_TYPES.contains(n.nodeType()))
                    .limit(3)
                    .forEach(candidates::add);
        }

        // ── Pass 2: Domain keyword semantic search ──────────────────────────
        List<String> keywords = extractDomainKeywords(text);
        for (String kw : keywords) {
            // Search by node name
            skg.searchNodes(tenantId, kw, null).stream()
                    .filter(n -> TECHNICAL_NODE_TYPES.contains(n.nodeType()))
                    .limit(2)
                    .forEach(candidates::add);
            // Search by node description (catches e.g. "payment" → PaymentService
            // whose description says "handles payment processing")
            skg.searchNodesByDescription(tenantId, kw).stream()
                    .filter(n -> TECHNICAL_NODE_TYPES.contains(n.nodeType()))
                    .limit(2)
                    .forEach(candidates::add);
            if (candidates.size() >= 40) break; // cap to avoid runaway edge creation
        }

        // Create DESCRIBES edges for all resolved candidates
        for (SkgNodeDto candidate : candidates) {
            skg.upsertEdge(tenantId, docNodeId, candidate.id(), "DESCRIBES", Map.of());
        }
        return candidates.size();
    }

    /**
     * Extracts significant domain keywords from document text for semantic
     * linking. Strips HTML tags, splits on whitespace/punctuation, removes
     * stop-words, and lowercases for case-insensitive graph search.
     */
    private List<String> extractDomainKeywords(String text) {
        // Strip HTML/XML markup
        String plain = text.replaceAll("<[^>]+>", " ");
        // Split PascalCase identifiers so "ChatService" → "chat service"
        plain = plain.replaceAll("([a-z])([A-Z])", "$1 $2");
        // Remove non-alpha characters
        plain = plain.replaceAll("[^a-zA-Z\\s]", " ").replaceAll("\\s+", " ").trim();

        return Arrays.stream(plain.split("\\s+"))
                .filter(w -> w.length() > 3)
                .filter(w -> !DOC_STOP_WORDS.contains(w.toLowerCase()))
                .map(String::toLowerCase)
                .distinct()
                .limit(30)
                .collect(Collectors.toList());
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String plain = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= max ? plain : plain.substring(0, max) + "…";
    }
}
