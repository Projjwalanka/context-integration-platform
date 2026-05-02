package com.bank.aiassistant.controller;

import com.bank.aiassistant.model.entity.IngestionDocument;
import com.bank.aiassistant.model.entity.IngestionJob;
import com.bank.aiassistant.repository.IngestionDocumentRepository;
import com.bank.aiassistant.repository.IngestionJobRepository;
import com.bank.aiassistant.service.ingestion.IngestionPipeline;
import com.bank.aiassistant.service.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionPipeline ingestionPipeline;
    private final IngestionJobRepository jobRepository;
    private final IngestionDocumentRepository documentRepository;
    private final VectorStoreService vectorStoreService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "connectorId", required = false) String connectorId,
            @AuthenticationPrincipal UserDetails principal) throws Exception {

        Map<String, Object> meta = Map.of(
                "user_id", principal.getUsername(),
                "source_type", "DOCUMENTS"
        );
        CompletableFuture<String> future = ingestionPipeline.ingestFile(
                file.getBytes(), file.getOriginalFilename(), connectorId, meta);

        return ResponseEntity.accepted().body(Map.of(
                "message", "Ingestion started",
                "filename", file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown"
        ));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<IngestionJob>> listJobs(
            @RequestParam(required = false) String connectorId,
            @RequestParam(required = false) String connectorType) {
        if (connectorId != null && !connectorId.isBlank()) {
            return ResponseEntity.ok(jobRepository.findByConnectorIdOrderByCreatedAtDesc(connectorId));
        }
        if (connectorType != null && !connectorType.isBlank()) {
            return ResponseEntity.ok(jobRepository.findByConnectorTypeOrderByCreatedAtDesc(connectorType));
        }
        return ResponseEntity.ok(jobRepository.findTop20ByOrderByCreatedAtDesc());
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<IngestionJob> getJob(@PathVariable String id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/documents")
    public ResponseEntity<List<IngestionDocument>> listDocuments(
            @RequestParam String connectorId) {
        return ResponseEntity.ok(
                documentRepository.findByConnectorIdOrderByUploadedAtDesc(connectorId));
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id) {
        return documentRepository.findById(id).map(doc -> {
            if (doc.getVectorIds() != null && !doc.getVectorIds().isEmpty()) {
                vectorStoreService.delete(doc.getVectorIds());
            }
            documentRepository.deleteById(id);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
