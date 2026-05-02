package com.bank.aiassistant.service.connector;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.service.connector.spi.ConnectorHealth;
import com.bank.aiassistant.service.connector.spi.ConnectorType;
import com.bank.aiassistant.service.connector.spi.DataSourceConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Auto-discovery registry for all {@link DataSourceConnector} implementations.
 *
 * <p>Spring auto-injects every {@code @Service} that implements {@link DataSourceConnector}.
 * Adding a new connector is purely additive; no changes to this class required.
 */
@Slf4j
@Service
public class ConnectorRegistry {

    private final Map<ConnectorType, DataSourceConnector> connectors;
    private final ConnectorConfigRepository configRepository;

    public ConnectorRegistry(List<DataSourceConnector> connectorList,
                             ConnectorConfigRepository configRepository) {
        this.connectors = connectorList.stream()
                .collect(Collectors.toMap(DataSourceConnector::getType, Function.identity()));
        this.configRepository = configRepository;
        log.info("ConnectorRegistry: registered {} connectors: {}", connectors.size(), connectors.keySet());
    }

    public String query(String connectorConfigId, String naturalLanguageQuery) {
        List<Map.Entry<String, Map<String, Object>>> results =
                queryEntries(connectorConfigId, naturalLanguageQuery, 10);
        if (results.isEmpty()) {
            return null;
        }

        return results.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    public List<Map.Entry<String, Map<String, Object>>> queryEntries(String connectorConfigId,
                                                                      String naturalLanguageQuery,
                                                                      int maxResults) {
        ConnectorConfig config = configRepository.findById(connectorConfigId)
                .orElseThrow(() -> new RuntimeException("Connector not found: " + connectorConfigId));

        if (!config.isEnabled()) {
            log.warn("Connector {} is disabled; skipping query", connectorConfigId);
            return List.of();
        }

        DataSourceConnector connector = resolve(config.getConnectorType());
        return connector.query(config, naturalLanguageQuery, maxResults);
    }

    public ConnectorHealth healthCheck(String connectorConfigId) {
        ConnectorConfig config = configRepository.findById(connectorConfigId)
                .orElseThrow(() -> new RuntimeException("Connector not found: " + connectorConfigId));
        return resolve(config.getConnectorType()).healthCheck(config);
    }

    public List<Map.Entry<String, Map<String, Object>>> fetchAll(String connectorConfigId) {
        ConnectorConfig config = configRepository.findById(connectorConfigId)
                .orElseThrow(() -> new RuntimeException("Connector not found: " + connectorConfigId));
        return resolve(config.getConnectorType()).fetchAll(config);
    }

    private DataSourceConnector resolve(String connectorType) {
        ConnectorType type = ConnectorType.valueOf(connectorType.toUpperCase());
        return Optional.ofNullable(connectors.get(type))
                .orElseThrow(() -> new RuntimeException("No connector implementation for type: " + type));
    }

    public Map<ConnectorType, DataSourceConnector> getAll() {
        return Map.copyOf(connectors);
    }
}
