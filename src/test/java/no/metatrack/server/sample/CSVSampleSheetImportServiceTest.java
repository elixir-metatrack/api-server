package no.metatrack.server.sample;

import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CSVSampleSheetImportServiceTest {
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
}