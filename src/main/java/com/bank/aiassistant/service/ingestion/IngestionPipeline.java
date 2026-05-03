package com.bank.aiassistant.service.ingestion;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.entity.IngestionDocument;
import com.bank.aiassistant.model.entity.IngestionJob;
import com.bank.aiassistant.repository.IngestionDocumentRepository;
import com.bank.aiassistant.repository.IngestionJobRepository;
import com.bank.aiassistant.service.kg.CdcSyncService;
import com.bank.aiassistant.service.kg.EntityExtractionService;
import com.bank.aiassistant.service.skg.SkgDocsIngestionService;
import com.bank.aiassistant.service.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipeline {

    /** Characters of raw text stored in {@link IngestionDocument#getTextPreview()} for KG refresh. */
    public static final int TEXT_PREVIEW_CHARS = 6_000;

    private final DocumentChunker chunker;
    private final VectorStoreService vectorStoreService;
    private final IngestionJobRepository jobRepository;
    private final IngestionDocumentRepository documentRepository;
    private final EntityExtractionService entityExtractionService;
    private final CdcSyncService cdcSyncService;
    private final SkgDocsIngestionService skgDocsIngestionService;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    @Async
    public CompletableFuture<String> ingestFile(byte[] fileBytes, String filename,
                                                 String connectorId,
                                                 Map<String, Object> extraMeta) {
        IngestionJob job = createJob(connectorId, filename);

        String ownerId   = extraMeta != null ? (String) extraMeta.get("user_id") : null;
        String tenantId  = TenantContext.fromEmail(ownerId);
        IngestionDocument doc = documentRepository.save(IngestionDocument.builder()
                .fileName(filename)
                .fileType(resolveFileType(filename))
                .fileSize((long) fileBytes.length)
                .connectorId(connectorId != null ? connectorId : "UPLOAD")
                .ownerId(ownerId)
                .status(IngestionDocument.DocStatus.INGESTING)
                .build());

        try {
            job.setStatus(IngestionJob.JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            Resource resource = new ByteArrayResource(fileBytes) {
                @Override public String getFilename() { return filename; }
            };

            List<Document> rawDocs = filename.toLowerCase().endsWith(".pdf")
                    ? readPdf(resource)
                    : readWithTika(resource);

            Map<String, Object> meta = new java.util.HashMap<>(extraMeta != null ? extraMeta : Map.of());
            meta.put("source_ref", filename);
            meta.put("connector_id", connectorId != null ? connectorId : "UPLOAD");
            meta.put("ingested_at", Instant.now().toString());
            meta.put("document_id", doc.getId());

            List<Document> chunks = chunker.chunk(rawDocs, meta);
            List<String> vectorIds = chunks.stream().map(Document::getId).toList();

            job.setChunksTotal(chunks.size());
            vectorStoreService.store(chunks);

            job.setChunksProcessed(chunks.size());
            job.setStatus(IngestionJob.JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            // Build raw text once — used for KG extraction, SKG ingestion, and textPreview
            String rawText = rawDocs.stream()
                    .map(d -> d.getText() != null ? d.getText() : "")
                    .collect(java.util.stream.Collectors.joining(" "));

            doc.setVectorIds(vectorIds);
            doc.setChunksCount(chunks.size());
            doc.setStatus(IngestionDocument.DocStatus.COMPLETED);
            doc.setUpdatedAt(Instant.now());
            // Store a text preview so the KG refresh path can reconstruct Neo4j nodes
            // without needing to fetch content back from Pinecone
            doc.setTextPreview(rawText.length() > TEXT_PREVIEW_CHARS
                    ? rawText.substring(0, TEXT_PREVIEW_CHARS) : rawText);
            documentRepository.save(doc);

            // Async MongoDB KG entity extraction (LLM-based NER over full chunked content)
            entityExtractionService.extractAndStore(
                    rawText, tenantId,
                    connectorId != null ? connectorId : "UPLOAD",
                    filename, "DOCUMENTS");

            // Async Neo4j SKG ingestion — creates Document/Section nodes and DESCRIBES
            // edges to technical components so business-to-technical queries can traverse
            // from this document to the services/APIs it describes.
            skgDocsIngestionService.ingestDocument(tenantId, filename, rawText,
                    filename, connectorId != null ? connectorId : "UPLOAD");

            // Update CDC checksum for document dedup on re-upload
            String hash = Integer.toHexString(new String(fileBytes).hashCode());
            cdcSyncService.getOrCreate(tenantId, connectorId != null ? connectorId : "UPLOAD", "DOCUMENTS");
            cdcSyncService.updateChecksum(tenantId, connectorId != null ? connectorId : "UPLOAD", filename, hash);

            log.info("Ingested '{}': {} chunks stored. jobId={} docId={}", filename, chunks.size(), job.getId(), doc.getId());
            return CompletableFuture.completedFuture(job.getId());

        } catch (Exception ex) {
            log.error("Ingestion failed for '{}': {}", filename, ex.getMessage(), ex);

            job.setStatus(IngestionJob.JobStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            doc.setStatus(IngestionDocument.DocStatus.FAILED);
            doc.setErrorMessage(ex.getMessage());
            doc.setUpdatedAt(Instant.now());
            documentRepository.save(doc);

            return CompletableFuture.failedFuture(ex);
        }
    }

    /**
     * Ingests a list of (text, metadata) entries into Pinecone and tracks each
     * unique source as an {@link IngestionDocument} in MongoDB.
     *
     * @param texts         list of (content, metadata) entries; metadata should include
     *                      {@code source_ref} (page/ticket identifier) for per-document tracking
     * @param jobLabel      human-readable label for the {@link IngestionJob}
     * @param connectorId   ID of the {@link com.bank.aiassistant.model.entity.ConnectorConfig}
     * @param connectorType connector type string (JIRA, CONFLUENCE, etc.)
     * @param ownerId       owner email / user identifier (used for tenant derivation and
     *                      {@link IngestionDocument#getOwnerId()})
     */
    @Async
    public CompletableFuture<String> ingestTexts(List<Map.Entry<String, Map<String, Object>>> texts,
                                                  String jobLabel,
                                                  String connectorId,
                                                  String connectorType,
                                                  String ownerId) {
        String effectiveConnectorId = connectorId != null ? connectorId : connectorType;
        String tenantId = TenantContext.fromEmail(ownerId);
        IngestionJob job = createJob(effectiveConnectorId, jobLabel);

        try {
            job.setStatus(IngestionJob.JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            // Enrich metadata so every chunk carries connector identity
            List<Map.Entry<String, Map<String, Object>>> enriched = texts.stream().map(e -> {
                Map<String, Object> meta = new java.util.HashMap<>(e.getValue() != null ? e.getValue() : Map.of());
                meta.putIfAbsent("connector_id", effectiveConnectorId);
                meta.putIfAbsent("user_id", ownerId);
                meta.putIfAbsent("ingested_at", Instant.now().toString());
                return Map.entry(e.getKey(), meta);
            }).toList();

            // Group entries by source_ref for per-document tracking
            Map<String, List<Map.Entry<String, Map<String, Object>>>> bySourceRef = enriched.stream()
                    .collect(Collectors.groupingBy(e ->
                            (String) e.getValue().getOrDefault("source_ref", jobLabel)));

            // Create an IngestionDocument record for each unique source
            Map<String, IngestionDocument> docsBySourceRef = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<Map.Entry<String, Map<String, Object>>>> group : bySourceRef.entrySet()) {
                String sourceRef = group.getKey();
                long totalSize = group.getValue().stream()
                        .mapToLong(e -> e.getKey().length()).sum();
                IngestionDocument doc = documentRepository.save(IngestionDocument.builder()
                        .fileName(sourceRef)
                        .fileType(connectorType != null ? connectorType.toUpperCase() : "TEXT")
                        .fileSize(totalSize)
                        .connectorId(effectiveConnectorId)
                        .ownerId(ownerId)
                        .status(IngestionDocument.DocStatus.INGESTING)
                        .build());
                docsBySourceRef.put(sourceRef, doc);
            }

            // Chunk and embed all documents into Pinecone
            List<Document> rawDocs = enriched.stream()
                    .map(e -> new Document(e.getKey(), e.getValue()))
                    .toList();
            List<Document> chunks = chunker.chunk(rawDocs, null);
            job.setChunksTotal(chunks.size());
            vectorStoreService.store(chunks);

            // Update each IngestionDocument with its vector IDs and mark completed
            for (Map.Entry<String, IngestionDocument> docEntry : docsBySourceRef.entrySet()) {
                String sourceRef = docEntry.getKey();
                IngestionDocument doc = docEntry.getValue();

                List<String> vectorIds = chunks.stream()
                        .filter(c -> sourceRef.equals(c.getMetadata().get("source_ref")))
                        .map(Document::getId)
                        .toList();

                // Build doc text once — used for KG extraction, SKG ingestion, and textPreview
                String docText = bySourceRef.get(sourceRef).stream()
                        .map(Map.Entry::getKey)
                        .collect(Collectors.joining(" "));

                doc.setVectorIds(vectorIds);
                doc.setChunksCount(vectorIds.size());
                doc.setStatus(IngestionDocument.DocStatus.COMPLETED);
                doc.setUpdatedAt(Instant.now());
                doc.setTextPreview(docText.length() > TEXT_PREVIEW_CHARS
                        ? docText.substring(0, TEXT_PREVIEW_CHARS) : docText);
                documentRepository.save(doc);

                // Async MongoDB KG entity extraction per source document
                entityExtractionService.extractAndStore(docText, tenantId, effectiveConnectorId,
                        sourceRef, connectorType != null ? connectorType.toUpperCase() : "TEXT");

                // Async Neo4j SKG ingestion — so connector-sourced documents (Confluence,
                // Jira, SharePoint, etc.) also create Document nodes and DESCRIBES edges
                // to technical components, enabling business-to-technical traversal.
                skgDocsIngestionService.ingestDocument(tenantId, sourceRef, docText,
                        sourceRef, effectiveConnectorId);
            }

            job.setChunksProcessed(chunks.size());
            job.setStatus(IngestionJob.JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            log.info("ingestTexts completed: jobLabel='{}' sources={} chunks={} connector={}",
                    jobLabel, docsBySourceRef.size(), chunks.size(), effectiveConnectorId);
            return CompletableFuture.completedFuture(job.getId());

        } catch (Exception ex) {
            log.error("ingestTexts failed for '{}': {}", jobLabel, ex.getMessage(), ex);
            job.setStatus(IngestionJob.JobStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            return CompletableFuture.failedFuture(ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Readers
    // ─────────────────────────────────────────────────────────────────────────

    private List<Document> readPdf(Resource resource) {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource,
                PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1)
                        .build()
        );
        return reader.get();
    }

    private List<Document> readWithTika(Resource resource) {
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        return reader.get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private IngestionJob createJob(String connectorId, String sourceRef) {
        IngestionJob job = IngestionJob.builder()
                .connectorType(connectorId != null ? connectorId : "UPLOAD")
                .connectorId(connectorId)
                .sourceRef(sourceRef)
                .status(IngestionJob.JobStatus.PENDING)
                .build();
        return jobRepository.save(job);
    }

    private String resolveFileType(String filename) {
        if (filename == null) return "UNKNOWN";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toUpperCase() : "UNKNOWN";
    }
}
