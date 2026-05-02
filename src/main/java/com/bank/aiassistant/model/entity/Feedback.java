package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "feedback")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Feedback {

    @Id
    private String id;

    private String messageId;
    private String conversationId;
    private String userId;

    private FeedbackType type;

    private Integer rating;

    private String comment;

    private String category;

    @Builder.Default
    private Instant createdAt = Instant.now();

    public enum FeedbackType {
        THUMBS_UP, THUMBS_DOWN, RATING
    }
}
