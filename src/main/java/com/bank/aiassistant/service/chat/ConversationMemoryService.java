package com.bank.aiassistant.service.chat;

import com.bank.aiassistant.model.entity.Conversation;
import com.bank.aiassistant.model.entity.Message;
import com.bank.aiassistant.repository.ConversationRepository;
import com.bank.aiassistant.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages conversation history for the LLM context window.
 *
 * <p>Strategy:
 * <ul>
 *   <li>Last {@code app.chat.context-window-messages} turns are kept verbatim.</li>
 *   <li>Older turns beyond the window are summarised by the LLM (lazy, on-demand).</li>
 *   <li>System prompt is always prepended.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Value("${app.chat.context-window-messages:20}")
    private int contextWindowMessages;

    @Value("${app.chat.system-prompt}")
    private String defaultSystemPrompt;

    // ─────────────────────────────────────────────────────────────────────────
    // Build LLM context
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the message list to send to the LLM.
     * Returns: [SystemMessage, ...history (last N turns), UserMessage(current)]
     */
    @Transactional(readOnly = true)
    public List<org.springframework.ai.chat.messages.Message> buildContext(
            String conversationId, String currentUserMessage, String systemPromptOverride) {

        List<org.springframework.ai.chat.messages.Message> context = new ArrayList<>();

        // 1. System prompt
        String sysPrompt = systemPromptOverride != null ? systemPromptOverride : defaultSystemPrompt;
        context.add(new SystemMessage(sysPrompt));

        // 2. Conversation history (last N turns)
        if (conversationId != null) {
            List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            int start = Math.max(0, history.size() - contextWindowMessages);
            history.subList(start, history.size()).forEach(msg -> {
                context.add(switch (msg.getRole()) {
                    case USER       -> new UserMessage(msg.getContent());
                    case ASSISTANT  -> new AssistantMessage(msg.getContent());
                    case SYSTEM     -> new SystemMessage(msg.getContent());
                    default         -> new UserMessage(msg.getContent()); // TOOL fallback
                });
            });
        }

        // 3. Current user message
        context.add(new UserMessage(currentUserMessage));
        return context;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public Message saveUserMessage(Conversation conversation, String content) {
        Message msg = Message.builder()
                .conversationId(conversation.getId())
                .role(Message.MessageRole.USER)
                .content(content)
                .build();
        return messageRepository.save(msg);
    }

    @Transactional
    public Message saveAssistantMessage(Conversation conversation, String content,
                                         String model, Integer tokens, Long latencyMs,
                                         String metadataJson) {
        Message msg = Message.builder()
                .conversationId(conversation.getId())
                .role(Message.MessageRole.ASSISTANT)
                .content(content)
                .model(model)
                .tokenCount(tokens)
                .latencyMs(latencyMs)
                .metadata(metadataJson)
                .build();
        return messageRepository.save(msg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-title generation
    // ─────────────────────────────────────────────────────────────────────────

    public String generateTitle(String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) return "New Chat";
        int maxLen = 60;
        String title = firstUserMessage.strip();
        return title.length() > maxLen ? title.substring(0, maxLen) + "…" : title;
    }
}
