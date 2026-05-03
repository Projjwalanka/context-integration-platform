package com.bank.aiassistant.service.ingestion;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.service.connector.ConnectorRegistry;
import com.bank.aiassistant.service.kg.CdcSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Scheduled CDC poller for non-GitHub connectors (Jira, Confluence, SharePoint, Email, etc.).
 *
 * <p>This class is deliberately separate from {@link CdcSyncService} to avoid a circular
 * Spring bean dependency: {@link IngestionPipeline} → {@link CdcSyncService}.
 *
 * <p>For each enabled non-GitHub connector it:
 * <ol>
 *   <li>Marks the CDC state as RUNNING</li>
 *   <li>Calls {@link ConnectorRegistry#fetchAll(String)} to pull the full corpus</li>
 *   <li>Enriches each entry with standard metadata ({@code connector_id}, {@code user_id})</li>
 *   <li>Delegates to {@link IngestionPipeline#ingestTexts} which chunks, embeds into Pinecone,
 *       and creates {@link com.bank.aiassistant.model.entity.IngestionDocument} records</li>
 *   <li>Marks the CDC state as COMPLETED or FAILED</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorIngestionScheduler {

    private final ConnectorConfigRepository connectorConfigRepo;
    private final ConnectorRegistry connectorRegistry;
    private final IngestionPipeline ingestionPipeline;
    private final CdcSyncService cdcSyncService;

    /**
     * Runs every 30 minutes by default (configurable via {@code app.cdc.poll-interval-ms}).
     * Skips GitHub connectors — those are handled by the dedicated GitHub ingestion service.
     */
    @Scheduled(fixedDelayString = "${app.cdc.poll-interval-ms:1800000}")
    public void scheduledPoll() {
        List<ConnectorConfig> connectors = connectorConfigRepo.findAll().stream()
                .filter(ConnectorConfig::isEnabled)
                .filter(c -> !"GITHUB".equalsIgnoreCase(c.getConnectorType()))
                .toList();

        if (connectors.isEmpty()) {
            log.debug("ConnectorIngestionScheduler: no enabled non-GitHub connectors to poll");
            return;
        }

        log.info("ConnectorIngestionScheduler: polling {} connector(s)", connectors.size());

        for (ConnectorConfig connector : connectors) {
            String tenantId = TenantContext.fromEmail(connector.getOwnerEmail());

            try {
                cdcSyncService.getOrCreate(tenantId, connector.getId(), connector.getConnectorType());
                cdcSyncService.markRunning(tenantId, connector.getId());

                log.info("Fetching corpus for connector={} type={}", connector.getId(), connector.getConnectorType());
                List<Map.Entry<String, Map<String, Object>>> entries =
                        connectorRegistry.fetchAll(connector.getId());

                if (entries == null || entries.isEmpty()) {
                    log.debug("No data returned from connector={}", connector.getId());
                    cdcSyncService.markCompleted(tenantId, connector.getId(), 0L, null);
                    continue;
                }

                // Enrich each entry with connector identity metadata before handing off
                // to the ingestion pipeline (source_ref comes from the connector itself)
                String effectiveOwner = connector.getOwnerEmail();
                List<Map.Entry<String, Map<String, Object>>> enriched = entries.stream().map(e -> {
                    Map<String, Object> meta = new java.util.HashMap<>(
                            e.getValue() != null ? e.getValue() : Map.of());
                    meta.putIfAbsent("connector_id", connector.getId());
                    meta.putIfAbsent("user_id", effectiveOwner);
                    // If the connector didn't supply a source_ref, synthesise one from type + name
                    meta.putIfAbsent("source_ref",
                            connector.getConnectorType() + ":" + connector.getName());
                    return Map.entry(e.getKey(), meta);
                }).toList();

                // Async — returns immediately; completion tracked via IngestionJob + CDC state
                ingestionPipeline.ingestTexts(
                        enriched,
                        connector.getName(),
                        connector.getId(),
                        connector.getConnectorType(),
                        effectiveOwner
                );

                cdcSyncService.markCompleted(tenantId, connector.getId(), (long) entries.size(), null);
                log.info("Ingestion dispatched for connector={} ({} entries)",
                        connector.getId(), entries.size());

            } catch (Exception ex) {
                log.error("ConnectorIngestionScheduler failed for connector={}: {}",
                        connector.getId(), ex.getMessage(), ex);
                try {
                    cdcSyncService.markFailed(tenantId, connector.getId(), ex.getMessage());
                } catch (Exception ignore) {
                    // don't mask the original error
                }
            }
        }
    }
}
