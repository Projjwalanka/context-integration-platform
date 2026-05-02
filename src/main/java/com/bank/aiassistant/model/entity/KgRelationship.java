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
 * Directed edge in the Knowledge Graph between two {@link KgEntity} nodes.
 *
 * Relationship types: IMPLEMENTS | DEPENDS_ON | DESCRIBES | RELATED_TO |
 * AUTHORED_BY | BELONGS_TO | CONTAINS | REFERENCES | RESOLVES | TESTED_BY | FOLLOWS
 */
@Document(collection = "kg_relationships")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_source", def = "{'tenantId':1,'sourceEntityId':1}"),
    @CompoundIndex(name = "tenant_target", def = "{'tenantId':1,'targetEntityId':1}")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KgRelationship {

    @Id
    private String id;

    @Indexed
    private String tenantId;

    private String sourceEntityId;
    private String targetEntityId;

    private String relationshipType;

    /** Confidence / strength of the relationship (0.0 – 1.0). */
    @Builder.Default
    private double weight = 1.0;

    private Map<String, Object> properties;

    @Builder.Default
    private boolean deprecated = false;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
