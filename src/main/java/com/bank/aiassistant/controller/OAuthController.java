package com.bank.aiassistant.controller;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.model.entity.Role;
import com.bank.aiassistant.model.entity.User;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.repository.UserRepository;
import com.bank.aiassistant.service.connector.ConnectorCredentialService;
import com.bank.aiassistant.service.connector.github.GitHubConnectorSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/connectors/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final ConnectorConfigRepository connectorConfigRepository;
    private final UserRepository userRepository;
    private final ConnectorCredentialService credentialService;
    private final GitHubConnectorSyncService gitHubConnectorSyncService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${oauth.github.client-id:}")
    private String githubClientId;
    @Value("${oauth.github.client-secret:}")
    private String githubClientSecret;

    @Value("${oauth.atlassian.client-id:}")
    private String atlassianClientId;
    @Value("${oauth.atlassian.client-secret:}")
    private String atlassianClientSecret;

    @Value("${oauth.microsoft.client-id:}")
    private String microsoftClientId;
    @Value("${oauth.microsoft.client-secret:}")
    private String microsoftClientSecret;

    @Value("${app.server-url:http://localhost:8080}")
    private String serverUrl;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    private record OAuthState(String type, String name, boolean readOnly, String username, Instant createdAt) {}

    // In-memory state map — TTL cleanup happens on next request (good enough for POC)
    private final ConcurrentHashMap<String, OAuthState> pendingStates = new ConcurrentHashMap<>();

    /**
     * Initiate OAuth flow: returns the provider authorization URL with a CSRF state token.
     * Returns {"error":"not_configured","message":"..."} if provider credentials are missing.
     */
    @GetMapping("/initiate")
    public ResponseEntity<Map<String, String>> initiateOAuth(
            @RequestParam String type,
            @RequestParam(defaultValue = "New Connection") String name,
            @RequestParam(defaultValue = "false") boolean readOnly,
            @AuthenticationPrincipal UserDetails principal) {

        evictExpiredStates();

        String upperType = type.toUpperCase();

        // Validate provider credentials are configured before building any URL
        String credError = checkProviderCredentials(upperType);
        if (credError != null) {
            log.warn("OAuth initiation blocked — credentials not configured for type={}", upperType);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "not_configured",
                    "message", credError
            ));
        }

        String state = UUID.randomUUID().toString();
        String callbackUri = serverUrl + "/api/connectors/oauth/callback";

        pendingStates.put(state, new OAuthState(
                upperType, name, readOnly, principal.getUsername(), Instant.now()));

        String authUrl = buildAuthUrl(upperType, state, callbackUri);
        log.debug("OAuth initiation for type={} user={}", upperType, principal.getUsername());
        return ResponseEntity.ok(Map.of("authUrl", authUrl, "state", state));
    }

    /** Returns an error message if required OAuth credentials are absent, null if all present. */
    private String checkProviderCredentials(String type) {
        return switch (type) {
            case "GITHUB" -> (githubClientId == null || githubClientId.isBlank())
                    ? "GitHub OAuth is not configured. Set the GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET environment variables, then register a GitHub OAuth App with callback URL: " + serverUrl + "/api/connectors/oauth/callback"
                    : null;
            case "JIRA", "CONFLUENCE" -> (atlassianClientId == null || atlassianClientId.isBlank())
                    ? "Atlassian OAuth is not configured. Set the ATLASSIAN_CLIENT_ID and ATLASSIAN_CLIENT_SECRET environment variables, then create an Atlassian OAuth 2.0 app at developer.atlassian.com."
                    : null;
            case "SHAREPOINT" -> (microsoftClientId == null || microsoftClientId.isBlank())
                    ? "Microsoft OAuth is not configured. Set the MICROSOFT_CLIENT_ID and MICROSOFT_CLIENT_SECRET environment variables via Azure AD app registration."
                    : null;
            default -> "OAuth is not supported for connector type: " + type;
        };
    }

    /**
     * OAuth callback: exchanges the code for a token and persists the connector.
     * This endpoint is called by the browser after provider redirect — no JWT available.
     */
    @GetMapping("/callback")
    public void handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response) throws IOException {

        if (error != null || state == null || code == null) {
            String msg = error != null ? error : "invalid_request";
            log.warn("OAuth callback error: {}", msg);
            response.sendRedirect(frontendUrl + "/oauth/callback?error=" + encode(msg));
            return;
        }

        OAuthState oauthState = pendingStates.remove(state);
        if (oauthState == null) {
            log.warn("OAuth callback with unknown or expired state: {}", state);
            response.sendRedirect(frontendUrl + "/oauth/callback?error=state_expired");
            return;
        }

        try {
            String callbackUri = serverUrl + "/api/connectors/oauth/callback";
            Map<String, String> tokens = exchangeCodeForToken(oauthState.type(), code, callbackUri);

            var user = resolveUserByEmail(oauthState.username());
            ConnectorConfig config = ConnectorConfig.builder()
                    .connectorType(oauthState.type())
                    .name(oauthState.name())
                    .ownerId(user.getId())
                    .ownerEmail(user.getEmail())
                    .enabled(true)
                    .verified(true)
                    .readOnly(oauthState.readOnly())
                    .encryptedCredentials(credentialService.encrypt(tokens))
                    .build();
            ConnectorConfig saved = connectorConfigRepository.save(config);
            if ("GITHUB".equalsIgnoreCase(oauthState.type())) {
                gitHubConnectorSyncService.syncConnectorAsync(saved.getId());
            }

            log.info("OAuth connector created: id={} type={} user={}", saved.getId(), oauthState.type(), oauthState.username());
            response.sendRedirect(frontendUrl + "/oauth/callback?success=true&connectorId=" + saved.getId() + "&type=" + oauthState.type());

        } catch (Exception e) {
            log.error("OAuth token exchange failed: {}", e.getMessage());
            response.sendRedirect(frontendUrl + "/oauth/callback?error=token_exchange_failed");
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildAuthUrl(String type, String state, String callbackUri) {
        return switch (type) {
            case "GITHUB" -> "https://github.com/login/oauth/authorize"
                    + "?client_id=" + encode(githubClientId)
                    + "&redirect_uri=" + encode(callbackUri)
                    + "&scope=repo%20read%3Aorg"
                    + "&state=" + state;

            case "JIRA", "CONFLUENCE" -> "https://auth.atlassian.com/authorize"
                    + "?audience=api.atlassian.com"
                    + "&client_id=" + encode(atlassianClientId)
                    + "&scope=" + encode("read:jira-work read:confluence-content.all offline_access")
                    + "&redirect_uri=" + encode(callbackUri)
                    + "&state=" + state
                    + "&response_type=code"
                    + "&prompt=consent";

            case "SHAREPOINT" -> "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
                    + "?client_id=" + encode(microsoftClientId)
                    + "&response_type=code"
                    + "&redirect_uri=" + encode(callbackUri)
                    + "&scope=" + encode("Sites.Read.All Files.Read.All offline_access")
                    + "&state=" + state;

            default -> throw new IllegalArgumentException("OAuth not supported for connector type: " + type);
        };
    }

    private Map<String, String> exchangeCodeForToken(String type, String code, String callbackUri) {
        String tokenUrl = switch (type) {
            case "GITHUB"    -> "https://github.com/login/oauth/access_token";
            case "JIRA", "CONFLUENCE" -> "https://auth.atlassian.com/oauth/token";
            case "SHAREPOINT" -> "https://login.microsoftonline.com/common/oauth2/v2.0/token";
            default -> throw new IllegalArgumentException("Unknown connector type: " + type);
        };

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", callbackUri);

        switch (type) {
            case "GITHUB" -> {
                params.add("client_id", githubClientId);
                params.add("client_secret", githubClientSecret);
            }
            case "JIRA", "CONFLUENCE" -> {
                params.add("client_id", atlassianClientId);
                params.add("client_secret", atlassianClientSecret);
            }
            case "SHAREPOINT" -> {
                params.add("client_id", microsoftClientId);
                params.add("client_secret", microsoftClientSecret);
            }
        }

        String responseBody = webClientBuilder.build()
                .post()
                .uri(tokenUrl)
                .header("Accept", "application/json")
                .body(BodyInserters.fromFormData(params))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode json = objectMapper.readTree(responseBody);
            if (json.has("access_token")) {
                return Map.of(
                        "accessToken", json.get("access_token").asText(),
                        "tokenType", type.toLowerCase(),
                        "refreshToken", json.has("refresh_token") ? json.get("refresh_token").asText() : ""
                );
            }
            throw new RuntimeException("No access_token in provider response");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse token response: " + e.getMessage(), e);
        }
    }

    private void evictExpiredStates() {
        Instant cutoff = Instant.now().minusSeconds(600); // 10-minute TTL
        pendingStates.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    private String encode(String value) {
        if (value == null || value.isBlank()) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private User resolveUserByEmail(String email) {
        return userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(User.builder()
                        .email(email)
                        .password("")
                        .firstName("Dev")
                        .lastName("User")
                        .roles(Set.of(Role.USER))
                        .enabled(true)
                        .locked(false)
                        .build()));
    }
}
