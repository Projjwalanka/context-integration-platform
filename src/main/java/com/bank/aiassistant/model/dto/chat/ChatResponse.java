package com.bank.aiassistant.model.dto.chat;

import java.time.Instant;
import java.util.List;

public record ChatResponse(
        String messageId,
        String conversationId,
        String content,
        List<Citation> citations,
        List<GeneratedArtifact> artifacts,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Long latencyMs,
        Instant timestamp
) {
    public record Citation(
            String sourceId,
            String sourceType,     // DOCUMENT, JIRA, CONFLUENCE, etc.
            String title,
            String snippet,
            String url
    ) {}

    public record GeneratedArtifact(
            String type,          // PDF, EXCEL, JSON, IMAGE
            String filename,
            String downloadUrl
    ) {}
}
