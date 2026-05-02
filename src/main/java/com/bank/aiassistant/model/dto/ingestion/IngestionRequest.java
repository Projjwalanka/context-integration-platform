package com.bank.aiassistant.model.dto.ingestion;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record IngestionRequest(
        @NotBlank String connectorType,  // DOCUMENTS, JIRA, CONFLUENCE, etc.
        String connectorId,              // connector config ID to pull live docs from
        Map<String, String> options      // connector-specific options
) {}
