package com.bank.aiassistant.service.agent.tools;

import com.bank.aiassistant.service.vector.VectorStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent tool: hybrid search over the vector knowledge base.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    private final VectorStoreService vectorStoreService;

    @Override public String getName() { return "search_knowledge_base"; }

    @Override
    public String getDescription() {
        return "Searches the internal knowledge base (ingested documents, PDFs, wikis) " +
               "for content relevant to the query. Returns ranked excerpts with sources.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "query":      {"type":"string","description":"Search query"},
                  "top_k":      {"type":"integer","description":"Max results (default 5)","default":5},
                  "source_type":{"type":"string","description":"Filter by source type: DOCUMENTS, JIRA, CONFLUENCE, etc."}
                },"required":["query"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String query      = params.path("query").asText();
        int topK          = params.path("top_k").asInt(5);
        String sourceType = params.path("source_type").asText(null);

        java.util.Map<String, Object> filter = new java.util.HashMap<>();
        if (sourceType != null && !sourceType.isBlank()) filter.put("source_type", sourceType);

        List<Document> docs = vectorStoreService.hybridSearch(query, filter, topK);
        if (docs.isEmpty()) return ToolResult.ok("No relevant documents found in the knowledge base.");

        String result = docs.stream()
                .map(d -> String.format("**Source:** %s\n%s",
                        d.getMetadata().getOrDefault("title", d.getMetadata().getOrDefault("source_ref", "Unknown")),
                        d.getText()))
                .collect(Collectors.joining("\n\n---\n\n"));

        return ToolResult.ok(result);
    }
}
