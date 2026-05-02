package com.bank.aiassistant.service.agent.tools;

import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.service.connector.ConnectorCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct agent tool for real-time GitHub API queries.
 *
 * Supported operations: get_commits, get_branches, get_pull_requests,
 * get_issues, get_workflows, get_workflow_runs, search_code, get_user_info, list_repos
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubApiTool implements AgentTool {

    private final ConnectorConfigRepository connectorConfigRepository;
    private final ConnectorCredentialService credentialService;
    private final ObjectMapper objectMapper;

    private static final String GITHUB_API = "https://api.github.com";
    private static final int MAX_IN_MEMORY = 4 * 1024 * 1024;

    @Override
    public String getName() { return "github_api"; }

    @Override
    public String getDescription() {
        return "Calls the GitHub REST API to fetch real-time data from connected GitHub accounts. " +
               "Use for: latest commits, branch list, open/closed pull requests, issues, " +
               "GitHub Actions workflow runs, code search, and repository details. " +
               "Always prefer this tool when the user asks about commits, branches, workflows, or recent activity.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "connector_id": {"type":"string","description":"GitHub connector ID to use (provided in context)"},
                  "operation":    {"type":"string","description":"One of: get_commits, get_branches, get_pull_requests, get_issues, get_workflows, get_workflow_runs, search_code, get_user_info, list_repos, get_repo, get_file_tree, get_repo_stats"},
                  "repo":         {"type":"string","description":"Repository full name, e.g. owner/repo (required for most operations)"},
                  "branch":       {"type":"string","description":"Branch name (for get_commits and get_file_tree, default: repo default branch)"},
                  "state":        {"type":"string","description":"For PRs/issues: open, closed, or all (default: open)"},
                  "query":        {"type":"string","description":"Search query (for search_code)"},
                  "workflow_id":  {"type":"string","description":"Workflow file name or ID (for get_workflow_runs)"},
                  "limit":        {"type":"integer","description":"Max results to return (default: 10, max: 30)"}
                },"required":["connector_id","operation"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String connectorId = params.path("connector_id").asText(null);
        String operation   = params.path("operation").asText(null);
        String repo        = params.path("repo").asText(null);
        String branch      = params.path("branch").asText(null);
        String state       = params.path("state").asText("open");
        String query       = params.path("query").asText(null);
        String workflowId  = params.path("workflow_id").asText(null);
        int limit          = Math.min(params.path("limit").asInt(10), 30);

        if (connectorId == null || operation == null) {
            return ToolResult.error("connector_id and operation are required");
        }

        try {
            WebClient client = buildClient(connectorId);
            return switch (operation) {
                case "get_commits"      -> getCommits(client, repo, branch, limit);
                case "get_branches"     -> getBranches(client, repo, limit);
                case "get_pull_requests"-> getPullRequests(client, repo, state, limit);
                case "get_issues"       -> getIssues(client, repo, state, limit);
                case "get_workflows"    -> getWorkflows(client, repo);
                case "get_workflow_runs"-> getWorkflowRuns(client, repo, workflowId, limit);
                case "search_code"      -> searchCode(client, query, repo, limit);
                case "get_user_info"    -> getUserInfo(client);
                case "list_repos"       -> listRepos(client, limit);
                case "get_repo"         -> getRepo(client, repo);
                case "get_file_tree"    -> getFileTree(client, repo, branch);
                case "get_repo_stats"   -> getRepoStats(client, repo, branch);
                default -> ToolResult.error("Unknown operation: " + operation +
                    ". Valid: get_commits, get_branches, get_pull_requests, get_issues, " +
                    "get_workflows, get_workflow_runs, search_code, get_user_info, list_repos, " +
                    "get_repo, get_file_tree, get_repo_stats");
            };
        } catch (Exception ex) {
            log.error("GitHubApiTool error op={} connector={}: {}", operation, connectorId, ex.getMessage());
            return ToolResult.error("GitHub API call failed: " + ex.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Operations
    // ──────────────────────────────────────────────────────────────────────────

    private ToolResult getCommits(WebClient client, String repo, String branch, int limit) {
        if (repo == null) return ToolResult.error("'repo' is required for get_commits");
        String url = "/repos/" + repo + "/commits?per_page=" + limit;
        if (branch != null && !branch.isBlank()) url += "&sha=" + branch;
        JsonNode arr = parse(get(client, url));
        if (!arr.isArray() || arr.isEmpty()) {
            return ToolResult.ok("No commits found in repository " + repo);
        }
        StringBuilder sb = new StringBuilder("**Recent commits in " + repo + "**\n\n");
        arr.forEach(commit -> {
            String sha     = commit.path("sha").asText("").substring(0, Math.min(7, commit.path("sha").asText("").length()));
            String message = commit.path("commit").path("message").asText("(no message)").lines().findFirst().orElse("").trim();
            String author  = commit.path("commit").path("author").path("name").asText("unknown");
            String date    = commit.path("commit").path("author").path("date").asText("");
            String htmlUrl = commit.path("html_url").asText("");
            sb.append(String.format("- `%s` **%s** by %s on %s\n  %s\n", sha, message, author, date, htmlUrl));
        });
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getBranches(WebClient client, String repo, int limit) {
        if (repo == null) return ToolResult.error("'repo' is required for get_branches");
        JsonNode arr = parse(get(client, "/repos/" + repo + "/branches?per_page=" + limit));
        if (!arr.isArray() || arr.isEmpty()) return ToolResult.ok("No branches found in " + repo);
        StringBuilder sb = new StringBuilder("**Branches in " + repo + "**\n\n");
        arr.forEach(b -> sb.append("- ").append(b.path("name").asText()).append(
                b.path("protected").asBoolean() ? " (protected)" : "").append("\n"));
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getPullRequests(WebClient client, String repo, String state, int limit) {
        if (repo == null) return ToolResult.error("'repo' is required for get_pull_requests");
        JsonNode arr = parse(get(client, "/repos/" + repo + "/pulls?state=" + state + "&per_page=" + limit + "&sort=updated&direction=desc"));
        if (!arr.isArray() || arr.isEmpty()) return ToolResult.ok("No " + state + " pull requests in " + repo);
        StringBuilder sb = new StringBuilder("**Pull Requests (" + state + ") in " + repo + "**\n\n");
        arr.forEach(pr -> {
            String num    = pr.path("number").asText();
            String title  = pr.path("title").asText();
            String author = pr.path("user").path("login").asText("unknown");
            String url    = pr.path("html_url").asText();
            String base   = pr.path("base").path("ref").asText();
            String head   = pr.path("head").path("ref").asText();
            String updated = pr.path("updated_at").asText("");
            sb.append(String.format("- PR #%s: **%s**\n  Author: %s | %s → %s | Updated: %s\n  %s\n",
                    num, title, author, head, base, updated, url));
        });
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getIssues(WebClient client, String repo, String state, int limit) {
        if (repo == null) return ToolResult.error("'repo' is required for get_issues");
        JsonNode arr = parse(get(client, "/repos/" + repo + "/issues?state=" + state + "&per_page=" + limit
                + "&sort=updated&direction=desc&filter=all"));
        if (!arr.isArray() || arr.isEmpty()) return ToolResult.ok("No " + state + " issues in " + repo);
        StringBuilder sb = new StringBuilder("**Issues (" + state + ") in " + repo + "**\n\n");
        arr.forEach(issue -> {
            if (issue.has("pull_request")) return;
            String num    = issue.path("number").asText();
            String title  = issue.path("title").asText();
            String author = issue.path("user").path("login").asText("unknown");
            String url    = issue.path("html_url").asText();
            sb.append(String.format("- Issue #%s: **%s** (by %s)\n  %s\n", num, title, author, url));
        });
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getWorkflows(WebClient client, String repo) {
        if (repo == null) return ToolResult.error("'repo' is required for get_workflows");
        JsonNode data = parse(get(client, "/repos/" + repo + "/actions/workflows"));
        JsonNode workflows = data.path("workflows");
        if (!workflows.isArray() || workflows.isEmpty()) return ToolResult.ok("No workflows found in " + repo);
        StringBuilder sb = new StringBuilder("**GitHub Actions Workflows in " + repo + "**\n\n");
        workflows.forEach(wf -> sb.append(String.format("- **%s** (id: %s, state: %s)\n  File: %s\n",
                wf.path("name").asText(), wf.path("id").asText(),
                wf.path("state").asText(), wf.path("path").asText())));
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getWorkflowRuns(WebClient client, String repo, String workflowId, int limit) {
        if (repo == null) return ToolResult.error("'repo' is required for get_workflow_runs");
        String url = workflowId != null && !workflowId.isBlank()
                ? "/repos/" + repo + "/actions/workflows/" + workflowId + "/runs?per_page=" + limit
                : "/repos/" + repo + "/actions/runs?per_page=" + limit;
        JsonNode data = parse(get(client, url));
        JsonNode runs = data.path("workflow_runs");
        if (!runs.isArray() || runs.isEmpty()) return ToolResult.ok("No workflow runs found in " + repo);
        StringBuilder sb = new StringBuilder("**Workflow Runs in " + repo + "**\n\n");
        runs.forEach(run -> sb.append(String.format("- **%s** | Status: %s | Conclusion: %s | Branch: %s | %s\n",
                run.path("name").asText(),
                run.path("status").asText(),
                run.path("conclusion").asText("in_progress"),
                run.path("head_branch").asText(),
                run.path("html_url").asText())));
        return ToolResult.ok(sb.toString());
    }

    private ToolResult searchCode(WebClient client, String query, String repo, int limit) {
        if (query == null || query.isBlank()) return ToolResult.error("'query' is required for search_code");
        String q = query + (repo != null ? " repo:" + repo : "");
        JsonNode data = parse(get(client, "/search/code?q=" + encode(q) + "&per_page=" + limit));
        JsonNode items = data.path("items");
        if (!items.isArray() || items.isEmpty()) return ToolResult.ok("No code results found for: " + query);
        StringBuilder sb = new StringBuilder("**Code search results for \"" + query + "\"**\n\n");
        items.forEach(item -> sb.append(String.format("- `%s` in **%s**\n  %s\n",
                item.path("path").asText(),
                item.path("repository").path("full_name").asText(),
                item.path("html_url").asText())));
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getUserInfo(WebClient client) {
        JsonNode user = parse(get(client, "/user"));
        String result = String.format(
                "**GitHub User**: %s (%s)\nPublic repos: %s | Followers: %s | Following: %s\nProfile: %s",
                user.path("name").asText(user.path("login").asText()),
                user.path("login").asText(),
                user.path("public_repos").asText("0"),
                user.path("followers").asText("0"),
                user.path("following").asText("0"),
                user.path("html_url").asText());
        return ToolResult.ok(result);
    }

    private ToolResult listRepos(WebClient client, int limit) {
        JsonNode arr = parse(get(client, "/user/repos?sort=pushed&per_page=" + limit
                + "&affiliation=owner,collaborator,organization_member"));
        if (!arr.isArray() || arr.isEmpty()) return ToolResult.ok("No repositories found.");
        StringBuilder sb = new StringBuilder("**Your GitHub Repositories**\n\n");
        List<String> repos = new ArrayList<>();
        arr.forEach(r -> repos.add(String.format("- **%s** (%s) | %s stars | %s open issues | %s\n",
                r.path("full_name").asText(),
                r.path("language").asText("?"),
                r.path("stargazers_count").asText("0"),
                r.path("open_issues_count").asText("0"),
                r.path("html_url").asText())));
        repos.forEach(sb::append);
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getRepo(WebClient client, String repo) {
        if (repo == null) return ToolResult.error("'repo' is required for get_repo");
        JsonNode r = parse(get(client, "/repos/" + repo));
        String result = String.format(
                "**Repository: %s**\nDescription: %s\nLanguage: %s | Stars: %s | Forks: %s | Open Issues: %s\n" +
                "Default Branch: %s | Visibility: %s\nURL: %s",
                r.path("full_name").asText(),
                r.path("description").asText("No description"),
                r.path("language").asText("Unknown"),
                r.path("stargazers_count").asText("0"),
                r.path("forks_count").asText("0"),
                r.path("open_issues_count").asText("0"),
                r.path("default_branch").asText("main"),
                r.path("visibility").asText("public"),
                r.path("html_url").asText());
        return ToolResult.ok(result);
    }

    private ToolResult getFileTree(WebClient client, String repo, String branch) {
        if (repo == null) return ToolResult.error("'repo' is required for get_file_tree");
        try {
            // Resolve default branch if not provided
            String ref = branch;
            if (ref == null || ref.isBlank()) {
                JsonNode repoInfo = parse(get(client, "/repos/" + repo));
                ref = repoInfo.path("default_branch").asText("main");
            }
            JsonNode tree = parse(get(client, "/repos/" + repo + "/git/trees/" + ref + "?recursive=1"));
            JsonNode items = tree.path("tree");
            if (!items.isArray() || items.isEmpty()) {
                return ToolResult.ok("No files found in repository " + repo);
            }
            boolean truncated = tree.path("truncated").asBoolean(false);
            StringBuilder sb = new StringBuilder();
            sb.append("**File tree for ").append(repo).append(" (branch: ").append(ref).append(")**\n\n");
            sb.append("| File Path | Type | Size (bytes) |\n");
            sb.append("|-----------|------|--------------|\n");
            int fileCount = 0;
            long totalSize = 0;
            for (JsonNode item : items) {
                String itemType = item.path("type").asText();
                if (!"blob".equals(itemType)) continue; // files only, skip trees/dirs
                String path = item.path("path").asText();
                long size = item.path("size").asLong(0);
                totalSize += size;
                fileCount++;
                sb.append(String.format("| %s | file | %,d |\n", path, size));
            }
            sb.append(String.format("\n**Total: %d files, %,d bytes (%.1f KB)**",
                    fileCount, totalSize, totalSize / 1024.0));
            if (truncated) sb.append("\n*(Tree was truncated by GitHub — repository has more files)*");
            return ToolResult.ok(sb.toString());
        } catch (Exception ex) {
            return ToolResult.error("Failed to get file tree: " + ex.getMessage());
        }
    }

    private ToolResult getRepoStats(WebClient client, String repo, String branch) {
        if (repo == null) return ToolResult.error("'repo' is required for get_repo_stats");
        try {
            String ref = branch;
            if (ref == null || ref.isBlank()) {
                JsonNode repoInfo = parse(get(client, "/repos/" + repo));
                ref = repoInfo.path("default_branch").asText("main");
            }
            JsonNode tree = parse(get(client, "/repos/" + repo + "/git/trees/" + ref + "?recursive=1"));
            JsonNode items = tree.path("tree");

            java.util.Map<String, long[]> byExt = new java.util.TreeMap<>();
            long totalFiles = 0, totalSize = 0;
            for (JsonNode item : items) {
                if (!"blob".equals(item.path("type").asText())) continue;
                String path = item.path("path").asText();
                long size = item.path("size").asLong(0);
                int dot = path.lastIndexOf('.');
                String ext = (dot >= 0 && dot < path.length() - 1)
                        ? path.substring(dot + 1).toLowerCase() : "no-extension";
                byExt.computeIfAbsent(ext, k -> new long[2]);
                byExt.get(ext)[0]++;
                byExt.get(ext)[1] += size;
                totalFiles++;
                totalSize += size;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("**Repository Statistics: ").append(repo).append("**\n\n");
            sb.append(String.format("- Total files: **%,d**\n", totalFiles));
            sb.append(String.format("- Total size: **%,d bytes (%.1f KB / %.2f MB)**\n\n",
                    totalSize, totalSize / 1024.0, totalSize / (1024.0 * 1024)));
            sb.append("**By file extension:**\n\n");
            sb.append("| Extension | Files | Total Size |\n");
            sb.append("|-----------|-------|------------|\n");
            byExt.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .forEach(e -> sb.append(String.format("| .%s | %,d | %,d bytes |\n",
                            e.getKey(), e.getValue()[0], e.getValue()[1])));
            return ToolResult.ok(sb.toString());
        } catch (Exception ex) {
            return ToolResult.error("Failed to get repo stats: " + ex.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private WebClient buildClient(String connectorId) {
        var config = connectorConfigRepository.findById(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found: " + connectorId));
        var creds = credentialService.decrypt(config.getEncryptedCredentials());
        String token = creds.getOrDefault("accessToken", creds.getOrDefault("personalAccessToken", ""));
        return WebClient.builder()
                .baseUrl(GITHUB_API)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
                        .build())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private String get(WebClient client, String path) {
        return client.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(20));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
