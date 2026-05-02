package com.bank.aiassistant.model.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        /** Existing conversation ID — null to start a new one */
        String conversationId,

        @NotBlank @Size(max = 8000)
        String message,

        /** Connector IDs to include as live context */
        List<String> connectorIds,

        /** Whether to stream the response via SSE */
        boolean stream,

        /** Optional: audio transcript from voice input */
        String audioTranscript
) {}
