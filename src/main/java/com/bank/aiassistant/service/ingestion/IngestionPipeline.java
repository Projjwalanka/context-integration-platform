package com.bank.aiassistant.service.ingestion;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.entity.IngestionDocument;
import com.bank.aiassistant.model.entity.IngestionJob;
import com.bank.aiassistant.repository.IngestionDocumentRepository;
import com.bank.aiassistant.repository.IngestionJobRepository;
import com.bank.aiassistant.service.kg.CdcSyncService;
import com.bank.aiassistant.service.kg.EntityExtractionService;
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

    private final DocumentChunker chunker;
    private final VectorStoreService vectorStoreService;
    private final IngestionJobRepository jobRepository;
    private final IngestionDocumentRepository documentRepository;
    private final EntityExtractionService entityExtractionService;
    private final CdcSyncService cdcSyncService;

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

            doc.setVectorIds(vectorIds);
            doc.setChunksCount(chunks.size());
            doc.setStatus(IngestionDocument.DocStatus.COMPLETED);
            doc.setUpdatedAt(Instant.now());
            documentRepository.save(doc);

            // Async KG entity extraction — does not block ingestion completion
            String rawText = rawDocs.stream()
                    .map(d -> d.getText() != null ? d.getText() : "")
                    .collect(java.util.stream.Collectors.joining(" "));
            entityExtractionService.extractAndStore(
                    rawText, tenantId,
                    connectorId != null ? connectorId : "UPLOAD",
                    filename, "DOCUMENTS");

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

    @Async
    public CompletableFuture<String> ingestTexts(List<Map.Entry<String, Map<String, Object>>> texts,
                                                  String jobLabel, String connectorType) {
        IngestionJob job = createJob(connectorType, jobLabel);
        try {
            job.setStatus(IngestionJob.JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            List<Document> rawDocs = texts.stream()
                    .map(e -> new Document(e.getKey(), e.getValue()))
                    .toList();

            List<Document> chunks = chunker.chunk(rawDocs, null);
            job.setChunksTotal(chunks.size());
            vectorStoreService.store(chunks);

            job.setChunksProcessed(chunks.size());
            job.setStatus(IngestionJob.JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            return CompletableFuture.completedFuture(job.getId());

        } catch (Exception ex) {
            job.setStatus(IngestionJob.JobStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
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
