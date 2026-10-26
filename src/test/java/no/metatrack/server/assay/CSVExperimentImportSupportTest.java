package no.metatrack.server.assay;

import no.metatrack.server.csv.CSVImportSupport;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CSVExperimentImportSupportTest {
    private final CSVImportSupport support = new CSVImportSupport();

    @Test
    void parsesHeaderFirstSemicolonValuesAndLeadingBom() throws Exception {
        List<CSVRecord> records = support.prepareRecords(new StringReader(
                "\uFEFFSample;Insert Size\nsample-1;250\n"), ';');

        assertEquals(1, records.size());
        assertEquals("Sample", records.getFirst().getParser().getHeaderNames().getFirst());
        assertEquals("sample-1", support.getMappedValue(records.get(0), "sample"));
        assertEquals("250", support.getMappedValue(records.get(0), "Insert Size"));
    }

    @Test
    void parsesHeaderFirstCsvAndTsv() throws Exception {
        List<CSVRecord> csv = support.prepareRecords(new StringReader("Sample,Insert Size\nsample-1,250\n"), ',');
        List<CSVRecord> tsv = support.prepareRecords(new StringReader("Sample\tInsert Size\nsample-2\t300\n"), '\t');

        assertEquals("sample-1", support.getMappedValue(csv.get(0), "Sample"));
        assertEquals("300", support.getMappedValue(tsv.get(0), "insert size"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"experiment_PE.csv", "experiment_SE.csv"})
    void parsesShippedExperimentTemplateHeadersWithData(String filename) throws Exception {
        String template;
        try (var input = getClass().getResourceAsStream(
                "/META-INF/resources/templates/experiments/" + filename)) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        }
        String[] headers = template.split(";", -1);
        String row = "sample-1" + ";".repeat(headers.length - 1);

        List<CSVRecord> records = support.prepareRecords(new StringReader(template + "\n" + row), ';');

        assertEquals(headers.length, records.getFirst().size());
        assertEquals("sample-1", support.getMappedValue(records.getFirst(), "Sample"));
    }
}