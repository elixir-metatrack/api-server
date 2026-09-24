package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import no.metatrack.server.sample.vocabulary.SampleVocabularyValidationException;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class CSVSampleSheetImportServiceTest {
    @TempDir
    Path directory;

    @Test
    void validRowsUseApiCreationWhileInvalidRowsRemainIsolated() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.sampleService = mock(SampleService.class, invocation -> {
            if ("second".equals(invocation.getArgument(1))) {
                throw new SampleVocabularyValidationException(List.of(new SampleValidationViolation(
                        "second", "host_sex", "male", "Invalid vocabulary value")));
            }
            return null;
        });
        Path csv = Files.writeString(directory.resolve("samples.csv"),
                "Sample Name,Tax ID,Host Sex,custom_status\n"
                        + "first,287,female,known\n"
                        + "bad,not-an-integer,male,unknown\n"
                        + "second,288,male,unknown\n");
        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            var errors = service.importNewSamples(1L, csv.toFile());
            assertEquals(2, errors.size());
            assertEquals("tax_id", errors.get(0).fieldKey());
            assertEquals("host_sex", errors.get(1).fieldKey());
            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample"))
                    .toList();
            assertEquals(List.of("first", "second"), calls.stream()
                    .map(invocation -> (String) invocation.getArgument(1)).toList());
            assertEquals(Integer.valueOf(287), calls.getFirst().getArgument(3));
            assertEquals("female", calls.getFirst().getArgument(24));
        }
    }

    @Test
    void ignoresTemplatePreambleAndAlignmentColumn() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> records = service.prepareRecords(new StringReader(
                "REQUIREMENTS:;Mandatory;Mandatory\n"
                        + "NOTES:;\"quoted; instruction\"\n"
                        + "METADATA FIELDS:;Tax ID;Sample Name\n"
                        + "alignment;287;NorPa_0001\n"), ';');

        assertEquals(1, records.size());
        assertEquals("287", records.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", records.get(0).get("Sample Name"));
        assertFalse(records.get(0).isMapped("METADATA FIELDS:"));
        assertFalse(records.get(0).isMapped("alignment"));
    }

    @Test
    void supportsBomPrefixedTemplate() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        BufferedReader reader = new BufferedReader(new StringReader(
                "\uFEFFREQUIREMENTS:;Mandatory\nMETADATA FIELDS:;Sample Name\nalignment;NorPa_0001\n"));
        reader.read();

        List<CSVRecord> records = service.prepareRecords(reader, ';');

        assertEquals("NorPa_0001", records.get(0).get("Sample Name"));
    }

    @Test
    void supportsTemplateTsv() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> records = service.prepareRecords(new StringReader(
                "REQUIREMENTS:\tMandatory\n"
                        + "METADATA FIELDS:\tTax ID\tSample Name\n"
                        + "alignment\t287\tNorPa_0001\n"), '\t');

        assertEquals("287", records.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", records.get(0).get("Sample Name"));
    }

    @Test
    void preservesAppendedTemplateColumnsAndUnevenRows() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> records = service.prepareRecords(new StringReader(
                "REQUIREMENTS:;Mandatory;Optional;Optional\n"
                        + "METADATA FIELDS:;Sample Name;custom_status;Unknown\n"
                        + "alignment;sample-1;known;ignored\n"
                        + "alignment;sample-2\n"), ';');

        assertEquals("known", records.get(0).get("custom_status"));
        assertEquals("ignored", records.get(0).get("Unknown"));
        assertEquals(null, service.getCustomMetadataValue(records.get(1), metadataField("custom_status", "Status")));
    }

    @Test
    void resolvesCustomMetadataByKeyAndLabelWithKeyPrecedence() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField field = metadataField("custom_status", "Status");

        CSVRecord keyRecord = service.prepareRecords(
                new StringReader("  CUSTOM_STATUS  ,Status\nkey-value,label-value\n"), ',').get(0);
        CSVRecord labelRecord = service.prepareRecords(
                new StringReader("Status\nlabel-value\n"), ',').get(0);

        assertEquals("key-value", service.getCustomMetadataValue(keyRecord, field));
        assertEquals("label-value", service.getCustomMetadataValue(labelRecord, field));
    }

    @Test
    void ignoresAmbiguousCustomMetadataLabels() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField builtInCollision = metadataField("custom_status", "Sample Name");
        SampleMetadataField keyCollision = metadataField("sample_name", "Status");
        SampleMetadataField otherField = metadataField("status", "Other Status");
        CSVRecord record = service.prepareRecords(
                new StringReader("Sample Name,Status\nsample-1,status-value\n"), ',').get(0);

        assertEquals(null, service.getCustomMetadataValue(record, builtInCollision));
        assertEquals(null, service.getCustomMetadataValue(record, keyCollision, List.of(keyCollision, otherField)));
    }

    @Test
    void keyMatchRemainsAuthoritativeWhenLabelIsAmbiguous() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField field = metadataField("custom_status", "Sample Name");
        CSVRecord record = service.prepareRecords(
                new StringReader("custom_status,Sample Name\nkey-value,label-value\n"), ',').get(0);

        assertEquals("key-value", service.getCustomMetadataValue(record, field));
    }

    @Test
    void rejectsDuplicateCustomHeaders() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        assertThrows(BadRequestException.class, () -> service.prepareRecords(
                new StringReader("custom_status,custom_status\nkey-value,other-value\n"), ','));
    }

    @Test
    void preservesHeaderFirstCsvAndTsv() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> csvRecords = service.prepareRecords(
                new StringReader("Tax ID,Sample Name\n287,NorPa_0001\n"), ',');
        List<CSVRecord> tsvRecords = service.prepareRecords(
                new StringReader("Tax ID\tSample Name\n287\tNorPa_0001\n"), '\t');

        assertEquals("287", csvRecords.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", csvRecords.get(0).get("Sample Name"));
        assertEquals("287", tsvRecords.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", tsvRecords.get(0).get("Sample Name"));
    }

    @Test
    void rejectsMarkerWithoutUsableHeaders() {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        assertThrows(BadRequestException.class, () -> service.prepareRecords(
                new StringReader("REQUIREMENTS:;Mandatory\nMETADATA FIELDS:\n"), ';'));
    }

    @Test
    void exposesCanonicalKeysForBuiltInAliases() {
        Sample sample = new Sample();
        sample.sequencingLab = "Lab A";
        sample.isolationSource = "swab";
        sample.hostSex = "female";

        Map<String, String> values = CSVSampleSheetImportService.builtInValues(sample);

        assertEquals("Lab A", values.get("sequencing_lab"));
        assertEquals("swab", values.get("isolation_source"));
        assertEquals("female", values.get("host_sex"));
        assertTrue(values.keySet().stream().noneMatch(key -> key.contains(" ")));
    }

    @Test
    void validatesMultipleRowsAgainstOneLoadedRuleSnapshot() {
        SampleVocabularyRules rules = new SampleVocabularyRules(
                Set.of("custom_status"), Map.of("host_sex", Set.of("female"), "custom_status", Set.of("known")));

        assertTrue(SampleVocabularyService.validate(
                        rules, "sample-1", Map.of("host_sex", "female"), Map.of("custom_status", "known"))
                .isEmpty());
        assertEquals(2, SampleVocabularyService.validate(
                        rules, "sample-2", Map.of("host_sex", "male"), Map.of("custom_status", "unknown"))
                .size());
    }

    private SampleMetadataField metadataField(String key, String label) {
        SampleMetadataField field = new SampleMetadataField();
        field.key = key;
        field.label = label;
        field.type = SampleMetadataFieldType.TEXT;
        return field;
    }
}