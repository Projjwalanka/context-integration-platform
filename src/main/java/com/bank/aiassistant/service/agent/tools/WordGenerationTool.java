package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent tool: generates a Word (.docx) document from title + Markdown-like content.
 */
@Slf4j
@Component
public class WordGenerationTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override public String getName() { return "generate_word"; }

    @Override
    public String getDescription() {
        return "Generates a Word (.docx) document from provided title and content. " +
               "Use when the user asks for output in Word, .docx, or Microsoft Word format.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "title":    {"type":"string","description":"Document title"},
                  "content":  {"type":"string","description":"Document body (supports # headings, ## subheadings, - bullet lists, and plain paragraphs)"},
                  "filename": {"type":"string","description":"Optional output filename (without extension)"}
                },"required":["title","content"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String title    = params.path("title").asText("Document");
        String content  = params.path("content").asText("");
        String filename = params.path("filename").asText("word-" + UUID.randomUUID().toString().substring(0, 8));

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String docxFilename = filename + ".docx";
            File outputFile = dir.resolve(docxFilename).toFile();

            try (XWPFDocument doc = new XWPFDocument();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                // Title
                XWPFParagraph titlePara = doc.createParagraph();
                titlePara.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(title);
                titleRun.setBold(true);
                titleRun.setFontSize(22);
                titleRun.setFontFamily("Calibri");

                // Generated date
                XWPFParagraph datePara = doc.createParagraph();
                datePara.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun dateRun = datePara.createRun();
                dateRun.setText("Generated: " + Instant.now());
                dateRun.setItalic(true);
                dateRun.setFontSize(10);
                dateRun.setColor("888888");

                // Spacer
                doc.createParagraph();

                // Body — parse simple markdown
                for (String block : content.split("\n\n")) {
                    String stripped = block.trim();
                    if (stripped.isEmpty()) continue;

                    if (stripped.startsWith("# ")) {
                        addHeading(doc, stripped.substring(2).trim(), 1);
                    } else if (stripped.startsWith("## ")) {
                        addHeading(doc, stripped.substring(3).trim(), 2);
                    } else if (stripped.startsWith("### ")) {
                        addHeading(doc, stripped.substring(4).trim(), 3);
                    } else if (stripped.startsWith("| ")) {
                        addTable(doc, stripped);
                    } else if (stripped.lines().anyMatch(l -> l.startsWith("- ") || l.startsWith("* "))) {
                        for (String line : stripped.split("\n")) {
                            if (line.startsWith("- ") || line.startsWith("* ")) {
                                addBullet(doc, line.substring(2).trim());
                            } else if (!line.isBlank()) {
                                addParagraph(doc, line.trim());
                            }
                        }
                    } else {
                        addParagraph(doc, stripped);
                    }
                }

                doc.write(fos);
            }

            log.info("Word document generated: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact(
                    "Word document '" + title + "' has been generated successfully.",
                    baseUrl + "/" + docxFilename, "WORD");

        } catch (Exception ex) {
            log.error("Word generation failed", ex);
            return ToolResult.error("Failed to generate Word document: " + ex.getMessage());
        }
    }

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(level == 1 ? 16 : level == 2 ? 14 : 12);
        run.setFontFamily("Calibri");
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
        run.setFontFamily("Calibri");
    }

    private void addBullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(720);
        XWPFRun run = p.createRun();
        run.setText("• " + text);
        run.setFontSize(11);
        run.setFontFamily("Calibri");
    }

    private void addTable(XWPFDocument doc, String markdownTable) {
        String[] lines = markdownTable.split("\n");
        if (lines.length < 2) return;
        // Parse header
        String[] headers = splitRow(lines[0]);
        // Find data rows (skip separator line)
        int dataStart = 1;
        if (lines.length > 1 && lines[1].contains("---")) dataStart = 2;

        XWPFTable table = doc.createTable(
                1 + lines.length - dataStart, headers.length);
        table.setWidth("100%");

        // Header row
        XWPFTableRow headerRow = table.getRow(0);
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = i == 0 ? headerRow.getCell(0) : headerRow.addNewTableCell();
            if (i < headerRow.getTableCells().size()) cell = headerRow.getCell(i);
            XWPFRun run = cell.getParagraphArray(0).createRun();
            run.setText(headers[i].trim());
            run.setBold(true);
            run.setFontFamily("Calibri");
            run.setFontSize(10);
        }

        // Data rows
        for (int r = dataStart; r < lines.length; r++) {
            String[] cols = splitRow(lines[r]);
            XWPFTableRow row = table.getRow(1 + r - dataStart);
            for (int c = 0; c < Math.min(cols.length, headers.length); c++) {
                XWPFTableCell cell = row.getCell(c);
                if (cell == null) cell = row.addNewTableCell();
                XWPFRun run = cell.getParagraphArray(0).createRun();
                run.setText(cols[c].trim());
                run.setFontFamily("Calibri");
                run.setFontSize(10);
            }
        }
        doc.createParagraph(); // spacing after table
    }

    private String[] splitRow(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.split("\\|");
    }
}
