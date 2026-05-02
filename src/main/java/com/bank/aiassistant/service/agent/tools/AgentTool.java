package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SPI for agentic tools. Each tool can be called by the ReAct agent.
 *
 * <p>Tool execution contract:
 * <ul>
 *   <li>{@link #getName()} must be a snake_case identifier, e.g. {@code generate_pdf}</li>
 *   <li>{@link #getDescription()} is injected into the LLM system prompt</li>
 *   <li>{@link #getParameterSchema()} is a JSON Schema string describing the input</li>
 *   <li>{@link #execute(JsonNode)} receives validated params and returns a result string</li>
 * </ul>
 */
public interface AgentTool {
    String getName();
    String getDescription();
    String getParameterSchema();
    ToolResult execute(JsonNode params);

    record ToolResult(boolean success, String content, String artifactUrl, String artifactType) {
        public static ToolResult ok(String content) {
            return new ToolResult(true, content, null, null);
        }
        public static ToolResult artifact(String content, String url, String type) {
            return new ToolResult(true, content, url, type);
        }
        public static ToolResult error(String message) {
            return new ToolResult(false, "Error: " + message, null, null);
        }
    }
}
