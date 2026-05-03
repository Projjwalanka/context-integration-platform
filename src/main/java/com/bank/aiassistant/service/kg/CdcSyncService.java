package com.bank.aiassistant.service.kg;

import com.bank.aiassistant.model.entity.CdcSyncState;
import com.bank.aiassistant.repository.CdcSyncStateRepository;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Change-Data-Capture sync service.
 *
 * Maintains a {@link CdcSyncState} record per (tenant, connector) to enable
 * incremental KG updates — only data that changed since the last sync is
 * re-extracted and re-stored.
 *
 * CDC detection strategy per source type:
 *  GITHUB      → commits/PRs since lastSyncAt (API `since` param)
 *  JIRA        → JQL `updated >= lastSyncAt`
 *  CONFLUENCE  → last-modified filter
 *  DOCUMENTS   → MD5 content hash comparison (stored in checksums map)
 *  Others      → full snapshot diff on checksums
 *
 * A scheduled fallback poll runs every 30 minutes via {@link com.bank.aiassistant.service.ingestion.ConnectorIngestionScheduler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CdcSyncService {

    private final CdcSyncStateRepository syncStateRepo;
    private final ConnectorConfigRepository connectorConfigRepo;
    private final KnowledgeGraphService kgService;

    // ─────────────────────────────────────────────────────────────────────────
    // State management
    // ─────────────────────────────────────────────────────────────────────────

    public CdcSyncState getOrCreate(String tenantId, String connectorId, String connectorType) {
        return syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId)
                .orElseGet(() -> syncStateRepo.save(
                        CdcSyncState.builder()
                                .tenantId(tenantId)
                                .connectorId(connectorId)
                                .connectorType(connectorType)
                                .status(CdcSyncState.SyncStatus.IDLE)
                                .build()));
    }

    public void markRunning(String tenantId, String connectorId) {
        syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId).ifPresent(s -> {
            s.setStatus(CdcSyncState.SyncStatus.RUNNING);
            s.setUpdatedAt(Instant.now());
            syncStateRepo.save(s);
        });
    }

    public void markCompleted(String tenantId, String connectorId, long changed, String cursor) {
        syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId).ifPresent(s -> {
            s.setStatus(CdcSyncState.SyncStatus.COMPLETED);
            s.setLastSyncAt(Instant.now());
            s.setLastCursor(cursor);
            s.setLastBatchChanged(changed);
            s.setTotalRecordsProcessed(s.getTotalRecordsProcessed() + changed);
            s.setLastError(null);
            s.setUpdatedAt(Instant.now());
            syncStateRepo.save(s);
        });
    }

    public void markFailed(String tenantId, String connectorId, String error) {
        syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId).ifPresent(s -> {
            s.setStatus(CdcSyncState.SyncStatus.FAILED);
            s.setLastError(error);
            s.setUpdatedAt(Instant.now());
            syncStateRepo.save(s);
        });
    }

    /**
     * Returns the last sync timestamp for a connector, or epoch if never synced.
     * Connectors use this as the lower-bound filter for their API queries.
     */
    public Instant getLastSyncAt(String tenantId, String connectorId) {
        return syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId)
                .map(CdcSyncState::getLastSyncAt)
                .orElse(Instant.EPOCH);
    }

    public List<CdcSyncState> listForTenant(String tenantId) {
        return syncStateRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    /**
     * Check whether content has changed using hash comparison.
     * Returns true if the content is new or changed.
     */
    public boolean hasChanged(String tenantId, String connectorId, String sourceRef, String contentHash) {
        Optional<CdcSyncState> state = syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId);
        if (state.isEmpty() || state.get().getChecksums() == null) return true;

        String stored = state.get().getChecksums().get(sanitizeChecksumKey(sourceRef));
        return !contentHash.equals(stored);
    }

    /**
     * Update the checksum for a specific source ref after successful processing.
     *
     * <p>The {@code sourceRef} is sanitized before use as a MongoDB map key because
     * MongoDB treats dots as path separators and rejects keys that contain them
     * (e.g., {@code "report.pdf"} → {@code MappingException: Map key contains dots}).
     */
    public void updateChecksum(String tenantId, String connectorId, String sourceRef, String hash) {
        syncStateRepo.findByTenantIdAndConnectorId(tenantId, connectorId).ifPresent(s -> {
            if (s.getChecksums() == null) s.setChecksums(new java.util.HashMap<>());
            s.getChecksums().put(sanitizeChecksumKey(sourceRef), hash);
            s.setUpdatedAt(Instant.now());
            syncStateRepo.save(s);
        });
    }

    /**
     * Sanitizes a source reference string for use as a MongoDB map key.
     *
     * <p>MongoDB does not allow dots ({@code .}) or dollar signs ({@code $}) in
     * document field names / map keys. Filenames like {@code "report.pdf"} or
     * Confluence page refs like {@code "space.page.title"} would cause a
     * {@code MappingException} at save time.
     *
     * <p>Dots are replaced with {@code _DOT_} and dollar signs with {@code _USD_}
     * so the key remains human-readable and the transformation is reversible.
     */
    private static String sanitizeChecksumKey(String sourceRef) {
        if (sourceRef == null) return "_null_";
        return sourceRef.replace("$", "_USD_").replace(".", "_DOT_");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scheduled fallback poll (for connectors without webhooks)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @deprecated Scheduling and actual ingestion have been moved to
     *             {@link com.bank.aiassistant.service.ingestion.ConnectorIngestionScheduler}
     *             to avoid a circular Spring bean dependency
     *             (IngestionPipeline → CdcSyncService).
     *             This method is kept as a no-op to avoid removing public API.
     */
    @Deprecated
    public void scheduledPoll() {
        // Replaced by ConnectorIngestionScheduler.scheduledPoll()
    }
}
