package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Agent tool: serialises data or LLM output to a downloadable JSON file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonExportTool implements AgentTool {

    private final ObjectMapper objectMapper;

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override public String getName() { return "export_json"; }

    @Override
    public String getDescription() {
        return "Exports structured data as a downloadable JSON file. " +
               "Use when the user wants machine-readable output.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "data":     {"type":"object","description":"The data to export"},
                  "filename": {"type":"string","description":"Optional filename (without extension)"}
                },"required":["data"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        JsonNode data    = params.get("data");
        String filename  = params.path("filename").asText("export-" + UUID.randomUUID().toString().substring(0, 8));
        if (data == null) return ToolResult.error("data is required");

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String jsonFilename = filename + ".json";
            File outputFile = dir.resolve(jsonFilename).toFile();
            Files.writeString(outputFile.toPath(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
            return ToolResult.artifact("JSON exported successfully.", baseUrl + "/" + jsonFilename, "JSON");
        } catch (Exception ex) {
            log.error("JSON export failed", ex);
            return ToolResult.error("JSON export failed: " + ex.getMessage());
        }
    }
}
