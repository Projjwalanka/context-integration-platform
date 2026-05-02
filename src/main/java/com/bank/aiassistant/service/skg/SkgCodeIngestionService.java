package com.bank.aiassistant.service.skg;

import com.bank.aiassistant.model.entity.ConnectorConfig;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.service.connector.ConnectorRegistry;
import com.bank.aiassistant.service.connector.github.GitHubConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

/**
 * Ingests source code repositories into the System Knowledge Graph.
 *
 * Strategy:
 *  1. Connect to a CODE/GITHUB connector via ConnectorRegistry.fetchAll
 *  2. Group files by top-level package → Service nodes
 *  3. Sub-packages → Module nodes
 *  4. Detect Spring stereotypes (@RestController, @Service, @Repository) → Component nodes
 *  5. Extract @*Mapping annotations → Api nodes
 *  6. Detect @Repository usage → Database relationships
 *  7. Infer DEPENDS_ON from @Autowired patterns
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkgCodeIngestionService {

    private final SystemKnowledgeGraphService skg;
    private final ConnectorRegistry connectorRegistry;
    private final IngestionStatusService statusService;
    private final ConnectorConfigRepository connectorConfigRepository;
    private final GitHubConnector gitHubConnector;

    @Async
    public void ingestAsync(String tenantId, String connectorId) {
        ingest(tenantId, connectorId);
    }

    public IngestionStatusService.IngestionSummary ingest(String tenantId, String connectorId) {
        statusService.markRunning(tenantId, "CODE", connectorId);
        int created = 0, edges = 0;
        try {
            // ── 1) Source code files (real tree + content from GitHub) ──────
            ConnectorConfig config = connectorConfigRepository.findById(connectorId).orElse(null);
            List<Map.Entry<String, Map<String, Object>>> sourceFiles = config != null
                    ? gitHubConnector.fetchSourceFiles(config)
                    : List.of();

            // ── 2) Issues / PRs / commits / READMEs / repo summaries ────────
            List<Map.Entry<String, Map<String, Object>>> liveEntries =
                    connectorRegistry.fetchAll(connectorId);

            log.info("CODE ingest: {} source files, {} metadata entries (connector={})",
                    sourceFiles.size(), liveEntries.size(), connectorId);

            // Group source files by repo so each repo becomes its own Service subtree
            Map<String, List<Map<String, Object>>> filesByRepo = new LinkedHashMap<>();
            for (var e : sourceFiles) {
                Map<String, Object> f = new HashMap<>(e.getValue());
                f.put("content", e.getKey());
                String repo = String.valueOf(f.getOrDefault("repo", "unknown"));
                filesByRepo.computeIfAbsent(repo, k -> new ArrayList<>()).add(f);
            }

            // Also collect repos seen via metadata (so empty repos still appear)
            for (var e : liveEntries) {
                Object repo = e.getValue().get("repo");
                if (repo != null) filesByRepo.putIfAbsent(repo.toString(), new ArrayList<>());
            }

            if (filesByRepo.isEmpty()) {
                filesByRepo.put(connectorId, new ArrayList<>()); // ensure at least one Service node
            }

            for (var repoEntry : filesByRepo.entrySet()) {
                String repoName = repoEntry.getKey();
                List<Map<String, Object>> files = repoEntry.getValue();

                String repoNodeId = tenantId + ":Repository_Node:" + sanitize(repoName);
                skg.upsertNode(tenantId, repoNodeId, "Repository_Node", repoName,
                        "GitHub repository " + repoName,
                        Map.of("connectorId", connectorId, "repo", repoName));
                created++;

                // Service node = the repo itself
                String serviceId = nodeId(tenantId, "Service", repoName);
                skg.upsertNode(tenantId, serviceId, "Service", shortRepoName(repoName),
                        "Service derived from repository " + repoName,
                        Map.of("repo", repoName, "fileCount", files.size()));
                skg.upsertEdge(tenantId, repoNodeId, serviceId, "CONTAINS", Map.of());
                created++; edges++;

                // Group within this repo by top package directory (e.g. src/main/java/com/...)
                Map<String, List<Map<String, Object>>> byModule = groupByModule(files);
                for (var modEntry : byModule.entrySet()) {
                    String moduleName = modEntry.getKey();
                    String moduleId = nodeId(tenantId, "Module", repoName + "/" + moduleName);
                    skg.upsertNode(tenantId, moduleId, "Module", moduleName,
                            "Module within " + repoName,
                            Map.of("repo", repoName, "fileCount", modEntry.getValue().size()));
                    skg.upsertEdge(tenantId, serviceId, moduleId, "CONTAINS", Map.of());
                    created++; edges++;

                    int[] r = extractCodeNodes(tenantId, serviceId, moduleId, modEntry.getValue());
                    created += r[0]; edges += r[1];
                }

                // Map metadata entries (commits/issues/PRs/readmes) for this repo
                int[] m = ingestRepoMetadata(tenantId, repoNodeId, serviceId, repoName, liveEntries);
                created += m[0]; edges += m[1];
            }

            // Derive DEPENDS_ON edges from @Autowired patterns (across all source files)
            List<Map<String, Object>> allFiles = filesByRepo.values().stream()
                    .flatMap(List::stream).toList();
            edges += deriveCallRelationships(tenantId, allFiles);

            statusService.markCompleted(tenantId, "CODE", connectorId, created, edges,
                    "Built graph from " + sourceFiles.size() + " source files + "
                            + liveEntries.size() + " metadata entries");
            return new IngestionStatusService.IngestionSummary(created, edges, List.of());

        } catch (Exception e) {
            log.error("Code ingestion failed for {}: {}", connectorId, e.getMessage(), e);
            statusService.markFailed(tenantId, "CODE", connectorId, e.getMessage());
            return new IngestionStatusService.IngestionSummary(created, edges, List.of(e.getMessage()));
        }
    }

    /** Build Document/Story/Bug/Task nodes from issues/PRs/commits/readmes for one repo. */
    private int[] ingestRepoMetadata(String tenantId, String repoNodeId, String serviceId,
                                      String repoName,
                                      List<Map.Entry<String, Map<String, Object>>> entries) {
        int created = 0, edges = 0;
        for (var e : entries) {
            Map<String, Object> meta = e.getValue();
            if (!repoName.equals(String.valueOf(meta.get("repo")))) continue;
            String contentType = String.valueOf(meta.getOrDefault("content_type", ""));
            String content     = e.getKey();
            String url         = String.valueOf(meta.getOrDefault("url", ""));
            String number      = String.valueOf(meta.getOrDefault("number", ""));
            String firstLine   = content == null ? "" :
                    content.lines().findFirst().orElse("").trim();

            switch (contentType) {
                case "readme" -> {
                    String docId = nodeId(tenantId, "Document", repoName + "/README");
                    skg.upsertNode(tenantId, docId, "Document", "README — " + shortRepoName(repoName),
                            truncate(firstLine, 200),
                            Map.of("repo", repoName, "url", url));
                    skg.upsertEdge(tenantId, docId, serviceId, "DESCRIBES", Map.of());
                    created++; edges++;
                }
                case "issue" -> {
                    boolean isBug = content.toLowerCase().contains("bug")
                            || content.toLowerCase().contains("[bug]");
                    String type = isBug ? "Bug" : "Story";
                    String id = nodeId(tenantId, type, repoName + number);
                    skg.upsertNode(tenantId, id, type, type + " " + number,
                            truncate(firstLine, 200),
                            Map.of("repo", repoName, "number", number, "url", url));
                    skg.upsertEdge(tenantId, id, serviceId, isBug ? "AFFECTS" : "IMPLEMENTS", Map.of());
                    created++; edges++;
                }
                case "pull_request" -> {
                    String id = nodeId(tenantId, "Task", repoName + "PR" + number);
                    skg.upsertNode(tenantId, id, "Task", "PR " + number,
                            truncate(firstLine, 200),
                            Map.of("repo", repoName, "number", number, "url", url));
                    skg.upsertEdge(tenantId, id, serviceId, "MODIFIES", Map.of());
                    created++; edges++;
                }
                case "commit" -> {
                    String sha = String.valueOf(meta.getOrDefault("sha", ""));
                    if (sha.isBlank()) break;
                    String id = nodeId(tenantId, "Task", repoName + "commit" + sha);
                    skg.upsertNode(tenantId, id, "Task", "Commit " + sha,
                            truncate(firstLine, 200),
                            Map.of("repo", repoName, "sha", sha, "url", url));
                    skg.upsertEdge(tenantId, id, serviceId, "MODIFIES", Map.of());
                    created++; edges++;
                }
                default -> {}
            }
        }
        return new int[]{ created, edges };
    }

    private Map<String, List<Map<String, Object>>> groupByModule(List<Map<String, Object>> files) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : files) {
            String path = String.valueOf(f.getOrDefault("path", ""));
            String module = inferModuleName(path);
            groups.computeIfAbsent(module, k -> new ArrayList<>()).add(f);
        }
        return groups;
    }

    /** e.g. "src/main/java/com/bank/aiassistant/controller/Foo.java" → "controller" */
    private String inferModuleName(String path) {
        if (path == null || path.isBlank()) return "root";
        String[] parts = path.split("/");
        // Prefer the directory immediately before the file
        if (parts.length >= 2) {
            String dir = parts[parts.length - 2];
            if (!dir.isBlank()) return dir;
        }
        return parts.length > 0 ? parts[0] : "root";
    }

    private String shortRepoName(String fullName) {
        int slash = fullName.lastIndexOf('/');
        return slash >= 0 ? fullName.substring(slash + 1) : fullName;
    }

    private String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9._/-]", "_");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node extraction
    // ─────────────────────────────────────────────────────────────────────────

    private int[] extractCodeNodes(String tenantId, String serviceId, String moduleId,
                                    List<Map<String, Object>> files) {
        int created = 0, edges = 0;
        for (Map<String, Object> file : files) {
            String path    = String.valueOf(file.getOrDefault("path", file.getOrDefault("name", "")));
            String content = String.valueOf(file.getOrDefault("content", ""));
            if (content.isBlank() || content.length() < 30) continue;

            String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            String className = filename.replace(".java", "").replace(".py", "").replace(".ts", "");

            if (isController(content)) {
                String compId = nodeId(tenantId, "Component", path);
                skg.upsertNode(tenantId, compId, "Component", className,
                        "REST Controller", Map.of("path", path, "stereotype", "Controller"));
                skg.upsertEdge(tenantId, moduleId, compId, "CONTAINS", Map.of());
                created++; edges++;
                int[] api = extractApiNodes(tenantId, compId, serviceId, content, path);
                created += api[0]; edges += api[1];

            } else if (isService(content)) {
                String compId = nodeId(tenantId, "Component", path);
                skg.upsertNode(tenantId, compId, "Component", className,
                        "Service component", Map.of("path", path, "stereotype", "Service"));
                skg.upsertEdge(tenantId, moduleId, compId, "CONTAINS", Map.of());
                created++; edges++;

            } else if (isRepository(content)) {
                String compId = nodeId(tenantId, "Component", path);
                skg.upsertNode(tenantId, compId, "Component", className,
                        "Repository", Map.of("path", path, "stereotype", "Repository"));
                skg.upsertEdge(tenantId, moduleId, compId, "CONTAINS", Map.of());
                created++; edges++;

                String dbName = inferDatabaseName(content);
                if (dbName != null) {
                    String dbId = nodeId(tenantId, "Database", dbName);
                    skg.upsertNode(tenantId, dbId, "Database", dbName,
                            "Database inferred from repository", Map.of());
                    skg.upsertEdge(tenantId, serviceId, dbId, "USES_DB", Map.of());
                    created++; edges++;
                }

            } else if (path.endsWith(".java") || path.endsWith(".ts") || path.endsWith(".py")) {
                String classId = nodeId(tenantId, "Class", path);
                skg.upsertNode(tenantId, classId, "Class", className,
                        "Source class", Map.of("path", path));
                skg.upsertEdge(tenantId, moduleId, classId, "CONTAINS", Map.of());
                created++; edges++;
            }
        }
        return new int[]{ created, edges };
    }

    private int[] extractApiNodes(String tenantId, String controllerId, String serviceId,
                                   String content, String filePath) {
        int created = 0, edges = 0;
        Pattern p = Pattern.compile(
                "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*(?:\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)[\"'])?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(content);
        while (m.find()) {
            String method = m.group(1).toUpperCase();
            String path   = m.group(2) != null ? m.group(2) : "/";
            String name   = method + " " + path;
            String apiId  = nodeId(tenantId, "Api", filePath + "|" + name);
            skg.upsertNode(tenantId, apiId, "Api", name,
                    method + " endpoint at " + path,
                    Map.of("httpMethod", method, "path", path));
            skg.upsertEdge(tenantId, apiId, serviceId, "EXPOSED_BY", Map.of());
            skg.upsertEdge(tenantId, controllerId, apiId, "CONTAINS", Map.of());
            created++; edges += 2;
        }
        return new int[]{ created, edges };
    }

    private int deriveCallRelationships(String tenantId, List<Map<String, Object>> files) {
        int edges = 0;
        for (Map<String, Object> file : files) {
            String path    = String.valueOf(file.getOrDefault("path", ""));
            String content = String.valueOf(file.getOrDefault("content", ""));
            if (!path.endsWith(".java") || content.isBlank()) continue;

            String sourceId = nodeId(tenantId, "Component", path);
            Pattern inject = Pattern.compile("(?:@Autowired|private final)\\s+([A-Z][a-zA-Z]+)\\s+\\w+");
            Matcher m = inject.matcher(content);
            while (m.find()) {
                String dep = m.group(1);
                skg.searchNodes(tenantId, dep, "Component").stream()
                        .filter(n -> !n.id().equals(sourceId))
                        .findFirst()
                        .ifPresent(n -> skg.upsertEdge(tenantId, sourceId, n.id(), "DEPENDS_ON", Map.of()));
                edges++;
            }
        }
        return edges;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Grouping utilities
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, List<Map<String, Object>>> groupByTopDir(List<Map<String, Object>> files) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : files) {
            String path  = String.valueOf(f.getOrDefault("path", ""));
            String[] pts = path.split("/");
            String top   = pts.length > 0 && !pts[0].isBlank() ? pts[0] : "root";
            groups.computeIfAbsent(top, k -> new ArrayList<>()).add(f);
        }
        return groups;
    }

    private Map<String, List<Map<String, Object>>> groupBySubDir(List<Map<String, Object>> files) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : files) {
            String path  = String.valueOf(f.getOrDefault("path", ""));
            String[] pts = path.split("/");
            String sub   = pts.length > 1 ? pts[1] : "root";
            groups.computeIfAbsent(sub, k -> new ArrayList<>()).add(f);
        }
        return groups;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detection helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isController(String c) { return c.contains("@RestController") || c.contains("@Controller"); }
    private boolean isService(String c)    { return c.contains("@Service") && !c.contains("@RestController"); }
    private boolean isRepository(String c) { return c.contains("@Repository") || c.contains("JpaRepository")
            || c.contains("MongoRepository") || c.contains("CrudRepository"); }

    private String inferDatabaseName(String content) {
        if (content.contains("MongoRepository") || content.contains("MongoTemplate")) return "MongoDB";
        if (content.contains("JpaRepository") || content.contains("@Entity"))          return "PostgreSQL";
        if (content.contains("Neo4jClient") || content.contains("Neo4jRepository"))    return "Neo4j";
        return null;
    }

    private String nodeId(String tenantId, String type, String name) {
        return tenantId + ":" + type + ":" + name.replaceAll("[^a-zA-Z0-9._/-]", "_");
    }
}
