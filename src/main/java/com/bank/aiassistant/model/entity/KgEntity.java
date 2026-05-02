package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Node in the Knowledge Graph. One record per discrete entity
 * (code component, ticket, document, person, concept, etc.).
 *
 * Isolated per tenant — all queries must include tenantId.
 */
@Document(collection = "kg_entities")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_type",      def = "{'tenantId':1,'entityType':1}"),
    @CompoundIndex(name = "tenant_connector", def = "{'tenantId':1,'sourceConnectorId':1}"),
    @CompoundIndex(name = "tenant_sourceref", def = "{'tenantId':1,'sourceRef':1,'sourceType':1}", unique = true,
                   sparse = true)
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KgEntity {

    @Id
    private String id;

    @Indexed
    private String tenantId;

    /** PERSON | CODE_COMPONENT | FUNCTION | CLASS_DEF | TICKET | STORY |
     *  DOCUMENT | CONCEPT | REPOSITORY | COMMIT | PULL_REQUEST | MODULE | API_ENDPOINT */
    private String entityType;

    private String name;
    private String description;

    /** Arbitrary structured properties (e.g. {url, priority, status}). */
    private Map<String, Object> properties;

    @Indexed
    private String sourceConnectorId;

    /** Unique identifier within the source (filename, ticket key, commit SHA, etc.). */
    private String sourceRef;

    /** GITHUB | JIRA | CONFLUENCE | DOCUMENTS | EMAIL | SHAREPOINT */
    private String sourceType;

    /** Pinecone vector ID for semantic retrieval of this entity's embedding. */
    private String embeddingId;

    @Builder.Default
    private boolean deprecated = false;

    @Builder.Default
    private int version = 1;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
