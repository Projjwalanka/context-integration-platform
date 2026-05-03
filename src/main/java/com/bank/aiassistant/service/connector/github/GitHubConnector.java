package com.bank.aiassistant.service.connector.github;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GitHub connector that supports:
 * - account-wide batch extraction for indexing
 * - live query retrieval for issues/PRs/repos/code snippets
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubConnector implements DataSourceConnector {

    private final ConnectorCredentialService credentialService;
    private final ObjectMapper objectMapper;

    private static final String GITHUB_API = "https://api.github.com";
    private static final int ITEMS_PER_REPO = 30;
    private static final int MAX_REPOS = 30;
    private static final int MAX_REPOS_FOR_TREE = 5;
    private static final int MAX_FILES_PER_REPO = 80;
    private static final int MAX_FILE_BYTES = 200_000;
    private static final java.util.Set<String> CODE_EXTENSIONS = java.util.Set.of(
            ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".py", ".go",
            ".rb", ".cs", ".rs", ".scala", ".sql", ".yaml", ".yml");
    private static final int README_TRUNCATE = 8000;
    private static final int BODY_TRUNCATE = 3500;
    private static final int WEBCLIENT_MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    @Override
    public ConnectorType getType() {
        return ConnectorType.GITHUB;
    }

    @Override
    public boolean supportsBatchIngestion() {
        return true;
    }

    @Override
    public ConnectorHealth healthCheck(ConnectorConfig config) {
        long start = System.currentTimeMillis();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            buildClient(creds).get().uri("/user")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
            return ConnectorHealth.ok(System.currentTimeMillis() - start);
        } catch (Exception ex) {
            return ConnectorHealth.error(ex.getMessage());
        }
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> query(ConnectorConfig config,
                                                               String query,
                                                               int maxResults) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            WebClient client = buildClient(creds);
            String org = getConfigValue(config, "org");
            if (org == null || org.isBlank()) {
                org = creds.get("org");
            }
            String qualifier = (org != null && !org.isBlank()) ? " org:" + org : "";

            int issuesLimit = Math.max(2, maxResults / 2);
            int reposLimit = Math.max(2, maxResults / 4);
            int codeLimit = Math.max(1, maxResults - issuesLimit - reposLimit);

            String issuesUrl = "/search/issues?q=" + encode(query + qualifier)
                    + "&sort=updated&order=desc&per_page=" + issuesLimit;
            results.addAll(parseIssueSearchResults(get(client, issuesUrl), config.getId()));

            String reposUrl = "/search/repositories?q=" + encode(query + qualifier)
                    + "&sort=updated&order=desc&per_page=" + reposLimit;
            results.addAll(parseRepoSearchResults(get(client, reposUrl), config.getId()));

            String codeUrl = "/search/code?q=" + encode(query + " in:file" + qualifier)
                    + "&per_page=" + codeLimit;
            results.addAll(parseCodeSearchResults(get(client, codeUrl), config.getId()));

        } catch (Exception ex) {
            log.error("GitHub live query failed for connector {}: {}", config.getId(), ex.getMessage());
        }
        return results;
    }

    @Override
    public List<Map.Entry<String, Map<String, Object>>> fetchAll(ConnectorConfig config) {
        List<Map.Entry<String, Map<String, Object>>> all = new ArrayList<>();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            WebClient client = buildClient(creds);
            String org = getConfigValue(config, "org");
            if (org == null || org.isBlank()) {
                org = creds.get("org");
            }

            Map<String, String> selectedBranchByRepo = getSelectedRepoBranchMap(config);
            List<JsonNode> repos = fetchRepos(client, org);

            // If user has selected specific repos, filter to only those
            if (!selectedBranchByRepo.isEmpty()) {
                repos = repos.stream()
                        .filter(r -> selectedBranchByRepo.containsKey(r.path("full_name").asText()))
                        .collect(Collectors.toList());
            }

            log.info("GitHub fetchAll: {} repos for connector={}", repos.size(), config.getId());

            for (JsonNode repo : repos) {
                String fullName = repo.path("full_name").asText();
                String repoUrl = repo.path("html_url").asText();
                // Use user-selected branch if specified, otherwise use repo default
                String branch = selectedBranchByRepo.getOrDefault(
                        fullName, repo.path("default_branch").asText("main"));

                all.add(repoSummaryEntry(repo, config.getId()));

                String readme = fetchReadme(client, fullName);
                if (readme != null && !readme.isBlank()) {
                    String truncated = readme.length() > README_TRUNCATE
                            ? readme.substring(0, README_TRUNCATE) + "\n...(truncated)" : readme;
                    all.add(Map.entry(
                            "# README - " + fullName + "\n\n" + truncated,
                            repoMeta("readme", config.getId(), fullName,
                                    repoUrl + "/blob/" + branch + "/README.md",
                                    repo.path("updated_at").asText(null))
                    ));
                }

                fetchCommits(client, fullName, config.getId(), all);
                fetchIssues(client, fullName, config.getId(), all);
                fetchPullRequests(client, fullName, config.getId(), all);
            }

        } catch (Exception ex) {
            log.error("GitHub fetchAll failed for connector {}: {}", config.getId(), ex.getMessage(), ex);
        }
        log.info("GitHub fetchAll complete: {} documents for connector={}", all.size(), config.getId());
        return all;
    }

    /**
     * Fetches actual source-code files (tree + content) from the user's repos.
     * Returns entries shaped as {content, metadata{path, repo, ...}} so that
     * SkgCodeIngestionService can build Service / Component / Api / Class nodes
     * from real source structure (not just commit/issue metadata).
     */
    public List<Map.Entry<String, Map<String, Object>>> fetchSourceFiles(ConnectorConfig config) {
        List<Map.Entry<String, Map<String, Object>>> all = new ArrayList<>();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            WebClient client = buildClient(creds);
            String org = getConfigValue(config, "org");
            if (org == null || org.isBlank()) org = creds.get("org");

            Map<String, String> selectedBranchByRepo = getSelectedRepoBranchMap(config);
            List<JsonNode> repos = fetchRepos(client, org);

            List<JsonNode> targetRepos;
            if (!selectedBranchByRepo.isEmpty()) {
                // Only scan user-selected repos
                targetRepos = repos.stream()
                        .filter(r -> selectedBranchByRepo.containsKey(r.path("full_name").asText()))
                        .collect(Collectors.toList());
            } else {
                targetRepos = repos.subList(0, Math.min(repos.size(), MAX_REPOS_FOR_TREE));
            }

            log.info("GitHub fetchSourceFiles: scanning {} repos for connector={}",
                    targetRepos.size(), config.getId());

            for (JsonNode repo : targetRepos) {
                String fullName = repo.path("full_name").asText();
                String branch = selectedBranchByRepo.getOrDefault(
                        fullName, repo.path("default_branch").asText("main"));
                fetchRepoTree(client, fullName, branch, config.getId(), all);
            }
        } catch (Exception ex) {
            log.error("GitHub fetchSourceFiles failed for connector {}: {}",
                    config.getId(), ex.getMessage(), ex);
        }
        log.info("GitHub fetchSourceFiles: {} source files for connector={}",
                all.size(), config.getId());
        return all;
    }

    /**
     * Reads the selectedRepos config array and returns a map of
     * {@code repoFullName -> branch}. Returns an empty map if no selections are stored.
     */
    private Map<String, String> getSelectedRepoBranchMap(ConnectorConfig config) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            if (config.getConfig() == null) return result;
            JsonNode root = objectMapper.readTree(config.getConfig());
            JsonNode selected = root.path("selectedRepos");
            if (!selected.isArray()) return result;
            selected.forEach(item -> {
                String fullName = item.path("fullName").asText(null);
                String branch   = item.path("branch").asText(null);
                if (fullName != null && !fullName.isBlank()) {
                    result.put(fullName, branch != null && !branch.isBlank() ? branch : "main");
                }
            });
        } catch (Exception e) {
            log.debug("Could not parse selectedRepos from connector config: {}", e.getMessage());
        }
        return result;
    }

    private void fetchRepoTree(WebClient client, String repoFullName, String branch,
                                String connectorId,
                                List<Map.Entry<String, Map<String, Object>>> out) {
        try {
            String treeUrl = "/repos/" + repoFullName + "/git/trees/" + branch + "?recursive=1";
            JsonNode root = objectMapper.readTree(get(client, treeUrl));
            JsonNode tree = root.path("tree");
            if (!tree.isArray()) return;

            int fetched = 0;
            for (JsonNode entry : tree) {
                if (fetched >= MAX_FILES_PER_REPO) break;
                if (!"blob".equals(entry.path("type").asText())) continue;
                long size = entry.path("size").asLong(0);
                if (size <= 0 || size > MAX_FILE_BYTES) continue;

                String path = entry.path("path").asText("");
                if (path.isBlank() || !hasCodeExtension(path)) continue;

                String content = fetchFileContent(client, repoFullName, branch, path);
                if (content == null || content.isBlank()) continue;

                Map<String, Object> meta = new java.util.HashMap<>();
                meta.put("source_type", "GITHUB");
                meta.put("connector_id", connectorId);
                meta.put("content_type", "source_file");
                meta.put("repo", repoFullName);
                meta.put("path", path);
                meta.put("branch", branch);
                meta.put("url", "https://github.com/" + repoFullName + "/blob/" + branch + "/" + path);
                out.add(Map.entry(content, meta));
                fetched++;
            }
            log.debug("Fetched {} source files from {}", fetched, repoFullName);
        } catch (Exception e) {
            log.warn("Failed to fetch tree for {}: {}", repoFullName, e.getMessage());
        }
    }

    private String fetchFileContent(WebClient client, String repoFullName,
                                     String branch, String path) {
        try {
            String url = "/repos/" + repoFullName + "/contents/" + path + "?ref=" + branch;
            JsonNode meta = objectMapper.readTree(get(client, url));
            String encoded = meta.path("content").asText("");
            String encoding = meta.path("encoding").asText("base64");
            if (encoded.isBlank()) return null;
            if ("base64".equalsIgnoreCase(encoding)) {
                byte[] decoded = java.util.Base64.getMimeDecoder().decode(encoded);
                return new String(decoded, StandardCharsets.UTF_8);
            }
            return encoded;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasCodeExtension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        return CODE_EXTENSIONS.contains(path.substring(dot).toLowerCase());
    }

    /**
     * Returns a list of repositories with their available branches for the
     * given connector. Used to populate the project/branch selector in the UI.
     */
    public List<Map<String, Object>> listReposWithBranches(ConnectorConfig config) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Map<String, String> creds = credentialService.decrypt(config.getEncryptedCredentials());
            WebClient client = buildClient(creds);
            String org = getConfigValue(config, "org");
            if (org == null || org.isBlank()) org = creds.get("org");

            List<JsonNode> repos = fetchRepos(client, org);
            for (JsonNode repo : repos) {
                String fullName     = repo.path("full_name").asText();
                String defaultBranch = repo.path("default_branch").asText("main");
                String description  = repo.path("description").asText("");
                String language     = repo.path("language").asText("");
                boolean isPrivate   = repo.path("private").asBoolean(false);

                List<String> branches = fetchBranchNames(client, fullName);

                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("fullName", fullName);
                entry.put("defaultBranch", defaultBranch);
                entry.put("description", description);
                entry.put("language", language);
                entry.put("isPrivate", isPrivate);
                entry.put("branches", branches);
                result.add(entry);
            }
        } catch (Exception ex) {
            log.error("listReposWithBranches failed for connector {}: {}", config.getId(), ex.getMessage());
        }
        return result;
    }

    private List<String> fetchBranchNames(WebClient client, String repoFullName) {
        try {
            String url = "/repos/" + repoFullName + "/branches?per_page=50";
            JsonNode arr = objectMapper.readTree(get(client, url));
            List<String> names = new ArrayList<>();
            if (arr.isArray()) arr.forEach(b -> names.add(b.path("name").asText()));
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<JsonNode> fetchRepos(WebClient client, String org) {
        try {
            String url = (org != null && !org.isBlank())
                    ? "/orgs/" + org + "/repos?type=all&sort=pushed&per_page=" + MAX_REPOS
                    : "/user/repos?sort=pushed&per_page=" + MAX_REPOS + "&affiliation=owner,collaborator,organization_member";
            JsonNode arr = objectMapper.readTree(get(client, url));
            Map<String, JsonNode> byFullName = new LinkedHashMap<>();
            if (arr.isArray()) {
                arr.forEach(repo -> {
                    String fullName = repo.path("full_name").asText(null);
                    if (fullName != null && !fullName.isBlank()) {
                        byFullName.putIfAbsent(fullName, repo);
                    }
                });
            }
            return new ArrayList<>(byFullName.values());
        } catch (Exception e) {
            log.error("Failed to fetch repos: {}", e.getMessage());
            return List.of();
        }
    }

    private String fetchReadme(WebClient client, String repoFullName) {
        try {
            JsonNode meta = objectMapper.readTree(get(client, "/repos/" + repoFullName + "/readme"));
            String downloadUrl = meta.path("download_url").asText(null);
            if (downloadUrl == null || downloadUrl.isBlank()) {
                return null;
            }
            return WebClient.builder().build().get().uri(downloadUrl)
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
        } catch (Exception e) {
            return null;
        }
    }

    private void fetchCommits(WebClient client,
                              String repoFullName,
                              String connectorId,
                              List<Map.Entry<String, Map<String, Object>>> out) {
        try {
            String url = "/repos/" + repoFullName + "/commits?per_page=20";
            JsonNode arr = objectMapper.readTree(get(client, url));
            if (!arr.isArray()) return;
            arr.forEach(commit -> {
                String sha     = commit.path("sha").asText("").substring(0,
                        Math.min(7, commit.path("sha").asText("").length()));
                String fullSha = commit.path("sha").asText("");
                String message = commit.path("commit").path("message").asText("");
                String author  = commit.path("commit").path("author").path("name").asText("unknown");
                String date    = commit.path("commit").path("author").path("date").asText("");
                String htmlUrl = commit.path("html_url").asText(
                        "https://github.com/" + repoFullName + "/commit/" + fullSha);
                String text = String.format(
                        "GitHub Commit %s - %s\nRepository: %s | Author: %s | Date: %s\n\n%s",
                        sha, message.lines().findFirst().orElse("").trim(),
                        repoFullName, author, date, truncate(message, BODY_TRUNCATE));
                Map<String, Object> meta = new java.util.HashMap<>(Map.of(
                        "source_type", "GITHUB",
                        "connector_id", connectorId,
                        "content_type", "commit",
                        "repo", repoFullName,
                        "sha", sha,
                        "url", htmlUrl
                ));
                if (!date.isEmpty()) meta.put("updated_at", date);
                out.add(Map.entry(text, meta));
            });
        } catch (Exception e) {
            log.debug("Failed to fetch commits for {}: {}", repoFullName, e.getMessage());
        }
    }

    private void fetchIssues(WebClient client,
                             String repoFullName,
                             String connectorId,
                             List<Map.Entry<String, Map<String, Object>>> out) {
        try {
            String url = "/repos/" + repoFullName + "/issues?state=all&per_page=" + ITEMS_PER_REPO
                    + "&sort=updated&direction=desc";
            JsonNode arr = objectMapper.readTree(get(client, url));
            if (!arr.isArray()) {
                return;
            }
            arr.forEach(item -> {
                if (item.has("pull_request")) {
                    return;
                }
                String number = item.path("number").asText();
                String title = item.path("title").asText();
                String issueBody = item.path("body").asText("");
                String url2 = item.path("html_url").asText();
                String state = item.path("state").asText();
                String labels = formatLabels(item.path("labels"));
                String text = String.format(
                        "GitHub Issue #%s [%s] - %s\nRepository: %s  Labels: %s\n\n%s",
                        number, state.toUpperCase(), title, repoFullName, labels,
                        truncate(issueBody, BODY_TRUNCATE)
                );
                out.add(Map.entry(text, issueMeta(
                        "issue", connectorId, repoFullName, "#" + number, url2, item.path("updated_at").asText(null)
                )));
            });
        } catch (Exception e) {
            log.debug("Failed to fetch issues for {}: {}", repoFullName, e.getMessage());
        }
    }

    private void fetchPullRequests(WebClient client,
                                   String repoFullName,
                                   String connectorId,
                                   List<Map.Entry<String, Map<String, Object>>> out) {
        try {
            String url = "/repos/" + repoFullName + "/pulls?state=all&per_page=" + ITEMS_PER_REPO
                    + "&sort=updated&direction=desc";
            JsonNode arr = objectMapper.readTree(get(client, url));
            if (!arr.isArray()) {
                return;
            }
            arr.forEach(item -> {
                String number = item.path("number").asText();
                String title = item.path("title").asText();
                String prBody = item.path("body").asText("");
                String url2 = item.path("html_url").asText();
                String state = item.path("state").asText("open");
                String headBranch = item.path("head").path("ref").asText();
                String baseBranch = item.path("base").path("ref").asText();
                String text = String.format(
                        "GitHub Pull Request #%s [%s] - %s\nRepository: %s  Branch: %s -> %s\n\n%s",
                        number, state.toUpperCase(), title, repoFullName, headBranch, baseBranch,
                        truncate(prBody, BODY_TRUNCATE)
                );
                out.add(Map.entry(text, issueMeta(
                        "pull_request", connectorId, repoFullName, "#" + number, url2, item.path("updated_at").asText(null)
                )));
            });
        } catch (Exception e) {
            log.debug("Failed to fetch PRs for {}: {}", repoFullName, e.getMessage());
        }
    }

    private Map.Entry<String, Map<String, Object>> repoSummaryEntry(JsonNode repo, String connectorId) {
        String fullName = repo.path("full_name").asText();
        String text = String.format(
                "GitHub Repository: %s\nDescription: %s\nLanguage: %s | Stars: %s | Forks: %s | Open Issues: %s\nDefault Branch: %s\nURL: %s",
                fullName,
                repo.path("description").asText("No description"),
                repo.path("language").asText("Unknown"),
                repo.path("stargazers_count").asText("0"),
                repo.path("forks_count").asText("0"),
                repo.path("open_issues_count").asText("0"),
                repo.path("default_branch").asText("main"),
                repo.path("html_url").asText()
        );
        return Map.entry(text, repoMeta(
                "repo_summary", connectorId, fullName, repo.path("html_url").asText(), repo.path("updated_at").asText(null)
        ));
    }

    private List<Map.Entry<String, Map<String, Object>>> parseIssueSearchResults(String json, String connectorId) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            JsonNode items = objectMapper.readTree(json).path("items");
            if (!items.isArray()) {
                return results;
            }
            items.forEach(item -> {
                String number = item.path("number").asText();
                String title = item.path("title").asText();
                String body = item.path("body").asText("");
                String url = item.path("html_url").asText();
                String state = item.path("state").asText();
                String repoName = item.path("repository_url").asText()
                        .replace("https://api.github.com/repos/", "");
                String contentType = item.path("pull_request").isObject() ? "pull_request" : "issue";
                String text = String.format("[GitHub %s #%s] %s\nRepo: %s | State: %s\n%s",
                        contentType.toUpperCase(), number, title, repoName, state, truncate(body, BODY_TRUNCATE));
                results.add(Map.entry(text,
                        issueMeta(contentType, connectorId, repoName, "#" + number, url, item.path("updated_at").asText(null))));
            });
        } catch (Exception ex) {
            log.debug("Failed to parse GitHub issue search response: {}", ex.getMessage());
        }
        return results;
    }

    private List<Map.Entry<String, Map<String, Object>>> parseRepoSearchResults(String json, String connectorId) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            JsonNode items = objectMapper.readTree(json).path("items");
            if (!items.isArray()) {
                return results;
            }
            items.forEach(item -> {
                String fullName = item.path("full_name").asText();
                String desc = item.path("description").asText("No description");
                String url = item.path("html_url").asText();
                String text = String.format(
                        "[GitHub REPOSITORY] %s\nDescription: %s\nLanguage: %s | Stars: %s | Updated: %s",
                        fullName,
                        desc,
                        item.path("language").asText("Unknown"),
                        item.path("stargazers_count").asText("0"),
                        item.path("updated_at").asText("")
                );
                results.add(Map.entry(text, repoMeta(
                        "repo_summary", connectorId, fullName, url, item.path("updated_at").asText(null)
                )));
            });
        } catch (Exception ex) {
            log.debug("Failed to parse GitHub repository search response: {}", ex.getMessage());
        }
        return results;
    }

    private List<Map.Entry<String, Map<String, Object>>> parseCodeSearchResults(String json, String connectorId) {
        List<Map.Entry<String, Map<String, Object>>> results = new ArrayList<>();
        try {
            JsonNode items = objectMapper.readTree(json).path("items");
            if (!items.isArray()) {
                return results;
            }
            items.forEach(item -> {
                String repoName = item.path("repository").path("full_name").asText("unknown");
                String path = item.path("path").asText();
                String htmlUrl = item.path("html_url").asText();
                String text = String.format(
                        "[GitHub CODE] %s\nFile: %s\nURL: %s",
                        repoName, path, htmlUrl
                );
                results.add(Map.entry(text, Map.of(
                        "source_type", "GITHUB",
                        "connector_id", connectorId,
                        "content_type", "code",
                        "repo", repoName,
                        "url", htmlUrl,
                        "path", path
                )));
            });
        } catch (Exception ex) {
            log.debug("Code search skipped: {}", ex.getMessage());
        }
        return results;
    }

    private Map<String, Object> repoMeta(String contentType,
                                         String connectorId,
                                         String repo,
                                         String url,
                                         String updatedAt) {
        return Map.of(
                "source_type", "GITHUB",
                "connector_id", connectorId,
                "content_type", contentType,
                "repo", repo,
                "url", url,
                "updated_at", updatedAt != null ? updatedAt : ""
        );
    }

    private Map<String, Object> issueMeta(String contentType,
                                          String connectorId,
                                          String repo,
                                          String number,
                                          String url,
                                          String updatedAt) {
        return Map.of(
                "source_type", "GITHUB",
                "connector_id", connectorId,
                "content_type", contentType,
                "repo", repo,
                "number", number,
                "url", url,
                "updated_at", updatedAt != null ? updatedAt : ""
        );
    }

    private String formatLabels(JsonNode labels) {
        if (!labels.isArray() || labels.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        labels.forEach(l -> names.add(l.path("name").asText()));
        return String.join(", ", names);
    }

    private WebClient buildClient(Map<String, String> creds) {
        String token = creds.getOrDefault("accessToken", creds.getOrDefault("personalAccessToken", ""));
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(WEBCLIENT_MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(GITHUB_API)
                .exchangeStrategies(strategies)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private String getConfigValue(ConnectorConfig config, String key) {
        try {
            if (config.getConfig() != null) {
                return objectMapper.readTree(config.getConfig()).path(key).asText(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String get(WebClient client, String path) {
        return client.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(20));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }
}
