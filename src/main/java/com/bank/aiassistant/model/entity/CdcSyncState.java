package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Change-Data-Capture state per (tenant, connector).
 *
 * Tracks the last successful sync point so incremental ingestion only
 * processes records that changed since the last run.
 *
 * CDC detection strategy per connector type:
 *  - GITHUB      → timestamp-based (API `since` param) + commit SHA cursor
 *  - JIRA        → JQL `updated >= lastSyncAt`
 *  - CONFLUENCE  → last-modified filter
 *  - DOCUMENTS   → MD5 content hash per file (stored in checksums map)
 *  - Others      → full snapshot diff on checksums
 */
@Document(collection = "cdc_sync_states")
@CompoundIndex(name = "tenant_connector_ux",
               def = "{'tenantId':1,'connectorId':1}",
               unique = true)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CdcSyncState {

    @Id
    private String id;

    private String tenantId;
    private String connectorId;
    private String connectorType;

    /** Timestamp of the last successful sync — used as the lower-bound filter. */
    private Instant lastSyncAt;

    /** Opaque cursor (commit SHA, page token, etc.) for cursor-based APIs. */
    private String lastCursor;

    /**
     * Map of { sourceRef → MD5(content) } for snapshot-based diff detection.
     * Only populated for connectors that don't support timestamp filtering.
     */
    private Map<String, String> checksums;

    @Builder.Default
    private long totalRecordsProcessed = 0;

    @Builder.Default
    private long lastBatchChanged = 0;

    @Builder.Default
    private SyncStatus status = SyncStatus.IDLE;

    private String lastError;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();

    public enum SyncStatus { IDLE, RUNNING, COMPLETED, FAILED }
}
