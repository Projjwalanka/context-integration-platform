package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Agent tool: exports content as a plain text (.txt) file.
 */
@Slf4j
@Component
public class TextExportTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override public String getName() { return "export_text"; }

    @Override
    public String getDescription() {
        return "Exports content as a downloadable plain text (.txt) file. " +
               "Use when the user requests a text file, .txt output, or plain text download.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "content":  {"type":"string","description":"Text content to write to the file"},
                  "filename": {"type":"string","description":"Optional filename (without extension)"}
                },"required":["content"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String content  = params.path("content").asText("");
        String filename = params.path("filename").asText("text-" + UUID.randomUUID().toString().substring(0, 8));

        if (content.isBlank()) return ToolResult.error("content is required");

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String txtFilename = filename + ".txt";
            File outputFile = dir.resolve(txtFilename).toFile();
            Files.writeString(outputFile.toPath(), content, StandardCharsets.UTF_8);
            log.info("Text file exported: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact("Text file exported successfully.", baseUrl + "/" + txtFilename, "TEXT");
        } catch (Exception ex) {
            log.error("Text export failed", ex);
            return ToolResult.error("Text export failed: " + ex.getMessage());
        }
    }
}
