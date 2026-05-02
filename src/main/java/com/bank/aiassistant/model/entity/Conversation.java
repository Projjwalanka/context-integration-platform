package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "conversations")
@CompoundIndex(name = "user_archived_updated_idx", def = "{'userId': 1, 'archived': 1, 'updatedAt': -1}")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Conversation {

    @Id
    private String id;

    private String title;

    private String userId;

    @Builder.Default
    private List<String> activeConnectorIds = new ArrayList<>();

    @Builder.Default
    private boolean archived = false;

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}
