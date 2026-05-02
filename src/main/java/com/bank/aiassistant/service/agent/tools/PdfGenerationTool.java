package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent tool: generates a formatted PDF document from Markdown-like text.
 *
 * <p>Input params:
 * <pre>{ "title": "string", "content": "string (markdown)", "filename": "optional" }</pre>
 */
@Slf4j
@Component
public class PdfGenerationTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override
    public String getName() { return "generate_pdf"; }

    @Override
    public String getDescription() {
        return "Generates a professional PDF document from provided title and content. " +
               "Use when the user asks to create a report, summary, or document in PDF format.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "title":    { "type": "string", "description": "Document title" },
                    "content":  { "type": "string", "description": "Document body content (supports basic markdown)" },
                    "filename": { "type": "string", "description": "Optional output filename (without extension)" }
                  },
                  "required": ["title", "content"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String title    = params.path("title").asText("Untitled Document");
        String content  = params.path("content").asText("");
        String filename = params.path("filename").asText("document-" + UUID.randomUUID().toString().substring(0, 8));

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String pdfFilename = filename + ".pdf";
            File outputFile = dir.resolve(pdfFilename).toFile();

            // Build PDF with OpenPDF
            Document document = new Document(PageSize.A4, 36, 36, 54, 54);
            PdfWriter.getInstance(document, new FileOutputStream(outputFile));
            document.open();

            // Header
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Font.BOLD);
            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.ITALIC);
            Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 11);

            Paragraph titlePara = new Paragraph(title, titleFont);
            titlePara.setAlignment(Element.ALIGN_CENTER);
            titlePara.setSpacingAfter(4);
            document.add(titlePara);

            Paragraph datePara = new Paragraph("Generated: " + Instant.now(), subtitleFont);
            datePara.setAlignment(Element.ALIGN_CENTER);
            datePara.setSpacingAfter(20);
            document.add(datePara);

            // Content — split on double newlines for paragraphs
            for (String block : content.split("\n\n")) {
                String stripped = block.trim();
                if (stripped.isEmpty()) continue;

                if (stripped.startsWith("# ")) {
                    Paragraph h1 = new Paragraph(stripped.substring(2),
                            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15));
                    h1.setSpacingBefore(14);
                    h1.setSpacingAfter(6);
                    document.add(h1);
                } else if (stripped.startsWith("## ")) {
                    Paragraph h2 = new Paragraph(stripped.substring(3),
                            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13));
                    h2.setSpacingBefore(10);
                    h2.setSpacingAfter(4);
                    document.add(h2);
                } else if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
                    List list = new List(List.UNORDERED);
                    for (String line : stripped.split("\n")) {
                        String item = line.replaceFirst("^[\\-\\*] ", "").trim();
                        if (!item.isEmpty()) list.add(new ListItem(item, bodyFont));
                    }
                    document.add(list);
                } else {
                    Paragraph para = new Paragraph(stripped, bodyFont);
                    para.setSpacingAfter(8);
                    para.setAlignment(Element.ALIGN_JUSTIFIED);
                    document.add(para);
                }
            }

            document.close();

            String downloadUrl = baseUrl + "/" + pdfFilename;
            log.info("PDF generated: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact(
                    "PDF document '" + title + "' has been generated successfully.",
                    downloadUrl, "PDF");

        } catch (Exception ex) {
            log.error("PDF generation failed", ex);
            return ToolResult.error("Failed to generate PDF: " + ex.getMessage());
        }
    }
}
