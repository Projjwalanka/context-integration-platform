package com.bank.aiassistant.service.connector.spi;

import com.bank.aiassistant.model.entity.ConnectorConfig;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface for all data source connectors.
 *
 * <p>To add a new data source:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Annotate with {@code @Service}</li>
 *   <li>Return the appropriate {@link ConnectorType} from {@link #getType()}</li>
 *   <li>The {@link com.bank.aiassistant.service.connector.ConnectorRegistry} auto-discovers it</li>
 * </ol>
 */
public interface DataSourceConnector {

    /** Unique connector type identifier */
    ConnectorType getType();

    /**
     * Test connectivity with the provided credentials.
     * @return health status object
     */
    ConnectorHealth healthCheck(ConnectorConfig config);

    /**
     * Query the data source for content relevant to the given natural-language query.
     * This is the live extraction path (no vector store involved).
     *
     * @param config     persisted connector configuration
     * @param query      natural-language query from the chat
     * @param maxResults maximum number of results to return
     * @return list of (content, metadata) pairs
     */
    List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                        String query,
                                                        int maxResults);

    /**
     * Fetch all documents suitable for vector ingestion (batch/scheduled).
     * Called by the ingestion orchestrator for static-content connectors.
     *
     * @param config persisted connector configuration
     * @return list of (content, metadata) pairs
     */
    List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config);

    /**
     * Whether this connector supports incremental sync (live mode).
     * If false, only batch ingestion is used.
     */
    default boolean supportsLiveQuery() {
        return true;
    }

    /**
     * Whether this connector supports full batch ingestion into the vector store.
     */
    default boolean supportsBatchIngestion() {
        return false;
    }
}
