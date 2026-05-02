package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "ingestion_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IngestionJob {

    @Id
    private String id;

    private String connectorType;

    private String connectorId;

    private String sourceRef;

    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    private Integer chunksProcessed;
    private Integer chunksTotal;

    private String errorMessage;

    private Instant startedAt;
    private Instant completedAt;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    public enum JobStatus {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }
}
