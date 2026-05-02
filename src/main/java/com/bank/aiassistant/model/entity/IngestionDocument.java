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

    private String ownerId;

    @Builder.Default
    private DocStatus status = DocStatus.INGESTING;

    private Integer chunksCount;

    private List<String> vectorIds;

    private String errorMessage;

    @Builder.Default
    private Instant uploadedAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    public enum DocStatus {
        INGESTING, COMPLETED, FAILED
    }
}
