package no.metatrack.server.spreadsheet;

import org.apache.commons.csv.CSVRecord;

import java.util.List;
import java.util.Map;

public record SpreadsheetTable(
        String sheetName, List<String> headers, List<CSVRecord> records,
        Map<Long, Integer> rowNumbers, Map<String, List<String>> vocabularies) {
    public String location(CSVRecord record) {
        return sheetName + "!row " + rowNumbers.get(record.getRecordNumber());
    }
}
