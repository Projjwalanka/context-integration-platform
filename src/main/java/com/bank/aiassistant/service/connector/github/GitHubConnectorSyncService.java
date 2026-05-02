package com.bank.aiassistant.service.connector.github;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.model.entity.IngestionJob;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.repository.IngestionJobRepository;
import com.bank.aiassistant.service.connector.ConnectorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubConnectorSyncService {

    private final ConnectorConfigRepository connectorConfigRepository;
    private final IngestionJobRepository jobRepository;
    private final ConnectorRegistry connectorRegistry;
    private final GitHubContextIndexService contextIndexService;

    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private final ConcurrentHashMap<String, ReentrantLock> connectorLocks = new ConcurrentHashMap<>();

    @Async
    public void syncConnectorAsync(String connectorId) {
        syncConnector(connectorId);
    }

    public boolean syncConnector(String connectorId) {
        ReentrantLock lock = connectorLocks.computeIfAbsent(connectorId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Skipping GitHub sync because connector is already syncing: {}", connectorId);
            return false;
        }
        ConnectorConfig config = connectorConfigRepository.findById(connectorId).orElse(null);
        if (config == null || !config.isEnabled() || !"GITHUB".equalsIgnoreCase(config.getConnectorType())) {
            lock.unlock();
            return false;
        }

        IngestionJob job = jobRepository.save(IngestionJob.builder()
                .connectorType("GITHUB")
                .connectorId(connectorId)
                .sourceRef(config.getName() != null ? config.getName() : connectorId)
                .status(IngestionJob.JobStatus.RUNNING)
                .startedAt(Instant.now())
                .build());

        try {
            List<Map.Entry<String, Map<String, Object>>> docs = connectorRegistry.fetchAll(connectorId);
            contextIndexService.replaceConnectorCorpus(config, docs);

            config.setLastSyncAt(Instant.now());
            config.setLastError(null);
            connectorConfigRepository.save(config);

            job.setStatus(IngestionJob.JobStatus.COMPLETED);
            job.setChunksTotal(docs.size());
            job.setChunksProcessed(docs.size());
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            log.info("GitHub connector sync complete: connectorId={} docs={}", connectorId, docs.size());
            return true;
        } catch (Exception ex) {
            config.setLastError(truncate(ex.getMessage(), 500));
            connectorConfigRepository.save(config);

            job.setStatus(IngestionJob.JobStatus.FAILED);
            job.setErrorMessage(truncate(ex.getMessage(), 500));
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            log.error("GitHub connector sync failed: connectorId={} error={}", connectorId, ex.getMessage(), ex);
            return false;
        } finally {
            lock.unlock();
        }
    }

    public void syncIfStale(ConnectorConfig config) {
        if (config == null || !"GITHUB".equalsIgnoreCase(config.getConnectorType()) || !config.isEnabled()) {
            return;
        }
        if (config.getLastSyncAt() == null || config.getLastSyncAt().isBefore(Instant.now().minus(STALE_AFTER))) {
            syncConnector(config.getId());
        }
    }

    @Scheduled(fixedDelayString = "${app.github.ingestion.refresh-ms:1800000}")
    public void refreshAllEnabledGithubConnectors() {
        List<ConnectorConfig> configs = connectorConfigRepository.findByConnectorTypeIgnoreCaseAndEnabledTrue("GITHUB");
        for (ConnectorConfig config : configs) {
            if (config.getLastSyncAt() == null || config.getLastSyncAt().isBefore(Instant.now().minus(STALE_AFTER))) {
                syncConnectorAsync(config.getId());
            }
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
