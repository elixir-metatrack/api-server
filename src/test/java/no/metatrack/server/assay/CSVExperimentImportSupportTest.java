package no.metatrack.server.assay;

import no.metatrack.server.csv.CSVImportSupport;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CSVExperimentImportSupportTest {
    private final CSVImportSupport support = new CSVImportSupport();

    @Test
    void parsesExperimentTemplatePreambleAndSemicolonValues() throws Exception {
        List<CSVRecord> records = support.prepareRecords(new StringReader(
                "\uFEFFREQUIREMENTS:;Mandatory;Mandatory\n"
                        + "EXAMPLES:;sample-1;100\n"
                        + "METADATA FIELDS:;Sample;Insert Size\n"
                        + "FILL TEMPLATE FROM THIS ROW:;sample-1;250\n"), ';');

        assertEquals(1, records.size());
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
}