package com.bank.aiassistant.model.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document(collection = "github_content_index")
@CompoundIndex(name = "connector_url_unique", def = "{'connectorId': 1, 'url': 1}", unique = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GithubContentIndex {

    @Id
    private String id;

    private String connectorId;

    private String userId;

    private String sourceType;

    private String repo;

    private String url;

    private String title;

    private String body;

    private Map<String, Object> metadata;

    private Instant sourceUpdatedAt;

    @Builder.Default
    private Instant ingestedAt = Instant.now();
}
