package no.metatrack.server.spreadsheet;

import jakarta.ws.rs.BadRequestException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpreadsheetImportSupportTest {
    @TempDir Path directory;
    final SpreadsheetImportSupport reader = new SpreadsheetImportSupport();

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void readsTypedCellsSparseRowsAndVocabularies(String format) throws Exception {
        try (Workbook workbook = workbook(format)) {
            Sheet samples = workbook.createSheet("Samples");
            row(samples, 0, "name", "collection_date", "number", "flag", "group");
            Row data = row(samples, 3, "00012");
            Cell date = data.createCell(1);
            date.setCellValue(LocalDateTime.of(2026, 9, 22, 0, 0));
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            date.setCellStyle(dateStyle);
            data.createCell(2).setCellValue(12.5);
            data.createCell(3).setCellValue(true);
            data.createCell(4).setCellValue(" control ");
            row(samples, 5, "second");
            Sheet vocabulary = workbook.createSheet("Vocabularies");
            row(vocabulary, 0, "field_key", "value");
            row(vocabulary, 2, "group", "control");
            row(vocabulary, 4, "group", "treated");
            var table = reader.read(write(workbook, format).toFile(), true);
            assertEquals(2, table.records().size());
            var first = table.records().getFirst();
            assertEquals(List.of("00012", "2026-09-22", "12.5", "true", "control"), first.toList());
            assertEquals("Samples!row 4", table.location(first));
            assertEquals(List.of("second", "", "", "", ""), table.records().getLast().toList());
            assertEquals(Map.of("group", List.of("control", "treated")), table.vocabularies());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void preservesFormattedIdentifiersAndSkipsPreambleExamples(String format) throws Exception {
        try (Workbook workbook = workbook(format)) {
            Sheet sheet = workbook.createSheet("Samples");
            row(sheet, 0, "TEMPLATE VERSION:", "1");
            row(sheet, 1, "EXAMPLES:", "do-not-import");
            row(sheet, 2, "METADATA FIELDS:", "name", "alias");
            Row row = row(sheet, 3, "FILL TEMPLATE FROM THIS ROW:");
            Cell id = row.createCell(1);
            id.setCellValue(12);
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("00000"));
            id.setCellStyle(style);
            var table = reader.read(write(workbook, format).toFile(), true);
            assertEquals(1, table.records().size());
            assertEquals("00012", table.records().getFirst().get("name"));
            assertEquals("Samples!row 4", table.location(table.records().getFirst()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample", "sample_extended", "sample_virus", "experiment_PE", "experiment_SE"})
    void downloadedTemplatesRoundTripWithoutImportingExamples(String type) throws Exception {
        boolean experiment = type.startsWith("experiment_");
        byte[] bytes = new SpreadsheetTemplateService().create(type);
        Path file = directory.resolve(type + ".xlsx");
        Files.write(file, bytes);
        var empty = reader.read(file.toFile(), !experiment);
        assertTrue(empty.records().isEmpty());
        assertTrue(empty.vocabularies().isEmpty());
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(experiment ? 1 : 2, workbook.getNumberOfSheets());
            Sheet data = workbook.getSheetAt(0);
            Row header = data.getRow(4);
            int sampleColumn = -1;
            for (Cell cell : header) {
                if (List.of("Sample", "Sample Name").contains(cell.getStringCellValue())) sampleColumn = cell.getColumnIndex();
            }
            assertTrue(sampleColumn > 0);
            data.getRow(5).createCell(sampleColumn).setCellValue("real-sample");
            try (var output = Files.newOutputStream(file)) { workbook.write(output); }
        }
        var filled = reader.read(file.toFile(), !experiment);
        assertEquals(1, filled.records().size());
        assertEquals("real-sample", filled.records().getFirst().get(experiment ? "Sample" : "Sample Name"));
    }

    @Test
    void rejectsUnknownVersionBeforeReadingData() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Samples");
            row(sheet, 0, "TEMPLATE VERSION:", "99");
            row(sheet, 1, "METADATA FIELDS:", "name");
            assertTrue(assertThrows(BadRequestException.class, () -> reader.read(write(workbook, "xlsx").toFile(), true))
                    .getMessage().contains("version"));
        }
    }

    @Test
    void rejectsOversizedExpandedOoxmlBeforeOpeningWorkbook() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            row(workbook.createSheet("Samples"), 0, "name");
            Path file = write(workbook, "xlsx");
            var error = assertThrows(BadRequestException.class, () -> reader.preflightOoxml(file.toFile(), 1));
            assertTrue(error.getMessage().contains("too large"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {100_001, 0})
    void rejectsSparseRowsAndColumnsBeforeOpeningWorkbook(int rowIndex) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Samples");
            if (rowIndex > 0) row(sheet, rowIndex - 1, "outside-limit");
            else sheet.createRow(0).createCell(512).setCellValue("outside-limit");
            var error = assertThrows(BadRequestException.class,
                    () -> reader.read(write(workbook, "xlsx").toFile(), true));
            assertTrue(error.getMessage().contains(rowIndex > 0 ? "rows" : "columns"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void rejectsFormulasAndReportsCell(String format) throws Exception {
        try (Workbook workbook = workbook(format)) {
            Sheet sheet = workbook.createSheet("Samples");
            row(sheet, 0, "name");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            var error = assertThrows(BadRequestException.class, () -> reader.read(write(workbook, format).toFile(), true));
            assertTrue(error.getMessage().contains("A2"));
            assertTrue(error.getMessage().contains("formulas"));
        }
    }

    @Test
    void rejectsDuplicateHeadersAndDuplicateVocabularyTerms() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Samples");
            row(sheet, 0, "name", " NAME ");
            assertThrows(BadRequestException.class, () -> reader.read(write(workbook, "xlsx").toFile(), true));
            sheet.getRow(0).getCell(1).setCellValue("group");
            Sheet terms = workbook.createSheet("Vocabularies");
            row(terms, 0, "field_key", "value");
            row(terms, 1, "group", "control");
            row(terms, 2, "group", " control ");
            assertTrue(assertThrows(BadRequestException.class, () -> reader.read(write(workbook, "xlsx").toFile(), true))
                    .getMessage().contains("duplicate term"));
        }
    }

    @Test
    void rejectsMalformedVocabularyAndExtraDataSheets() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            row(workbook.createSheet("Samples"), 0, "name");
            Sheet vocab = workbook.createSheet("Vocabularies");
            row(vocab, 0, "wrong", "headers");
            assertThrows(BadRequestException.class, () -> reader.read(write(workbook, "xlsx").toFile(), true));
            row(vocab, 0, "field_key", "value");
            row(vocab, 1, "group", "");
            assertThrows(BadRequestException.class, () -> reader.read(write(workbook, "xlsx").toFile(), true));
            vocab.removeRow(vocab.getRow(1));
            row(workbook.createSheet("Extra"), 0, "unhandled");
            assertThrows(BadRequestException.class, () -> reader.read(write(workbook, "xlsx").toFile(), true));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void experimentsRejectVocabularySheet(String format) throws Exception {
        try (Workbook workbook = workbook(format)) {
            row(workbook.createSheet("Experiments"), 0, "Sample");
            row(workbook.createSheet("Vocabularies"), 0, "field_key", "value");
            assertThrows(BadRequestException.class, () -> reader.read(write(workbook, format).toFile(), false));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void detectsContentWhenBrowserSuppliesGenericMimeType(String format) throws Exception {
        try (Workbook workbook = workbook(format)) {
            row(workbook.createSheet("Samples"), 0, "name");
            Path path = write(workbook, format);
            FileUpload upload = mock(FileUpload.class);
            when(upload.filePath()).thenReturn(path);
            when(upload.fileName()).thenReturn("samples." + format);
            when(upload.contentType()).thenReturn("application/octet-stream");
            assertDoesNotThrow(() -> TableUploadSupport.validate(upload));
            assertTrue(TableUploadSupport.isWorkbook(path.toFile()));
        }
    }

    @Test
    void rejectsTextDisguisedAsExcelAndPreservesCsvMimeHandling() throws Exception {
        Path path = Files.writeString(directory.resolve("upload"), "name\nexample\n");
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(path);
        when(upload.fileName()).thenReturn("samples.xlsx");
        when(upload.contentType()).thenReturn("text/csv");
        assertThrows(BadRequestException.class, () -> TableUploadSupport.validate(upload));
        when(upload.fileName()).thenReturn("samples.csv");
        assertDoesNotThrow(() -> TableUploadSupport.validate(upload));
        assertFalse(TableUploadSupport.isWorkbook(path.toFile()));
    }

    static Row row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
        return row;
    }

    private Workbook workbook(String format) { return format.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook(); }

    private Path write(Workbook workbook, String format) throws Exception {
        Path path = directory.resolve("import." + format);
        try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        return path;
    }
}
