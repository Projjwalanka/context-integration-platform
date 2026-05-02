package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Agent tool: generates a PowerPoint (.pptx) presentation from a list of slides.
 *
 * Each slide has a title and bullet points (or body text).
 */
@Slf4j
@Component
public class PowerPointGenerationTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    private static final Color HEADER_BG   = new Color(0x1E, 0x40, 0xAF); // blue-700
    private static final Color HEADER_FG   = Color.WHITE;
    private static final Color SLIDE_BG    = new Color(0xF8, 0xFA, 0xFF);
    private static final Color TITLE_COLOR = new Color(0x1E, 0x40, 0xAF);
    private static final Color BODY_COLOR  = new Color(0x1F, 0x29, 0x37);

    @Override public String getName() { return "generate_powerpoint"; }

    @Override
    public String getDescription() {
        return "Generates a PowerPoint (.pptx) presentation. " +
               "Use when the user asks for slides, a presentation, or .pptx output. " +
               "Each slide has a title and bullet points or body content.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {"type":"object","properties":{
                  "presentation_title": {"type":"string","description":"Overall presentation title"},
                  "slides": {
                    "type":"array",
                    "description":"List of slides",
                    "items": {
                      "type":"object",
                      "properties":{
                        "title":   {"type":"string","description":"Slide title"},
                        "bullets": {"type":"array","items":{"type":"string"},"description":"Bullet points for the slide"},
                        "content": {"type":"string","description":"Free-form text content (used if bullets is empty)"}
                      },"required":["title"]
                    }
                  },
                  "filename": {"type":"string","description":"Optional output filename (without extension)"}
                },"required":["presentation_title","slides"]}""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String presentationTitle = params.path("presentation_title").asText("Presentation");
        JsonNode slides          = params.path("slides");
        String filename          = params.path("filename").asText("slides-" + UUID.randomUUID().toString().substring(0, 8));

        if (!slides.isArray() || slides.isEmpty()) {
            return ToolResult.error("slides array is required and must not be empty");
        }

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String pptxFilename = filename + ".pptx";
            File outputFile = dir.resolve(pptxFilename).toFile();

            try (XMLSlideShow ppt = new XMLSlideShow();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                // Default slide dimensions (widescreen 16:9)
                ppt.setPageSize(new Dimension(960, 540));

                // Title slide
                XSLFSlide titleSlide = addTitleSlide(ppt, presentationTitle);

                // Content slides
                for (JsonNode slide : slides) {
                    String slideTitle   = slide.path("title").asText("Slide");
                    JsonNode bulletsNode = slide.path("bullets");
                    String bodyContent  = slide.path("content").asText(null);
                    addContentSlide(ppt, slideTitle, bulletsNode, bodyContent);
                }

                ppt.write(fos);
            }

            log.info("PowerPoint generated: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact(
                    "PowerPoint presentation '" + presentationTitle + "' with " + slides.size() + " slides generated.",
                    baseUrl + "/" + pptxFilename, "POWERPOINT");

        } catch (Exception ex) {
            log.error("PowerPoint generation failed", ex);
            return ToolResult.error("Failed to generate PowerPoint: " + ex.getMessage());
        }
    }

    private XSLFSlide addTitleSlide(XMLSlideShow ppt, String title) {
        XSLFSlide slide = ppt.createSlide();
        Dimension dim = ppt.getPageSize();

        // Background
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        bg.setAnchor(new Rectangle(0, 0, dim.width, dim.height));
        bg.setFillColor(HEADER_BG);
        bg.setLineColor(HEADER_BG);

        // Title text
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(60, 160, dim.width - 120, 160));
        XSLFTextParagraph p = titleBox.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun run = p.addNewTextRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(40.0);
        run.setFontColor(HEADER_FG);

        return slide;
    }

    private void addContentSlide(XMLSlideShow ppt, String title, JsonNode bullets, String bodyContent) {
        XSLFSlide slide = ppt.createSlide();
        Dimension dim = ppt.getPageSize();

        // Slide background
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        bg.setAnchor(new Rectangle(0, 0, dim.width, dim.height));
        bg.setFillColor(SLIDE_BG);
        bg.setLineColor(SLIDE_BG);

        // Title bar
        XSLFAutoShape titleBar = slide.createAutoShape();
        titleBar.setShapeType(org.apache.poi.sl.usermodel.ShapeType.RECT);
        titleBar.setAnchor(new Rectangle(0, 0, dim.width, 70));
        titleBar.setFillColor(HEADER_BG);
        titleBar.setLineColor(HEADER_BG);

        // Title text
        XSLFTextBox titleBox = slide.createTextBox();
        titleBox.setAnchor(new Rectangle(24, 10, dim.width - 48, 50));
        XSLFTextParagraph tp = titleBox.addNewTextParagraph();
        XSLFTextRun tr = tp.addNewTextRun();
        tr.setText(title);
        tr.setBold(true);
        tr.setFontSize(22.0);
        tr.setFontColor(HEADER_FG);

        // Body content
        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle(40, 90, dim.width - 80, dim.height - 110));

        if (bullets != null && bullets.isArray() && !bullets.isEmpty()) {
            for (JsonNode bullet : bullets) {
                XSLFTextParagraph bp = body.addNewTextParagraph();
                bp.setIndentLevel(0);
                XSLFTextRun br = bp.addNewTextRun();
                br.setText("• " + bullet.asText());
                br.setFontSize(16.0);
                br.setFontColor(BODY_COLOR);
                bp.setSpaceBefore(200.0);
            }
        } else if (bodyContent != null && !bodyContent.isBlank()) {
            for (String line : bodyContent.split("\n")) {
                XSLFTextParagraph bp = body.addNewTextParagraph();
                XSLFTextRun br = bp.addNewTextRun();
                br.setText(line.trim());
                br.setFontSize(15.0);
                br.setFontColor(BODY_COLOR);
            }
        }
    }
}
