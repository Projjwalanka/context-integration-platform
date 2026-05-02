package com.bank.aiassistant.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Agent tool: generates an Excel workbook from tabular data.
 *
 * <p>Input params:
 * <pre>
 * {
 *   "title":   "Report Title",
 *   "headers": ["Col1", "Col2", ...],
 *   "rows":    [["val1", "val2"], ...],
 *   "filename": "optional"
 * }
 * </pre>
 */
@Slf4j
@Component
public class ExcelGenerationTool implements AgentTool {

    @Value("${app.artifacts.output-dir:${java.io.tmpdir}/ai-artifacts}")
    private String outputDir;

    @Value("${app.artifacts.base-url:/api/artifacts}")
    private String baseUrl;

    @Override public String getName() { return "generate_excel"; }

    @Override
    public String getDescription() {
        return "Generates an Excel (.xlsx) spreadsheet from tabular data (headers + rows). " +
               "Use when the user wants data in a spreadsheet/Excel format.";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "title":    { "type": "string" },
                    "headers":  { "type": "array", "items": { "type": "string" } },
                    "rows":     { "type": "array",  "items": { "type": "array", "items": {} } },
                    "filename": { "type": "string" }
                  },
                  "required": ["headers", "rows"]
                }""";
    }

    @Override
    public ToolResult execute(JsonNode params) {
        String title    = params.path("title").asText("Data Export");
        String filename = params.path("filename").asText("export-" + UUID.randomUUID().toString().substring(0, 8));
        JsonNode headers = params.get("headers");
        JsonNode rows    = params.get("rows");

        if (headers == null || rows == null) return ToolResult.error("Missing headers or rows");

        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            String xlsxFilename = filename + ".xlsx";
            File outputFile = dir.resolve(xlsxFilename).toFile();

            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet(title.length() > 31 ? title.substring(0, 31) : title);

                // Styles
                CellStyle headerStyle = workbook.createCellStyle();
                Font headerFont = workbook.createFont();
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
                headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                headerStyle.setBorderBottom(BorderStyle.THIN);

                CellStyle dataStyle = workbook.createCellStyle();
                dataStyle.setBorderBottom(BorderStyle.HAIR);

                // Header row
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers.get(i).asText());
                    cell.setCellStyle(headerStyle);
                    sheet.autoSizeColumn(i);
                }

                // Data rows
                int rowNum = 1;
                for (JsonNode row : rows) {
                    Row dataRow = sheet.createRow(rowNum++);
                    for (int i = 0; i < row.size(); i++) {
                        Cell cell = dataRow.createCell(i);
                        JsonNode val = row.get(i);
                        if (val.isNumber()) cell.setCellValue(val.doubleValue());
                        else cell.setCellValue(val.asText());
                        cell.setCellStyle(dataStyle);
                    }
                }

                // Auto-size columns after data
                for (int i = 0; i < headers.size(); i++) sheet.autoSizeColumn(i);

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    workbook.write(fos);
                }
            }

            String downloadUrl = baseUrl + "/" + xlsxFilename;
            log.info("Excel generated: {}", outputFile.getAbsolutePath());
            return ToolResult.artifact(
                    "Excel spreadsheet with " + rows.size() + " rows has been generated.",
                    downloadUrl, "EXCEL");
        } catch (Exception ex) {
            log.error("Excel generation failed", ex);
            return ToolResult.error("Failed to generate Excel: " + ex.getMessage());
        }
    }
}
