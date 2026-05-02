package com.bank.aiassistant.service.connector.confluence;

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
 * Confluence Cloud connector via Confluence REST API v2.
 *
 * <p>Required credentials: {@code baseUrl}, {@code email}, {@code apiToken}
 * <p>Config: {@code spaceKeys} (comma-separated)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfluenceConnector implements DataSourceConnector {

    private final ConnectorCredentialService credentialService;
    private final ObjectMapper objectMapper;

    @Override
    public ConnectorType getType() { return ConnectorType.CONFLUENCE; }

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) {
        long start = System.currentTimeMillis();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            buildClient(creds).get().uri("/wiki/api/v2/spaces?limit=1")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
            return ConnectorHealth.ok(System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return ConnectorHealth.error(ex.getMessage());
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                               String query, int maxResults) {
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            // CQL (Confluence Query Language) search
            String cql = "text ~ \"" + query.replace("\"", "\\\"") + "\" AND type = page";
            String encoded = java.net.URLEncoder.encode(cql, java.nio.charset.StandardCharsets.UTF_8);
            String url = "/wiki/rest/api/content/search?cql=" + encoded +
                    "&limit=" + maxResults + "&expand=body.storage,metadata.labels";

            String body = buildClient(creds).get().uri(url)
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(15));
            return parsePages(body, creds.get("baseUrl"));
        } catch (Exception ex) {
            log.error("Confluence query failed: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) {
        return query(config, "*", 50);
    }

    @Override
    public boolean supportsBatchIngestion() { return true; }

    private WebClient buildClient(Map<String, String> creds) {
        String auth = Base64.getEncoder().encodeToString(
                (creds.get("email") + ":" + creds.get("apiToken")).getBytes());
        return WebClient.builder()
                .baseUrl(creds.get("baseUrl"))
                .defaultHeader("Authorization", "Basic " + auth)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private List<Map.Entry<String, Map<String, Object>>> parsePages(String json, String baseUrl) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results2 = root.get("results");
            if (results2 == null) return results;
            results2.forEach(page -> {
                String id = page.path("id").asText();
                String title = page.path("title").asText();
                String storageBody = page.path("body").path("storage").path("value").asText();
                // Strip HTML tags for plain text
                String plainText = storageBody.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                String content = String.format("[Confluence] %s\n%s",
                        title, plainText.length() > 3000 ? plainText.substring(0, 3000) + "…" : plainText);
                results.add(Map.entry(content, Map.of(
                        "source_type", "CONFLUENCE",
                        "page_id", id,
                        "title", title,
                        "url", baseUrl + "/wiki/spaces/~default/pages/" + id
                )));
            });
        } catch (Exception ex) {
            log.error("Failed to parse Confluence response: {}", ex.getMessage());
        }
        return results;
    }
}
