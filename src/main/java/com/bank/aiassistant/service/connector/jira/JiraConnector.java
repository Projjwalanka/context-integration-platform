package com.bank.aiassistant.service.connector.jira;

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
 * Jira Cloud connector via Jira REST API v3.
 *
 * <p>Required credentials in ConnectorConfig:
 * <ul>
 *   <li>{@code baseUrl} — e.g. {@code https://acme.atlassian.net}</li>
 *   <li>{@code email} — Jira account email</li>
 *   <li>{@code apiToken} — Jira API token (not password)</li>
 * </ul>
 *
 * <p>Config options:
 * <ul>
 *   <li>{@code projectKeys} — comma-separated Jira project keys to search</li>
 *   <li>{@code issueTypes} — comma-separated issue types (default: all)</li>
 * </ul>
 *
 * <p>MCP-secured: credentials are AES-256-GCM encrypted at rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JiraConnector implements DataSourceConnector {

    private final ConnectorCredentialService credentialService;
    private final ObjectMapper objectMapper;

    @Override
    public ConnectorType getType() { return ConnectorType.JIRA; }

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) {
        long start = System.currentTimeMillis();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            WebClient client = buildClient(creds);
            client.get().uri("/rest/api/3/myself")
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return ConnectorHealth.ok(System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.error("Jira health check failed: {}", ex.getMessage());
            return ConnectorHealth.error(ex.getMessage());
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                               String query,
                                                               int maxResults) {
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            WebClient client = buildClient(creds);

            // Jira JQL search
            String jql = buildJql(query, config);
            String url = String.format(
                    "/rest/api/3/search?jql=%s&maxResults=%d&fields=summary,description,status,assignee,priority,created,updated",
                    java.net.URLEncoder.encode(jql, java.nio.charset.StandardCharsets.UTF_8), maxResults);

            String responseBody = client.get().uri(url)
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            return parseIssues(responseBody, creds.get("baseUrl"));
        } catch (Exception ex) {
            log.error("Jira query failed for connector {}: {}", config.getId(), ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) {
        return query(config, "ORDER BY updated DESC", 100);
    }

    @Override
    public boolean supportsBatchIngestion() { return true; }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private WebClient buildClient(Map<String, String> creds) {
        String credentials = Base64.getEncoder().encodeToString(
                (creds.get("email") + ":" + creds.get("apiToken")).getBytes());
        return WebClient.builder()
                .baseUrl(creds.get("baseUrl"))
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private String buildJql(String naturalQuery, ConnectorConfig config) {
        StringBuilder jql = new StringBuilder();
        // Text search across summary and description
        if (!naturalQuery.isBlank() && !naturalQuery.startsWith("ORDER BY")) {
            jql.append("text ~ \"").append(naturalQuery.replace("\"", "\\\"")).append("\" AND ");
        }
        // Project filter from connector config
        try {
            if (config.getConfig() != null) {
                JsonNode configNode = objectMapper.readTree(config.getConfig());
                JsonNode projectKeys = configNode.get("projectKeys");
                if (projectKeys != null && !projectKeys.isEmpty()) {
                    jql.append("project in (").append(projectKeys.asText()).append(") AND ");
                }
            }
        } catch (Exception ignored) {}

        jql.append("ORDER BY updated DESC");
        return jql.toString();
    }

    private List<Map.Entry<String, Map<String, Object>>> parseIssues(String json, String baseUrl) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode issues = root.get("issues");
            if (issues == null || !issues.isArray()) return results;

            issues.forEach(issue -> {
                String key = issue.path("key").asText();
                JsonNode fields = issue.get("fields");
                String summary = fields.path("summary").asText();
                String description = extractDescription(fields.get("description"));
                String status = fields.path("status").path("name").asText();
                String priority = fields.path("priority").path("name").asText();

                String content = String.format(
                        "[Jira %s] %s\nStatus: %s | Priority: %s\n%s",
                        key, summary, status, priority,
                        description.length() > 2000 ? description.substring(0, 2000) + "…" : description);

                Map<String, Object> meta = Map.of(
                        "source_type", "JIRA",
                        "issue_key", key,
                        "title", summary,
                        "url", baseUrl + "/browse/" + key
                );
                results.add(Map.entry(content, meta));
            });
        } catch (Exception ex) {
            log.error("Failed to parse Jira response: {}", ex.getMessage());
        }
        return results;
    }

    /** Jira description is Atlassian Document Format (ADF) — extract plain text */
    private String extractDescription(JsonNode adfNode) {
        if (adfNode == null) return "";
        if (adfNode.isTextual()) return adfNode.asText();
        StringBuilder text = new StringBuilder();
        extractTextFromAdf(adfNode, text);
        return text.toString().trim();
    }

    private void extractTextFromAdf(JsonNode node, StringBuilder sb) {
        if (node.has("text")) sb.append(node.get("text").asText()).append(" ");
        if (node.has("content")) {
            node.get("content").forEach(child -> extractTextFromAdf(child, sb));
        }
    }
}
