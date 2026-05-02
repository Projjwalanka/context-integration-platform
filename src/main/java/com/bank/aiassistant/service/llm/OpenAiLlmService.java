package com.bank.aiassistant.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * OpenAI GPT-4o implementation of {@link LlmService}.
 *
 * <p>Uses Spring AI's ChatClient for both blocking and streaming modes.
 * The ChatClient is auto-configured by spring-ai-openai-spring-boot-starter
 * from the {@code spring.ai.openai.*} properties.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiLlmService implements LlmService {

    private final ChatClient chatClient;
    private final ImageModel imageModel;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o}")
    private String modelName;

    // ─────────────────────────────────────────────────────────────────────────
    // Chat
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String chat(List<Message> messages) {
        long start = System.currentTimeMillis();
        try {
            String response = chatClient.prompt()
                    .messages(messages)
                    .call()
                    .content();
            log.debug("LLM blocking call completed in {}ms", System.currentTimeMillis() - start);
            return response;
        } catch (Exception ex) {
            log.error("LLM chat call failed", ex);
            throw new RuntimeException("LLM service unavailable: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Flux<String> chatStream(List<Message> messages) {
        return chatClient.prompt()
                .messages(messages)
                .stream()
                .content()
                .doOnError(ex -> log.error("LLM stream error", ex));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Embeddings  (handled by Spring AI PgVectorStore auto-config)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public float[] embed(String text) {
        // EmbeddingClient is used directly by VectorStoreService via Spring AI.
        // This method is a passthrough — kept in the interface for flexibility.
        throw new UnsupportedOperationException(
                "Use VectorStoreService.embed() which leverages the auto-configured EmbeddingClient");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Image Generation
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public String generateImage(String prompt) {
        log.debug("Generating image for prompt: {}", prompt.substring(0, Math.min(80, prompt.length())));
        ImageResponse response = imageModel.call(
                new ImagePrompt(prompt,
                        OpenAiImageOptions.builder()
                                .model(OpenAiImageApi.ImageModel.DALL_E_3.getValue())
                                .quality("standard")
                                .width(1024)
                                .height(1024)
                                .build()));
        return response.getResult().getOutput().getUrl();
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
