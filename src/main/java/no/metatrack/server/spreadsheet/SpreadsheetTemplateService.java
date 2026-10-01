package no.metatrack.server.spreadsheet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@ApplicationScoped
public class SpreadsheetTemplateService {
    private static final Map<String, String> TEMPLATES = Map.of(
            "sample", "samples/sample.csv", "sample_extended", "samples/sample_extended.csv",
            "sample_virus", "samples/sample_virus.csv",
            "experiment_PE", "experiments/experiment_PE.csv",
            "experiment_SE", "experiments/experiment_SE.csv");

    public byte[] create(String type) {
        String path = TEMPLATES.get(type);
        if (path == null) throw new NotFoundException("Unknown template");
        try (var source = getClass().getResourceAsStream("/META-INF/resources/templates/" + path);
             var workbook = new XSSFWorkbook();
             var output = new ByteArrayOutputStream()) {
            if (source == null) throw new NotFoundException("Template not found");
            boolean experiment = type.startsWith("experiment_");
            Sheet sheet = workbook.createSheet(experiment ? "Experiments" : "Samples");
            CellStyle heading = workbook.createCellStyle();
            heading.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            heading.setFont(font);
            CellStyle wrapped = workbook.createCellStyle();
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            Row version = sheet.createRow(0);
            version.createCell(0).setCellValue(SpreadsheetImportSupport.VERSION_MARKER);
            version.createCell(1).setCellValue(SpreadsheetImportSupport.VERSION);
            version.createCell(2).setCellValue("Enter data below METADATA FIELDS. Keep the header rows. Use values, not formulas.");
            version.getCell(2).setCellStyle(wrapped);
            version.setHeightInPoints(44);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 2, 6));
            try (CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(';').get()
                    .parse(new InputStreamReader(source, StandardCharsets.UTF_8))) {
                int index = 1;
                for (var record : parser) {
                    Row row = sheet.createRow(index++);
                    boolean headers = record.get(0).replace("\uFEFF", "").equals("METADATA FIELDS:");
                    for (int col = 0; col < record.size(); col++) {
                        Cell cell = row.createCell(col);
                        cell.setCellValue(record.get(col).replace("\uFEFF", ""));
                        cell.setCellStyle(headers || col == 0 ? heading : wrapped);
                        sheet.setColumnWidth(col, (col == 0 ? 30 : 24) * 256);
                        sheet.setDefaultColumnStyle(col, text);
                    }
                    row.setHeightInPoints(headers ? 36 : record.get(0).equals("DESCRIPTIONS:") ? 100 : 46);
                    if (headers) {
                        sheet.createFreezePane(1, index);
                        Row data = sheet.createRow(index);
                        data.createCell(0).setCellValue("FILL TEMPLATE FROM THIS ROW:");
                        break;
                    }
                }
            }
            if (!experiment) {
                Sheet vocabularies = workbook.createSheet("Vocabularies");
                Row headers = vocabularies.createRow(0);
                headers.createCell(0).setCellValue("field_key");
                headers.createCell(1).setCellValue("value");
                headers.forEach(cell -> cell.setCellStyle(heading));
                vocabularies.setColumnWidth(0, 36 * 256);
                vocabularies.setColumnWidth(1, 42 * 256);
                vocabularies.setDefaultColumnStyle(0, text);
                vocabularies.setDefaultColumnStyle(1, text);
                vocabularies.createFreezePane(0, 1);
                addComment(workbook, headers.getCell(0), "Optional: use the key of an existing custom TEXT attribute. Add the same key as a column in the Samples sheet. Built-in attributes are not allowed. Example: treatment_group.");
                addComment(workbook, headers.getCell(1), "One allowed value per row. Repeat the field_key for each value, e.g. control and treated. Leave this sheet empty if no vocabularies are needed. New definitions require project ADMIN rights; existing definitions must match.");
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate Excel template", e);
        }
    }

    private void addComment(Workbook workbook, Cell cell, String text) {
        CreationHelper helper = workbook.getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow1(1);
        anchor.setRow2(7);
        Comment comment = cell.getSheet().createDrawingPatriarch().createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        comment.setAuthor("MetaTrack");
        cell.setCellComment(comment);
    }
}
