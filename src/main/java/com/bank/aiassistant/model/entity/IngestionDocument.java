package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "ingestion_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IngestionDocument {

    @Id
    private String id;

    private String fileName;
    private String fileType;
    private Long fileSize;

    @Indexed
    private String connectorId;

    @Indexed
    private String ownerId;

    @Builder.Default
    private DocStatus status = DocStatus.INGESTING;

    private Integer chunksCount;

    private List<String> vectorIds;

    /**
     * First {@value com.bank.aiassistant.service.ingestion.IngestionPipeline#TEXT_PREVIEW_CHARS}
     * characters of the raw extracted text.
     *
     * <p>Stored at ingestion time so the Neo4j SKG refresh path can reconstruct
     * Document nodes and {@code DESCRIBES} edges without needing to fetch content
     * back from Pinecone.
     */
    private String textPreview;

    private String errorMessage;

    @Builder.Default
    private Instant uploadedAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    public enum DocStatus {
        INGESTING, COMPLETED, FAILED
    }
}
