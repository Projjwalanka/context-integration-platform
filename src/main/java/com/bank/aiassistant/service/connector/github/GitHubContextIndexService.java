package com.bank.aiassistant.service.connector.github;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.model.entity.GithubContentIndex;
import com.bank.aiassistant.repository.GithubContentIndexRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubContextIndexService {

    private final GithubContentIndexRepository repository;

    public record IndexedHit(
            String connectorId,
            String sourceType,
            String repo,
            String url,
            String title,
            String body,
            Map<String, Object> metadata,
            double score
    ) {}

    @Transactional
    public void replaceConnectorCorpus(ConnectorConfig connector,
                                       List<Map.Entry<String, Map<String, Object>>> documents) {
        repository.deleteByConnectorId(connector.getId());
        if (documents == null || documents.isEmpty()) {
            return;
        }

        Map<String, GithubContentIndex> byUrl = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : documents) {
            Map<String, Object> meta = entry.getValue() != null ? new HashMap<>(entry.getValue()) : new HashMap<>();
            String sourceType = asString(meta.getOrDefault("content_type", "unknown"));
            String title = deriveTitle(entry.getKey(), sourceType);
            String body = normalizeBody(entry.getKey());
            String url = asString(meta.getOrDefault("url",
                    "https://github.com/" + asString(meta.getOrDefault("repo", "unknown"))));
            Instant sourceUpdatedAt = parseInstant(meta.get("updated_at"));
            if (url == null || url.isBlank()) {
                continue;
            }

            GithubContentIndex candidate = GithubContentIndex.builder()
                    .connectorId(connector.getId())
                    .userId(connector.getOwnerId())
                    .sourceType(sourceType)
                    .repo(asString(meta.get("repo")))
                    .url(url)
                    .title(title)
                    .body(body)
                    .metadata(meta)
                    .sourceUpdatedAt(sourceUpdatedAt)
                    .build();

            GithubContentIndex existing = byUrl.get(url);
            if (existing == null || shouldReplace(existing, candidate)) {
                byUrl.put(url, candidate);
            }
        }
        List<GithubContentIndex> entities = new ArrayList<>(byUrl.values());
        repository.saveAll(entities);
        log.info("GitHub index refreshed for connector={} inputDocs={} uniqueDocs={}",
                connector.getId(), documents.size(), entities.size());
    }

    @Transactional(readOnly = true)
    public List<IndexedHit> search(String userId, List<String> connectorIds, String query, int limit) {
        if (connectorIds == null || connectorIds.isEmpty()) {
            return List.of();
        }

        List<GithubContentIndex> docs = repository.findByUserIdAndConnectorIdIn(userId, connectorIds);
        String q = query == null ? "" : query.toLowerCase();
        return docs.stream()
                .map(doc -> new IndexedHit(
                        doc.getConnectorId(),
                        doc.getSourceType(),
                        doc.getRepo(),
                        doc.getUrl(),
                        doc.getTitle(),
                        doc.getBody(),
                        doc.getMetadata(),
                        score(doc, q)
                ))
                .filter(hit -> hit.score() > 0)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long countDistinctRepos(String userId, List<String> connectorIds) {
        if (connectorIds == null || connectorIds.isEmpty()) {
            return 0;
        }
        return repository.findByUserIdAndConnectorIdIn(userId, connectorIds).stream()
                .map(GithubContentIndex::getRepo)
                .filter(r -> r != null && !r.isEmpty())
                .distinct()
                .count();
    }

    private double score(GithubContentIndex doc, String q) {
        if (q == null || q.isBlank()) {
            return 0;
        }
        String title = doc.getTitle() == null ? "" : doc.getTitle().toLowerCase();
        String body = doc.getBody() == null ? "" : doc.getBody().toLowerCase();
        String repo = doc.getRepo() == null ? "" : doc.getRepo().toLowerCase();

        double score = 0;
        if (title.contains(q)) score += 5.0;
        if (repo.contains(q)) score += 4.0;
        if (body.contains(q)) score += 3.0;

        String[] tokens = q.split("\\s+");
        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (title.contains(token)) score += 1.5;
            if (repo.contains(token)) score += 1.2;
            if (body.contains(token)) score += 0.8;
        }
        return score;
    }

    private String deriveTitle(String body, String sourceType) {
        if (body == null || body.isBlank()) {
            return sourceType.toUpperCase() + " item";
        }
        String firstLine = body.lines().findFirst().orElse(body).trim();
        if (firstLine.length() > 180) {
            return firstLine.substring(0, 180);
        }
        return firstLine;
    }

    private String normalizeBody(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().replace("\u0000", "");
        return cleaned.length() > 16000 ? cleaned.substring(0, 16000) : cleaned;
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean shouldReplace(GithubContentIndex current, GithubContentIndex candidate) {
        Instant currentTs = current.getSourceUpdatedAt();
        Instant candidateTs = candidate.getSourceUpdatedAt();
        if (currentTs == null && candidateTs != null) {
            return true;
        }
        if (currentTs != null && candidateTs != null && candidateTs.isAfter(currentTs)) {
            return true;
        }
        return candidate.getBody() != null
                && current.getBody() != null
                && candidate.getBody().length() > current.getBody().length();
    }
}
