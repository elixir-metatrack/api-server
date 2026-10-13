package no.metatrack.server.spreadsheet;

import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.file.UploadStatus;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.vocabulary.SampleVocabularyResponse;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SpreadsheetExportWriterTest {
    @TempDir Path directory;
    private final SpreadsheetExportWriter writer = new SpreadsheetExportWriter();

    @Test
    void roundTripsAllSampleFieldsCustomValuesAndEligibleVocabularies() throws Exception {
        Sample sample = sample("00012");
        Map<String, String> expected = new LinkedHashMap<>();
        // Exercise every persisted scalar sample field, including distinct institution/collector aliases.
        for (var field : Sample.class.getDeclaredFields()) {
            Object value;
            if (field.getName().equals("name")) value = sample.name;
            else if (field.getType() == String.class) value = "value-" + field.getName();
            else if (field.getType() == Integer.class) value = 1234;
            else if (field.getType() == Double.class) value = 69.649208;
            else if (field.getType() == LocalDate.class) value = LocalDate.of(2024, 2, 29);
            else continue;
            field.set(sample, value);
            expected.put(field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT), value.toString());
        }
        sample.description = "=HYPERLINK(\"https://example.invalid\")\n\"quoted\"\ttext";
        expected.put("description", sample.description);
        var fields = List.of(field("group", SampleMetadataFieldType.TEXT),
                field("amount", SampleMetadataFieldType.NUMBER), field("flag", SampleMetadataFieldType.BOOLEAN),
                field("date", SampleMetadataFieldType.DATE), field("archived", SampleMetadataFieldType.TEXT));
        fields.getLast().archivedOn = java.time.Instant.EPOCH;
        var values = Map.<String, Object>of("group", "=control", "amount", new BigDecimal("12345678901234567890.12345"),
                "flag", false, "date", LocalDate.of(2026, 10, 13), "archived", "do not export");
        byte[] bytes = writer.samples(List.of(sample), fields, Map.of(sample.id, values),
                List.of(vocabulary("group", "=control", "treated"), vocabulary("archived", "old"),
                        vocabulary("host_sex", "female")));
        SpreadsheetTable table = read(bytes, true, Set.of("amount"));
        assertEquals(1, table.records().size());
        var record = table.records().getFirst();
        expected.forEach((key, value) -> assertEquals(value, record.get(key), key));
        for (String key : List.of("group", "amount", "flag", "date")) assertEquals(values.get(key).toString(), record.get(key));
        assertFalse(table.headers().contains("archived"));
        assertEquals(expected.size() + 4, table.headers().size());
        assertEquals(Map.of("group", List.of("=control", "treated")), table.vocabularies());
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(table.headers().indexOf("description") + 1).getCellType());
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(table.headers().indexOf("amount") + 1).getCellType());
            assertEquals(CellType.NUMERIC, sheet.getRow(2).getCell(table.headers().indexOf("latitude") + 1).getCellType());
        }
    }

    @Test
    void exportsEveryFileWithItsReadRoleAndKeepsSamplesWithoutFiles() throws Exception {
        Assay assay = new Assay();
        assay.id = UUID.randomUUID();
        assay.studyAccession = "PRJ0001";
        assay.sequencingPlatform = "ILLUMINA";
        assay.insertSize = 1234;
        Sample sample = sample("00012");
        Sample empty = sample("empty");
        var files = List.of(file(sample, assay, "single.fastq", ReadRole.SINGLE),
                file(sample, assay, "r1.fastq", ReadRole.FORWARD), file(sample, assay, "r2.fastq", ReadRole.REVERSE),
                file(sample, assay, "lane2-r1.fastq", ReadRole.FORWARD), file(sample, assay, "legacy.fastq", null));
        var table = read(writer.experiments(assay, List.of(sample, empty), files), false, Set.of());
        assertEquals(6, table.records().size());
        for (int index = 0; index < files.size(); index++) {
            var file = files.get(index);
            String prefix = file.readRole() == ReadRole.FORWARD ? "Forward " : file.readRole() == ReadRole.REVERSE ? "Reverse " : "";
            var record = table.records().get(index);
            assertEquals("00012", record.get("Sample"));
            assertEquals(file.name(), record.get(prefix + "File Name"));
            assertEquals("md5", record.get(prefix + "File md5"));
            assertEquals("plain-md5", record.get(prefix + "File Unencrypted md5"));
            assertEquals("PRJ0001", record.get("Study Accession"));
            assertEquals("ILLUMINA", record.get("Sequencing platform"));
            assertEquals("1234", record.get("Insert Size"));
        }
        assertEquals("empty", table.records().getLast().get("Sample"));
        assertEquals("", table.records().getLast().get("File Name"));
        assertTrue(table.vocabularies().isEmpty());
        assertFalse(table.headers().contains("objectKey"));
    }

    @Test
    void rejectsExcelCellAndColumnLimitsInsteadOfTruncatingData() {
        Sample sample = sample("sample");
        sample.description = "x".repeat(32768);
        assertThrows(BadRequestException.class, () -> writer.samples(List.of(sample), List.of(), Map.of(), List.of()));
        var fields = java.util.stream.IntStream.range(0, 512)
                .mapToObj(i -> field("field_" + i, SampleMetadataFieldType.TEXT)).toList();
        assertThrows(BadRequestException.class, () -> writer.samples(List.of(), fields, Map.of(), List.of()));
    }

    private SpreadsheetTable read(byte[] bytes, boolean sample, Set<String> numericHeaders) throws Exception {
        Path path = Files.write(directory.resolve("export.xlsx"), bytes);
        return new SpreadsheetImportSupport().read(path.toFile(), sample, numericHeaders);
    }

    private Sample sample(String name) {
        Sample sample = new Sample();
        sample.id = UUID.randomUUID();
        sample.name = name;
        return sample;
    }

    private SampleMetadataField field(String key, SampleMetadataFieldType type) {
        SampleMetadataField field = new SampleMetadataField();
        field.key = key;
        field.label = key;
        field.type = type;
        return field;
    }

    private SampleVocabularyResponse vocabulary(String key, String... terms) {
        return new SampleVocabularyResponse(UUID.randomUUID(), key, key, true, List.of(terms), null, null);
    }

    private FileResponse file(Sample sample, Assay assay, String name, ReadRole role) {
        return new FileResponse(UUID.randomUUID(), name, "private-path", "private-key", UploadStatus.UPLOADED,
                UUID.randomUUID(), sample.id, assay.id, role, "md5", "plain-md5");
    }
}
