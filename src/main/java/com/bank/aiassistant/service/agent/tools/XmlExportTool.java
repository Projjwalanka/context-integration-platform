package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Agent tool: exports data as a well-formed XML file.
 */
@Slf4j
@Component
public class XmlExportTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override public String getName() { return "export_xml"; }

    @Override
    public String getDescription() {
        return "Exports data as a downloadable XML file. " +
               "Use when the user wants output in XML format.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "root_element": {"type":"string","description":"Root XML element name (default: 'data')"},
                  "content":      {"type":"string","description":"Pre-formatted XML content string, OR omit to use 'data' field"},
                  "data":         {"type":"object","description":"Structured data to convert to XML (used if 'content' not provided)"},
                  "filename":     {"type":"string","description":"Optional filename (without extension)"}
                },"required":[]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String rootElement = params.path("root_element").asText("data");
        String filename    = params.path("filename").asText("export-" + UUID.randomUUID().toString().substring(0, 8));
        String content     = params.path("content").asText(null);
        JsonNode data      = params.get("data");

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String xmlFilename = filename + ".xml";
            File outputFile = dir.resolve(xmlFilename).toFile();

            String xml;
            if (content != null && !content.isBlank()) {
                xml = content.startsWith("<?xml") ? content
                        : "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + content;
            } else if (data != null) {
                xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + jsonToXml(rootElement, data, 0);
            } else {
                return ToolResult.error("Either 'content' or 'data' is required");
            }

            Files.writeString(outputFile.toPath(), xml, StandardCharsets.UTF_8);
            log.info("XML exported: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact("XML file exported successfully.", baseUrl + "/" + xmlFilename, "XML");

        } catch (Exception ex) {
            log.error("XML export failed", ex);
            return ToolResult.error("XML export failed: " + ex.getMessage());
        }
    }

    private String jsonToXml(String tag, JsonNode node, int depth) {
        String indent = "  ".repeat(depth);
        StringBuilder sb = new StringBuilder();
        String safeTag = tag.replaceAll("[^a-zA-Z0-9_\\-.]", "_");

        if (node.isObject()) {
            sb.append(indent).append("<").append(safeTag).append(">\n");
            node.properties().forEach(field ->
                    sb.append(jsonToXml(field.getKey(), field.getValue(), depth + 1)));
            sb.append(indent).append("</").append(safeTag).append(">\n");
        } else if (node.isArray()) {
            String itemTag = safeTag.endsWith("s") ? safeTag.substring(0, safeTag.length() - 1) : "item";
            sb.append(indent).append("<").append(safeTag).append(">\n");
            for (JsonNode item : node) {
                sb.append(jsonToXml(itemTag, item, depth + 1));
            }
            sb.append(indent).append("</").append(safeTag).append(">\n");
        } else {
            String value = escape(node.asText());
            sb.append(indent).append("<").append(safeTag).append(">")
              .append(value).append("</").append(safeTag).append(">\n");
        }
        return sb.toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
