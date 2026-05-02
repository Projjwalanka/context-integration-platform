package com.bank.aiassistant.service.agent.tools;

import com.bank.aiassistant.service.llm.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent tool: generates an image using DALL-E 3 via OpenAI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenerationTool implements AgentTool {

    private final LlmService llmService;

    @Override public String getName() { return "generate_image"; }

    @Override
    public String getDescription() {
        return "Generates an image using DALL-E 3 based on a text description. " +
               "Use when the user asks to create, draw, or visualize something.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "prompt": {"type":"string","description":"Detailed image description"}
                },"required":["prompt"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String prompt = params.path("prompt").asText();
        if (prompt.isBlank()) return ToolResult.error("Prompt is required");
        try {
            String imageUrl = llmService.generateImage(prompt);
            return ToolResult.artifact("Image generated successfully.", imageUrl, "IMAGE");
        } catch (Exception ex) {
            log.error("Image generation failed: {}", ex.getMessage());
            return ToolResult.error("Image generation failed: " + ex.getMessage());
        }
    }
}
