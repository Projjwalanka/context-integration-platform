package com.bank.aiassistant.service.connector.github;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.service.connector.ConnectorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubContextEngineeringService {

    private final ConnectorConfigRepository connectorConfigRepository;
    private final GitHubConnectorSyncService syncService;
    private final GitHubContextIndexService contextIndexService;
    private final ConnectorRegistry connectorRegistry;

    public record GitHubContextResult(String context, Set<String> usedConnectorIds) {}

    public Optional<String> tryDirectAnswer(String userId,
                                            String userEmail,
                                            String userQuery,
                                            List<String> requestedConnectorIds) {
        if (!isRepoCountQuestion(userQuery)) {
            return Optional.empty();
        }
        List<ConnectorConfig> scopedConnectors = resolveScope(userId, userEmail, userQuery, requestedConnectorIds);
        if (scopedConnectors.isEmpty()) {
            return Optional.of("I could not find an enabled GitHub connector for your account.");
        }

        Set<String> connectorIds = scopedConnectors.stream()
                .map(ConnectorConfig::getId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        for (ConnectorConfig connector : scopedConnectors) {
            syncService.syncIfStale(connector);
        }

        long repoCount = contextIndexService.countDistinctRepos(userId, List.copyOf(connectorIds));
        if (repoCount == 0) {
            // Force one fresh sync attempt to answer count-style questions reliably.
            for (ConnectorConfig connector : scopedConnectors) {
                syncService.syncConnector(connector.getId());
            }
            repoCount = contextIndexService.countDistinctRepos(userId, List.copyOf(connectorIds));
        }

        return Optional.of("Connected GitHub repositories available for this account: " + repoCount + ".");
    }

    public GitHubContextResult buildContext(String userId,
                                            String userEmail,
                                            String userQuery,
                                            List<String> requestedConnectorIds) {
        List<ConnectorConfig> scopedConnectors = resolveScope(userId, userEmail, userQuery, requestedConnectorIds);
        if (scopedConnectors.isEmpty()) {
            return new GitHubContextResult("", Set.of());
        }

        for (ConnectorConfig connector : scopedConnectors) {
            syncService.syncIfStale(connector);
        }

        Set<String> connectorIds = scopedConnectors.stream().map(ConnectorConfig::getId).collect(LinkedHashSet::new, Set::add, Set::addAll);
        List<RankedHit> fused = fusedRetrieve(userId, userQuery, connectorIds);
        if (fused.isEmpty()) {
            return new GitHubContextResult(noMatchContext(scopedConnectors), connectorIds);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n## GitHub Context (Account-wide)\n");
        sb.append("Use the snippets below as primary evidence. Prefer exact repository names, issue/PR numbers, and links.\n");
        int limit = Math.min(10, fused.size());
        for (int i = 0; i < limit; i++) {
            RankedHit hit = fused.get(i);
            sb.append(i + 1).append(". ");
            sb.append("[").append(hit.sourceType.toUpperCase()).append("]");
            if (hit.repo != null && !hit.repo.isBlank()) {
                sb.append(" ").append(hit.repo);
            }
            sb.append(" - ").append(trim(hit.title != null ? hit.title : hit.snippet, 160));
            if (hit.url != null && !hit.url.isBlank()) {
                sb.append(" (").append(hit.url).append(")");
            }
            sb.append("\n");
            sb.append("   ").append(trim(hit.snippet, 420)).append("\n");
        }
        return new GitHubContextResult(sb.toString(), connectorIds);
    }

    private List<ConnectorConfig> resolveScope(String userId,
                                               String userEmail,
                                               String query,
                                               List<String> requestedConnectorIds) {
        if (requestedConnectorIds != null && !requestedConnectorIds.isEmpty()) {
            List<ConnectorConfig> selected = new ArrayList<>();
            for (String id : requestedConnectorIds) {
                connectorConfigRepository.findById(id).ifPresent(cfg -> {
                    if (cfg.isEnabled() && "GITHUB".equalsIgnoreCase(cfg.getConnectorType())
                            && userId.equals(cfg.getOwnerId())) {
                        selected.add(cfg);
                    }
                });
            }
            return selected;
        }

        if (!looksLikeGitHubQuestion(query)) {
            return List.of();
        }
        List<ConnectorConfig> byOwnerId =
                connectorConfigRepository.findByOwnerIdAndConnectorTypeIgnoreCaseAndEnabledTrue(userId, "GITHUB");
        if (!byOwnerId.isEmpty()) {
            return byOwnerId;
        }

        if (userEmail != null && !userEmail.isBlank()) {
            List<ConnectorConfig> byOwnerEmail =
                    connectorConfigRepository.findByOwnerEmailAndConnectorTypeIgnoreCaseAndEnabledTrue(userEmail, "GITHUB");
            if (!byOwnerEmail.isEmpty()) {
                log.warn("GitHub scope resolved by owner email fallback for user={}", userEmail);
                return byOwnerEmail;
            }
        }
        return List.of();
    }

    private List<RankedHit> fusedRetrieve(String userId, String query, Set<String> connectorIds) {
        List<RankedHit> localHits = contextIndexService.search(userId, List.copyOf(connectorIds), query, 15).stream()
                .map(hit -> new RankedHit(
                        "index",
                        hit.sourceType(),
                        hit.repo(),
                        hit.title(),
                        hit.body(),
                        hit.url(),
                        hit.score()
                ))
                .toList();

        List<RankedHit> liveHits = new ArrayList<>();
        for (String connectorId : connectorIds) {
            try {
                List<Map.Entry<String, Map<String, Object>>> entries = connectorRegistry.queryEntries(connectorId, query, 8);
                for (Map.Entry<String, Map<String, Object>> e : entries) {
                    Map<String, Object> meta = e.getValue() != null ? e.getValue() : Map.of();
                    liveHits.add(new RankedHit(
                            "live",
                            toString(meta.getOrDefault("content_type", "result")),
                            toString(meta.get("repo")),
                            titleFromText(e.getKey()),
                            e.getKey(),
                            toString(meta.get("url")),
                            0.0
                    ));
                }
            } catch (Exception ex) {
                log.debug("Live GitHub retrieval failed for connector={}: {}", connectorId, ex.getMessage());
            }
        }

        return reciprocalRankFusion(localHits, liveHits);
    }

    private List<RankedHit> reciprocalRankFusion(List<RankedHit> localHits, List<RankedHit> liveHits) {
        Map<String, RankedHit> byKey = new LinkedHashMap<>();
        Map<String, Double> rrf = new HashMap<>();
        int k = 60;

        for (int i = 0; i < localHits.size(); i++) {
            RankedHit hit = localHits.get(i);
            String key = keyOf(hit);
            byKey.putIfAbsent(key, hit);
            rrf.put(key, rrf.getOrDefault(key, 0.0) + 1.0 / (k + i + 1));
        }
        for (int i = 0; i < liveHits.size(); i++) {
            RankedHit hit = liveHits.get(i);
            String key = keyOf(hit);
            byKey.putIfAbsent(key, hit);
            rrf.put(key, rrf.getOrDefault(key, 0.0) + 1.0 / (k + i + 1));
        }

        return byKey.entrySet().stream()
                .sorted((a, b) -> Double.compare(rrf.getOrDefault(b.getKey(), 0.0), rrf.getOrDefault(a.getKey(), 0.0)))
                .map(Map.Entry::getValue)
                .toList();
    }

    private boolean looksLikeGitHubQuestion(String query) {
        if (query == null) {
            return false;
        }
        String q = query.toLowerCase();
        return q.contains("github") || q.contains("repo") || q.contains("repository")
                || q.contains("pull request") || q.contains("pr ")
                || q.contains("issue") || q.contains("commit") || q.contains("branch")
                || q.contains("workflow") || q.contains("actions") || q.contains("code");
    }

    private boolean isRepoCountQuestion(String query) {
        if (query == null) {
            return false;
        }
        String q = query.toLowerCase();
        boolean asksCount = q.contains("how many") || q.contains("count") || q.contains("number of");
        boolean asksReposOrProjects = q.contains("repo") || q.contains("repository") || q.contains("project");
        boolean asksGithub = q.contains("github") || q.contains("connected");
        return asksCount && asksReposOrProjects && asksGithub;
    }

    private String keyOf(RankedHit hit) {
        if (hit.url != null && !hit.url.isBlank()) {
            return hit.url;
        }
        return (hit.repo + "|" + hit.title + "|" + Integer.toHexString(hit.snippet.hashCode()));
    }

    private String titleFromText(String text) {
        if (text == null || text.isBlank()) {
            return "GitHub result";
        }
        String first = text.lines().findFirst().orElse(text).trim();
        return trim(first, 180);
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > max ? cleaned.substring(0, max) + "..." : cleaned;
    }

    private String toString(Object value) {
        return value == null ? null : value.toString();
    }

    private String noMatchContext(List<ConnectorConfig> scopedConnectors) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## GitHub Context (Account-wide)\n");
        sb.append("A GitHub account is connected and queryable in this session.\n");
        sb.append("Connected GitHub connectors: ").append(scopedConnectors.size()).append("\n");
        sb.append("No strongly matching repositories/issues/PRs were retrieved for this exact query.\n");
        sb.append("You should still answer using available GitHub account-level understanding and suggest refining the query if needed.\n");
        return sb.toString();
    }

    private record RankedHit(
            String channel,
            String sourceType,
            String repo,
            String title,
            String snippet,
            String url,
            double score
    ) {}
}
