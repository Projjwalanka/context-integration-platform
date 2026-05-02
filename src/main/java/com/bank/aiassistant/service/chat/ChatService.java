package com.bank.aiassistant.service.chat;

import com.bank.aiassistant.context.TenantContext;
import com.bank.aiassistant.model.dto.chat.ChatRequest;
import com.bank.aiassistant.model.dto.chat.ChatResponse;
import com.bank.aiassistant.model.entity.Conversation;
import com.bank.aiassistant.model.entity.Message;
import com.bank.aiassistant.model.entity.User;
import com.bank.aiassistant.repository.ConnectorConfigRepository;
import com.bank.aiassistant.repository.ConversationRepository;
import com.bank.aiassistant.repository.UserRepository;
import com.bank.aiassistant.service.agent.AgentOrchestrator;
import com.bank.aiassistant.service.connector.ConnectorRegistry;
import com.bank.aiassistant.service.connector.github.GitHubContextEngineeringService;
import com.bank.aiassistant.service.guardrails.GuardrailChain;
import com.bank.aiassistant.service.kg.ContextEngineeringService;
import com.bank.aiassistant.service.skg.SkgContextService;
import com.bank.aiassistant.model.dto.skg.ContextBuildRequest;
import com.bank.aiassistant.model.dto.skg.ContextBuildResponse;
import com.bank.aiassistant.service.vector.VectorStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the full chat turn:
 * 1) guardrail input check
 * 2) context retrieval (vector + connectors)
 * 3) prompt augmentation
 * 4) agent run (tool calls)
 * 5) guardrail output check
 * 6) persistence
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final ConversationMemoryService memoryService;
    private final VectorStoreService vectorStoreService;
    private final AgentOrchestrator agentOrchestrator;
    private final ConnectorRegistry connectorRegistry;
    private final GuardrailChain guardrailChain;
    private final ObjectMapper objectMapper;
    private final GitHubContextEngineeringService gitHubContextEngineeringService;
    private final ConnectorConfigRepository connectorConfigRepository;
    private final ContextEngineeringService contextEngineeringService;
    private final SkgContextService skgContextService;

    @Transactional
    public ChatResponse chat(ChatRequest request, String userEmail) {
        long start = System.currentTimeMillis();
        User user = resolveUser(userEmail);

        String sanitizedInput = guardrailChain.checkInput(request.message(), userEmail);
        Conversation conversation = resolveConversation(request, user);
        memoryService.saveUserMessage(conversation, sanitizedInput);

        Optional<String> directGitHubAnswer = gitHubContextEngineeringService.tryDirectAnswer(
                user.getId(), user.getEmail(), sanitizedInput, request.connectorIds());
        if (directGitHubAnswer.isPresent()) {
            String safeOutput = guardrailChain.checkOutput(directGitHubAnswer.get(), userEmail);
            Message assistantMessage = memoryService.saveAssistantMessage(
                    conversation, safeOutput, "direct-github-answer", null,
                    System.currentTimeMillis() - start, "{}");
            return new ChatResponse(
                    assistantMessage.getId(),
                    conversation.getId(),
                    safeOutput,
                    List.of(),
                    List.of(),
                    "direct-github-answer",
                    null,
                    null,
                    System.currentTimeMillis() - start,
                    Instant.now()
            );
        }

        String augmentedPrompt = buildAugmentedPrompt(user, sanitizedInput, request.connectorIds());
        var messages = memoryService.buildContext(conversation.getId(), augmentedPrompt, null);

        AgentOrchestrator.AgentResult result = agentOrchestrator.run(messages, conversation.getId());
        String safeOutput = guardrailChain.checkOutput(result.content(), userEmail);

        String metadataJson = buildMetadataJson(result);
        Message assistantMessage = memoryService.saveAssistantMessage(
                conversation, safeOutput, result.model(),
                result.outputTokens(), System.currentTimeMillis() - start, metadataJson);

        log.info("Chat completed for user={} conv={} latency={}ms tokens={}",
                userEmail, conversation.getId(), System.currentTimeMillis() - start, result.outputTokens());

        return new ChatResponse(
                assistantMessage.getId(),
                conversation.getId(),
                safeOutput,
                result.citations(),
                result.artifacts(),
                result.model(),
                result.inputTokens(),
                result.outputTokens(),
                System.currentTimeMillis() - start,
                Instant.now()
        );
    }

    @Transactional
    public Flux<String> chatStream(ChatRequest request, String userEmail) {
        User user = resolveUser(userEmail);
        String sanitizedInput = guardrailChain.checkInput(request.message(), userEmail);
        Conversation conversation = resolveConversation(request, user);
        memoryService.saveUserMessage(conversation, sanitizedInput);

        Optional<String> directGitHubAnswer = gitHubContextEngineeringService.tryDirectAnswer(
                user.getId(), user.getEmail(), sanitizedInput, request.connectorIds());
        if (directGitHubAnswer.isPresent()) {
            String safeOutput = guardrailChain.checkOutput(directGitHubAnswer.get(), userEmail);
            memoryService.saveAssistantMessage(
                    conversation, safeOutput, "direct-github-answer", null, 0L, "{}");
            return Flux.just(safeOutput);
        }

        String augmentedPrompt = buildAugmentedPrompt(user, sanitizedInput, request.connectorIds());
        var messages = memoryService.buildContext(conversation.getId(), augmentedPrompt, null);

        return agentOrchestrator.runStream(messages, conversation.getId())
                .doOnError(ex -> log.error("Stream error for user={}", userEmail, ex));
    }

    private String buildAugmentedPrompt(User user, String userQuery, List<String> connectorIds) {
        StringBuilder context = new StringBuilder();

        // ── Knowledge Graph + Vector context via Context Engineering Engine ──
        String tenantId = TenantContext.fromEmail(user.getEmail());
        ContextEngineeringService.EngineeredContext engineered =
                contextEngineeringService.engineContext(userQuery, tenantId, user.getEmail(), connectorIds);

        if (!engineered.contextBlock().isBlank()) {
            context.append(engineered.contextBlock()).append("\n");
        }

        // ── System Knowledge Graph (Neo4j) — every query taps the SKG so deep
        //    reasoning questions ("if I change X, what code must change?") get
        //    the full dependency traversal injected as context. ─────────────
        try {
            ContextBuildRequest skgReq = new ContextBuildRequest(userQuery, 3, true, true, true);
            ContextBuildResponse skgCtx = skgContextService.buildContext(tenantId, skgReq);
            if (skgCtx != null && skgCtx.assembledContext() != null
                    && !skgCtx.assembledContext().isBlank()
                    && !skgCtx.relevantNodes().isEmpty()) {
                context.append(skgCtx.assembledContext()).append("\n");
            }
        } catch (Exception ex) {
            log.warn("SKG context build failed: {}", ex.getMessage());
        }

        // ── Inject active GitHub connector IDs so the LLM can call github_api tool ──
        List<String> githubConnectorIds = resolveGitHubConnectorIds(user, connectorIds);
        if (!githubConnectorIds.isEmpty()) {
            context.append("\n## Active GitHub Connectors (use with github_api tool)\n");
            connectorConfigRepository.findAllById(githubConnectorIds).forEach(cfg ->
                    context.append(String.format("- connector_id: `%s`  name: \"%s\"\n",
                            cfg.getId(), cfg.getName())));
            context.append("Use the `github_api` tool with one of these connector_ids to fetch real-time GitHub data " +
                    "(commits, branches, pull requests, issues, workflows, etc.).\n");
        }

        // ── GitHub-specific deep context (indexed PRs/issues/READMEs) ────────
        GitHubContextEngineeringService.GitHubContextResult gitHubContext =
                gitHubContextEngineeringService.buildContext(user.getId(), user.getEmail(), userQuery, connectorIds);
        if (gitHubContext.context() != null && !gitHubContext.context().isBlank()) {
            context.append(gitHubContext.context()).append("\n");
        }

        // ── Live connector data (Jira, Confluence, etc.) ─────────────────────
        if (connectorIds != null && !connectorIds.isEmpty()) {
            appendNonGithubLiveConnectorData(context, connectorIds, gitHubContext.usedConnectorIds(), userQuery);
        }

        log.debug("Context engineering: intent={} kgEntities={} vectorDocs={} githubConnectors={} totalChars={}",
                engineered.detectedIntent(), engineered.kgEntities().size(),
                engineered.vectorDocuments().size(), githubConnectorIds.size(), context.length());

        if (context.isEmpty()) {
            return userQuery;
        }
        return userQuery + "\n\n---\n" + context;
    }

    private List<String> resolveGitHubConnectorIds(User user, List<String> requestedConnectorIds) {
        if (requestedConnectorIds != null && !requestedConnectorIds.isEmpty()) {
            return requestedConnectorIds.stream()
                    .filter(id -> connectorConfigRepository.findById(id)
                            .map(cfg -> "GITHUB".equalsIgnoreCase(cfg.getConnectorType())
                                    && cfg.isEnabled()
                                    && user.getId().equals(cfg.getOwnerId()))
                            .orElse(false))
                    .toList();
        }
        return connectorConfigRepository.findByOwnerIdAndConnectorTypeIgnoreCaseAndEnabledTrue(
                user.getId(), "GITHUB").stream()
                .map(cfg -> cfg.getId())
                .toList();
    }

    private void appendNonGithubLiveConnectorData(StringBuilder context,
                                                  List<String> connectorIds,
                                                  Set<String> githubConnectorIds,
                                                  String userQuery) {
        for (String connectorId : connectorIds) {
            if (githubConnectorIds.contains(connectorId)) {
                continue;
            }
            boolean isGitHub = connectorConfigRepository.findById(connectorId)
                    .map(cfg -> "GITHUB".equalsIgnoreCase(cfg.getConnectorType()))
                    .orElse(false);
            if (isGitHub) {
                continue;
            }

            try {
                String liveData = connectorRegistry.query(connectorId, userQuery);
                if (liveData != null && !liveData.isBlank()) {
                    context.append("\n## Live Data from connector [").append(connectorId).append("]\n");
                    context.append(liveData).append("\n");
                }
            } catch (Exception ex) {
                log.warn("Failed to query connector {}: {}", connectorId, ex.getMessage());
            }
        }
    }

    private Conversation resolveConversation(ChatRequest request, User user) {
        if (request.conversationId() != null) {
            return conversationRepository.findByIdAndUserId(request.conversationId(), user.getId())
                    .orElseThrow(() -> new RuntimeException("Conversation not found: " + request.conversationId()));
        }
        Conversation newConv = Conversation.builder()
                .userId(user.getId())
                .title(memoryService.generateTitle(request.message()))
                .build();
        return conversationRepository.save(newConv);
    }

    private User resolveUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    private String buildMetadataJson(AgentOrchestrator.AgentResult result) {
        try {
            Map<String, Object> meta = new HashMap<>();
            if (result.citations() != null) {
                meta.put("citations", result.citations());
            }
            if (result.artifacts() != null) {
                meta.put("artifacts", result.artifacts());
            }
            if (result.toolCalls() != null) {
                meta.put("tool_calls", result.toolCalls());
            }
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }
}
