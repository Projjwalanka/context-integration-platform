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
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
                    "/rest/api/3/search?jql=%s&maxResults=%d&fields=summary,description,status,assignee,priority,issuetype,labels,created,updated",
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
        String baseUrl = normalizeBaseUrl(creds.get("baseUrl"));

        // Trim whitespace — copy-pasted tokens often have trailing newline/space which
        // silently corrupts the Basic Auth header and causes 401
        String email    = trim(creds.get("email"));
        String apiToken = trim(creds.get("apiToken"));

        if (email.isEmpty() || apiToken.isEmpty()) {
            log.warn("Jira buildClient: missing credentials — email present={} token present={}",
                    !email.isEmpty(), !apiToken.isEmpty());
        }
        log.debug("Jira buildClient: baseUrl={} email={}", baseUrl, email);

        // Use explicit UTF-8 charset — platform default (e.g. Windows-1252) can mangle
        // tokens that contain non-ASCII characters
        String credentials = Base64.getEncoder().encodeToString(
                (email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));

        // Increase codec buffer to 10 MB — Jira Cloud API responses can exceed the
        // default 256 KB limit (large project lists, search results, ADF content)
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + credentials)
                .defaultHeader("Accept", "application/json")
                .exchangeStrategies(strategies)
                .build();
    }

    private static String trim(String value) {
        return value != null ? value.strip() : "";
    }

    /**
     * Normalises the Jira base URL to scheme+host only.
     *
     * <p>Users sometimes paste a full Jira project or board URL
     * (e.g. {@code https://acme.atlassian.net/jira/software/projects/CIP}) as the
     * {@code baseUrl}. That causes health-check requests to resolve incorrectly to
     * {@code .../jira/software/projects/CIP/rest/api/3/myself}, which returns an HTML
     * redirect page instead of JSON and can blow past the codec buffer limit.
     *
     * <p>This method strips any path/query so only
     * {@code https://acme.atlassian.net} is used.
     */
    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            URI uri = URI.create(raw.trim());
            // Reconstruct with scheme + host only (drop path, query, fragment)
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            // If the URL is malformed, return as-is — the API call will fail with a
            // clear error rather than silently using the wrong path
            return raw.trim();
        }
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
                String key         = issue.path("key").asText();
                JsonNode fields    = issue.get("fields");
                String summary     = fields.path("summary").asText();
                String description = extractDescription(fields.get("description"));
                String status      = fields.path("status").path("name").asText("Unknown");
                String priority    = fields.path("priority").path("name").asText("Medium");
                String issueType   = fields.path("issuetype").path("name").asText("Task");
                String assignee    = fields.path("assignee").path("displayName").asText("");

                // Labels: Jira returns them as a plain string array
                List<String> labelsList = new ArrayList<>();
                JsonNode labelsNode = fields.path("labels");
                if (labelsNode.isArray()) {
                    labelsNode.forEach(l -> labelsList.add(l.asText()));
                }

                String content = String.format(
                        "[Jira %s] %s\nType: %s | Status: %s | Priority: %s\n%s",
                        key, summary, issueType, status, priority,
                        description.length() > 2000 ? description.substring(0, 2000) + "…" : description);

                // Use a mutable map — Map.of() has a 10-key limit and rejects duplicates
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("source_type",  "JIRA");
                // "source_ref" = issue key so each issue gets its own IngestionDocument
                meta.put("source_ref",   key);
                // "key" is the primary lookup used by SkgJiraIngestionService
                meta.put("key",          key);
                meta.put("issue_key",    key);   // backward compat
                meta.put("title",        summary);
                meta.put("url",          baseUrl + "/browse/" + key);
                meta.put("issueType",    issueType);
                meta.put("status",       status);
                meta.put("priority",     priority);
                meta.put("assignee",     assignee);
                meta.put("labels",       labelsList);

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
