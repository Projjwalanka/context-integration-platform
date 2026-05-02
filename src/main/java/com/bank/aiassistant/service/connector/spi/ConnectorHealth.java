package com.bank.aiassistant.service.connector.spi;

import java.time.Instant;

public record ConnectorHealth(
        boolean healthy,
        String message,
        long latencyMs,
        Instant checkedAt
) {
    public static ConnectorHealth ok(long latencyMs) {
        return new ConnectorHealth(true, "OK", latencyMs, Instant.now());
    }

    public static ConnectorHealth error(String message) {
        return new ConnectorHealth(false, message, -1, Instant.now());
    }
}
