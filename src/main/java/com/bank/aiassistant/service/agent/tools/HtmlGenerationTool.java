package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent tool: generates a styled HTML page from title + Markdown-like content,
 * or from a raw HTML string.
 */
@Slf4j
@Component
public class HtmlGenerationTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override public String getName() { return "generate_html"; }

    @Override
    public String getDescription() {
        return "Generates a styled HTML file. Use when the user asks for HTML output, " +
               "a web page, or an HTML report. Supports titles, headings, paragraphs, " +
               "bullet lists, numbered lists, and tables.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "title":    {"type":"string","description":"Page/document title"},
                  "content":  {"type":"string","description":"Markdown-like content (# H1, ## H2, - bullets, | tables) OR raw HTML body"},
                  "raw_html": {"type":"boolean","description":"If true, 'content' is treated as raw HTML body (default: false)"},
                  "filename": {"type":"string","description":"Optional filename (without extension)"}
                },"required":["title","content"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String title    = params.path("title").asText("Document");
        String content  = params.path("content").asText("");
        boolean rawHtml = params.path("raw_html").asBoolean(false);
        String filename = params.path("filename").asText("page-" + UUID.randomUUID().toString().substring(0, 8));

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String htmlFilename = filename + ".html";
            File outputFile = dir.resolve(htmlFilename).toFile();

            String body = rawHtml ? content : markdownToHtml(content);
            String html = buildPage(title, body);

            Files.writeString(outputFile.toPath(), html, StandardCharsets.UTF_8);
            log.info("HTML generated: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact(
                    "HTML page '" + title + "' has been generated successfully.",
                    baseUrl + "/" + htmlFilename, "HTML");

        } catch (Exception ex) {
            log.error("HTML generation failed", ex);
            return ToolResult.error("Failed to generate HTML: " + ex.getMessage());
        }
    }

    private String markdownToHtml(String markdown) {
        StringBuilder sb = new StringBuilder();
        String[] blocks = markdown.split("\n\n");
        for (String block : blocks) {
            String stripped = block.trim();
            if (stripped.isEmpty()) continue;

            if (stripped.startsWith("# ")) {
                sb.append("<h1>").append(esc(stripped.substring(2).trim())).append("</h1>\n");
            } else if (stripped.startsWith("## ")) {
                sb.append("<h2>").append(esc(stripped.substring(3).trim())).append("</h2>\n");
            } else if (stripped.startsWith("### ")) {
                sb.append("<h3>").append(esc(stripped.substring(4).trim())).append("</h3>\n");
            } else if (stripped.startsWith("| ")) {
                sb.append(tableToHtml(stripped));
            } else if (stripped.lines().anyMatch(l -> l.startsWith("- ") || l.startsWith("* "))) {
                sb.append("<ul>\n");
                for (String line : stripped.split("\n")) {
                    if (line.startsWith("- ") || line.startsWith("* ")) {
                        sb.append("  <li>").append(esc(line.substring(2).trim())).append("</li>\n");
                    }
                }
                sb.append("</ul>\n");
            } else if (stripped.matches("\\d+\\..*")) {
                sb.append("<ol>\n");
                for (String line : stripped.split("\n")) {
                    String item = line.replaceFirst("^\\d+\\.\\s*", "");
                    sb.append("  <li>").append(esc(item)).append("</li>\n");
                }
                sb.append("</ol>\n");
            } else {
                sb.append("<p>").append(esc(stripped)).append("</p>\n");
            }
        }
        return sb.toString();
    }

    private String tableToHtml(String markdownTable) {
        StringBuilder sb = new StringBuilder("<table>\n");
        String[] lines = markdownTable.split("\n");
        boolean headerDone = false;
        for (String line : lines) {
            if (line.contains("---")) { headerDone = true; continue; }
            String[] cells = splitRow(line);
            if (!headerDone) {
                sb.append("  <thead><tr>");
                for (String c : cells) sb.append("<th>").append(esc(c.trim())).append("</th>");
                sb.append("</tr></thead>\n  <tbody>\n");
            } else {
                sb.append("    <tr>");
                for (String c : cells) sb.append("<td>").append(esc(c.trim())).append("</td>");
                sb.append("</tr>\n");
            }
        }
        sb.append("  </tbody>\n</table>\n");
        return sb.toString();
    }

    private String[] splitRow(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.split("\\|");
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String buildPage(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                  <title>""" + esc(title) + """
                </title>
                  <style>
                    body { font-family: 'Segoe UI', system-ui, sans-serif; max-width: 900px; margin: 40px auto;
                           padding: 0 24px; color: #1f2937; background: #f9fafb; line-height: 1.6; }
                    h1   { color: #1e3a8a; border-bottom: 2px solid #3b82f6; padding-bottom: 8px; }
                    h2   { color: #1e40af; margin-top: 2rem; }
                    h3   { color: #2563eb; }
                    table{ border-collapse: collapse; width: 100%; margin: 1rem 0; }
                    th   { background: #1e40af; color: white; padding: 8px 12px; text-align: left; }
                    td   { border: 1px solid #e5e7eb; padding: 7px 12px; }
                    tr:nth-child(even) td { background: #f1f5f9; }
                    ul,ol{ padding-left: 1.5rem; }
                    li   { margin: 4px 0; }
                    .footer { margin-top: 3rem; font-size: 0.8rem; color: #9ca3af; border-top: 1px solid #e5e7eb; padding-top: 8px; }
                  </style>
                </head>
                <body>
                  <h1>""" + esc(title) + """
                </h1>
                """ + body + """
                  <div class="footer">Generated by AI Assistant &mdash; """ + Instant.now() + """
                </div>
                </body>
                </html>
                """;
    }
}
