package com.bank.aiassistant.service.agent;

import com.bank.aiassistant.model.dto.chat.ChatResponse;
import com.bank.aiassistant.service.agent.tools.AgentTool;
import com.bank.aiassistant.service.llm.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ReAct (Reason + Act) agent orchestrator.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Inject tool definitions into the system prompt</li>
 *   <li>Call LLM — if response contains a tool call directive, execute the tool</li>
 *   <li>Append tool result to context and call LLM again (up to {@code MAX_ITERATIONS})</li>
 *   <li>Return final answer when no more tool calls detected</li>
 * </ol>
 *
 * <p>Tool call format (instructs the LLM via system prompt):
 * <pre>
 * TOOL_CALL: {"tool": "generate_pdf", "params": {"title": "...", "content": "..."}}
 * </pre>
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private static final int MAX_ITERATIONS = 5;
    private static final String TOOL_CALL_PREFIX = "TOOL_CALL:";

    private final LlmService llmService;
    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;
    private final String toolSystemPrompt;

    public AgentOrchestrator(LlmService llmService,
                              List<AgentTool> toolList,
                              ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
        this.tools = toolList.stream()
                .collect(Collectors.toMap(AgentTool::getName, Function.identity()));
        this.toolSystemPrompt = buildToolSystemPrompt(toolList);
        log.info("AgentOrchestrator: {} tools registered: {}", tools.size(), tools.keySet());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Blocking execution
    // ─────────────────────────────────────────────────────────────────────────

    public AgentResult run(List<Message> messages, String conversationId) {
        long start = System.currentTimeMillis();
        List<Message> context = new ArrayList<>(messages);
        List<ChatResponse.Citation> citations = new ArrayList<>();
        List<ChatResponse.GeneratedArtifact> artifacts = new ArrayList<>();
        List<String> toolCallLog = new ArrayList<>();
        String model = llmService.getModelName();

        // Inject tool capabilities into system prompt
        injectToolPrompt(context);

        String finalResponse = null;
        int iteration = 0;

        while (iteration < MAX_ITERATIONS) {
            iteration++;
            log.debug("Agent iteration {}/{} for conversation {}", iteration, MAX_ITERATIONS, conversationId);

            String llmResponse = llmService.chat(context);

            if (!containsToolCall(llmResponse)) {
                finalResponse = llmResponse;
                break;
            }

            // Extract and execute tool call
            String toolCallJson = extractToolCallJson(llmResponse);
            String textBeforeCall = extractTextBeforeCall(llmResponse);

            try {
                JsonNode parsed = objectMapper.readTree(toolCallJson);
                String toolName = parsed.path("tool").asText();
                JsonNode toolParams = parsed.path("params");

                AgentTool tool = tools.get(toolName);
                if (tool == null) {
                    context.add(new UserMessage("Tool '" + toolName + "' is not available."));
                    continue;
                }

                toolCallLog.add(toolName);
                log.info("Executing tool: {} for conversation {}", toolName, conversationId);
                AgentTool.ToolResult result = tool.execute(toolParams);

                // Collect artifacts
                if (result.artifactUrl() != null) {
                    artifacts.add(new ChatResponse.GeneratedArtifact(
                            result.artifactType(), result.artifactUrl().substring(result.artifactUrl().lastIndexOf('/') + 1),
                            result.artifactUrl()));
                }

                // Append tool result to context
                String toolFeedback = "TOOL_RESULT[" + toolName + "]: " + result.content();
                context.add(new UserMessage(toolFeedback));

            } catch (Exception ex) {
                log.error("Tool execution error: {}", ex.getMessage());
                context.add(new UserMessage("TOOL_ERROR: " + ex.getMessage()));
            }
        }

        if (finalResponse == null) {
            finalResponse = llmService.chat(context);
        }

        return new AgentResult(
                finalResponse, citations, artifacts, toolCallLog,
                model, null, null,
                System.currentTimeMillis() - start
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming (for SSE endpoints — tools run inline, final text is streamed)
    // ─────────────────────────────────────────────────────────────────────────

    public Flux<String> runStream(List<Message> messages, String conversationId) {
        return Flux.create(sink -> {
            try {
                AgentResult result = run(messages, conversationId);
                // Stream content in chunks for demo effect
                String content = result.content();
                for (int i = 0; i < content.length(); i += 10) {
                    int end = Math.min(i + 10, content.length());
                    sink.next(content.substring(i, end));
                }
                // Emit artifact metadata as a special trailing event
                if (result.artifacts() != null && !result.artifacts().isEmpty()) {
                    try {
                        String artifactJson = "[ARTIFACTS]" + objectMapper.writeValueAsString(result.artifacts());
                        sink.next(artifactJson);
                    } catch (Exception e) {
                        log.warn("Failed to serialize artifacts for stream: {}", e.getMessage());
                    }
                }
                sink.complete();
            } catch (Exception ex) {
                sink.error(ex);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool system prompt injection
    // ─────────────────────────────────────────────────────────────────────────

    private void injectToolPrompt(List<Message> context) {
        // Replace or append system message with tool capabilities
        context.add(0, new SystemMessage(toolSystemPrompt));
    }

    private String buildToolSystemPrompt(List<AgentTool> toolList) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an enterprise AI assistant for a bank. You are helpful, precise, and professional.

                ## CRITICAL — CODEBASE CONTEXT TAKES ABSOLUTE PRIORITY
                The user message may contain sections labelled "=== SYSTEM KNOWLEDGE GRAPH CONTEXT ===",
                "## Knowledge Graph Context", or "## REST API Catalog". These come from the ACTUAL INDEXED
                CODEBASE and are always more accurate than your general training knowledge.

                When ANY of these sections are present and non-empty, you MUST:
                1. Base your ENTIRE answer on the specific classes, services, files, and relationships listed there.
                2. NAME the exact classes/files from the context — e.g. "SystemKnowledgeGraphService.java".
                3. NEVER give a generic "here are the typical classes you would change" answer when real codebase
                   data is available. That is wrong and unhelpful to the user.
                4. If the context lists specific classes, cite those exact names in your response.
                5. Only say "the knowledge graph did not return specific results" when the sections are truly empty.

                ## KNOWLEDGE GRAPH CONTEXT — USE FIRST
                The user message contains pre-built Knowledge Graph context assembled from Neo4j, MongoDB,
                and indexed code/documentation. For ANY question about system structure, component lists,
                API catalogs, service inventories, dependencies, or architecture — USE THIS CONTEXT DIRECTLY.
                DO NOT call `github_api` for such questions. The knowledge graph already has the answer.

                Use `github_api` ONLY for real-time operational data that the knowledge graph cannot provide:
                latest commit messages, open pull requests, current workflow run status, live branch diffs.

                ## TOOL CALLING — MANDATORY RULES
                1. For structural/catalog queries (list all APIs, show all services, what endpoints exist, etc.)
                   — answer directly from the KNOWLEDGE GRAPH CONTEXT in the message. No tool call needed.
                2. When a user asks for real-time GitHub data (latest commits, open PRs, workflow run status,
                   recent branch changes) — call the `github_api` tool IMMEDIATELY.
                3. When a user asks for output as a file (Excel, PDF, Word, PowerPoint, JSON, XML, CSV, HTML, text),
                   you MUST call the appropriate generation tool with the actual data — do NOT just show the data inline.
                4. NEVER say "I will fetch", "Let me retrieve", or "I'll start by" — just answer or call the tool.
                5. Multi-step tasks: call one tool, receive TOOL_RESULT, then call the next tool if needed.

                ## TOOL CALL FORMAT
                Output EXACTLY this format (nothing before or after on that line):
                TOOL_CALL: {"tool": "<tool_name>", "params": {<params_json>}}

                Wait for TOOL_RESULT before continuing.

                ## OUTPUT FORMAT DETECTION
                - Excel / spreadsheet / table / .xlsx → use `generate_excel`
                - PDF / report / document → use `generate_pdf`
                - Word / .docx → use `generate_word`
                - PowerPoint / slides / .pptx → use `generate_powerpoint`
                - JSON / machine-readable → use `export_json`
                - XML → use `export_xml`
                - HTML → use `generate_html`
                - Text / .txt → use `export_text`
                - Real-time GitHub data (latest commits, open PRs, workflow runs, branch status) → use `github_api`
                - System structure, API lists, component catalogs → answer from knowledge graph context (no tool call)

                AVAILABLE TOOLS:
                """);
        toolList.forEach(tool -> sb.append(String.format(
                "- **%s**: %s\n  Parameters: %s\n\n",
                tool.getName(), tool.getDescription(), tool.getParameterSchema())));
        sb.append("\nIf no tool is needed, respond directly and professionally.");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean containsToolCall(String response) {
        return response != null && response.contains(TOOL_CALL_PREFIX);
    }

    private String extractToolCallJson(String response) {
        int idx = response.indexOf(TOOL_CALL_PREFIX);
        String after = response.substring(idx + TOOL_CALL_PREFIX.length()).trim();
        int start = after.indexOf('{');
        int end = findMatchingBrace(after, start);
        return after.substring(start, end + 1);
    }

    private String extractTextBeforeCall(String response) {
        int idx = response.indexOf(TOOL_CALL_PREFIX);
        return idx > 0 ? response.substring(0, idx).trim() : "";
    }

    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') { depth--; if (depth == 0) return i; }
        }
        return s.length() - 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result record
    // ─────────────────────────────────────────────────────────────────────────

    public record AgentResult(
            String content,
            List<ChatResponse.Citation> citations,
            List<ChatResponse.GeneratedArtifact> artifacts,
            List<String> toolCalls,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            long latencyMs
    ) {}
}
