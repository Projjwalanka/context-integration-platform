package com.bank.aiassistant.service.connector.sharepoint;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.service.connector.ConnectorCredentialService;
import com.bank.aiassistant.service.connector.spi.ConnectorHealth;
import com.bank.aiassistant.service.connector.spi.ConnectorType;
import com.bank.aiassistant.service.connector.spi.DataSourceConnector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * SharePoint Online connector via Microsoft Graph API.
 *
 * <p>Required credentials (Azure AD App Registration):
 * <ul>
 *   <li>{@code tenantId}</li>
 *   <li>{@code clientId}</li>
 *   <li>{@code clientSecret}</li>
 * </ul>
 * Config: {@code siteId}, {@code driveId}
 *
 * <p>Auth flow: Client Credentials (OAuth 2.0) — suitable for server-to-server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SharePointConnector implements DataSourceConnector {

    private final ConnectorCredentialService credentialService;
    private final ObjectMapper objectMapper;

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String TOKEN_URL   = "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token";

    @Override
    public ConnectorType getType() { return ConnectorType.SHAREPOINT; }

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) {
        long start = System.currentTimeMillis();
        try {
            String token = acquireToken(config);
            buildGraphClient(token).get().uri("/me")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
            return ConnectorHealth.ok(System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return ConnectorHealth.error(ex.getMessage());
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                               String query, int maxResults) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            String token = acquireToken(config);
            String siteId = getConfigValue(config, "siteId");

            // Microsoft Graph search API
            String searchPayload = """
                    {"requests":[{"entityTypes":["driveItem","listItem"],"query":{"queryString":"%s"},"from":0,"size":%d}]}
                    """.formatted(query.replace("\"", "\\\""), maxResults);

            String body = buildGraphClient(token).post()
                    .uri("/search/query")
                    .header("Content-Type", "application/json")
                    .bodyValue(searchPayload)
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));

            results.addAll(parseSearchResponse(body));
        } catch (Exception ex) {
            log.error("SharePoint query failed: {}", ex.getMessage());
        }
        return results;
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) {
        return query(config, "*", 50);
    }

    @Override
    public boolean supportsBatchIngestion() { return true; }

    // ─────────────────────────────────────────────────────────────────────────
    // OAuth 2.0 Client Credentials
    // ─────────────────────────────────────────────────────────────────────────

    private String acquireToken(ConnectorConfig config) {
        Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
        String tenantId = creds.get("tenantId");
        String tokenUrl = TOKEN_URL.replace("{tenantId}", tenantId);

        String formBody = "grant_type=client_credentials" +
                "&client_id=" + creds.get("clientId") +
                "&client_secret=" + creds.get("clientSecret") +
                "&scope=https://graph.microsoft.com/.default";

        String response = WebClient.create(tokenUrl).post()
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(formBody)
                .retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(15));
        try {
            return objectMapper.readTree(response).path("access_token").asText();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to acquire SharePoint token", ex);
        }
    }

    private WebClient buildGraphClient(String token) {
        return WebClient.builder()
                .baseUrl(GRAPH_BASE)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private String getConfigValue(ConnectorConfig config, String key) {
        try {
            if (config.getConfig() != null)
                return objectMapper.readTree(config.getConfig()).path(key).asText(null);
        } catch (Exception ignored) {}
        return null;
    }

    private List<Map.Entry<String, Map<String, Object>>> parseSearchResponse(String json) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            JsonNode hits = objectMapper.readTree(json)
                    .path("value").get(0).path("hitsContainers").get(0).path("hits");
            if (hits == null) return results;
            hits.forEach(hit -> {
                String name = hit.path("resource").path("name").asText("Untitled");
                String webUrl = hit.path("resource").path("webUrl").asText("");
                String summary = hit.path("summary").asText("");
                String content = String.format("[SharePoint] %s\n%s\nURL: %s", name, summary, webUrl);
                results.add(Map.entry(content, Map.of(
                        "source_type", "SHAREPOINT",
                        "title", name,
                        "url", webUrl
                )));
            });
        } catch (Exception ex) {
            log.error("Failed to parse SharePoint response: {}", ex.getMessage());
        }
        return results;
    }
}
