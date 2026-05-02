package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "messages")
@CompoundIndex(name = "conversation_created_idx", def = "{'conversationId': 1, 'createdAt': 1}")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Message {

    @Id
    private String id;

    private String conversationId;

    private MessageRole role;

    private String content;

    private String metadata;

    private Integer tokenCount;

    private String model;

    private Long latencyMs;

    @Builder.Default
    private Instant createdAt = Instant.now();

    public enum MessageRole {
        USER, ASSISTANT, SYSTEM, TOOL
    }
}
