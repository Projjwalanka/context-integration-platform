package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "connector_configs")
@CompoundIndex(name = "owner_type_name_unique", def = "{'ownerId': 1, 'connectorType': 1, 'name': 1}", unique = true)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConnectorConfig {

    @Id
    private String id;

    private String connectorType;

    private String name;

    private String encryptedCredentials;

    private String config;

    @Builder.Default
    private boolean enabled = false;

    @Builder.Default
    private boolean verified = false;

    @Builder.Default
    private boolean readOnly = false;

    private String ownerId;

    private String ownerEmail;

    private Instant lastSyncAt;

    private String lastError;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
