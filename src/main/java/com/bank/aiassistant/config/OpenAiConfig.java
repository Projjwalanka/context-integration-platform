package com.bank.aiassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient configuration.
 *
 * <p>The {@link ChatClient} is the primary interface for LLM interaction.
 * Auto-configured by spring-ai-openai-spring-boot-starter from {@code spring.ai.openai.*}
 * properties. This bean adds a default system prompt and advisory configuration.
 */
@Configuration
public class OpenAiConfig {

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("You are a helpful, professional AI assistant for a financial services organisation.")
                .build();
    }
}
