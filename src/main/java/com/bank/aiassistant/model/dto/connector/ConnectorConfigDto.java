package com.bank.aiassistant.model.dto.connector;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

public record ConnectorConfigDto(
        String id,
        @NotBlank String connectorType,
        @NotBlank String name,
        Map<String, String> credentials,   // plain-text on write, null on read
        Map<String, Object> config,
        boolean enabled,
        boolean verified,
        boolean readOnly,
        Instant lastSyncAt,
        String lastError
) {}
