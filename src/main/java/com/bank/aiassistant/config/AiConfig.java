package com.bank.aiassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * Dedicated ChatClient for internal KG services (entity extraction,
     * context engineering). Separate from the agent orchestrator so that
     * KG extraction calls don't interfere with user-facing chat.
     */
    @Bean("kgChatClient")
    public ChatClient kgChatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
