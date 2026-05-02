package com.bank.aiassistant.service.llm;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Abstraction over any LLM backend.
 * Swap the implementation to use Bedrock, Azure OpenAI, or local Ollama
 * without changing any upstream code.
 */
public interface LlmService {

    /**
     * Blocking chat completion.
     * @param messages conversation history including system prompt
     * @return full assistant response text
     */
    String chat(List<Message> messages);

    /**
     * Streaming chat completion via SSE.
     * @param messages conversation history
     * @return Flux of incremental text chunks
     */
    Flux<String> chatStream(List<Message> messages);

    /**
     * Generate a dense embedding vector for the given text.
     * Used by the ingestion pipeline and retrieval stage.
     */
    float[] embed(String text);

    /**
     * Generate an image from a text prompt (OpenAI DALL-E 3).
     * @return URL of the generated image
     */
    String generateImage(String prompt);

    /** @return model identifier currently in use */
    String getModelName();
}
