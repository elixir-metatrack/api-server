package no.metatrack.server.csv;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class CSVImportSupport {
    public List<CSVRecord> prepareRecords(Reader reader, char delimiter) throws IOException {
        List<CSVRecord> allRecords;
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setTrim(true).get();
        try (CSVParser parser = format.parse(reader)) {
            allRecords = parser.getRecords();
        }

        int markerIndex = -1;
        for (int i = 0; i < allRecords.size(); i++) {
            CSVRecord record = allRecords.get(i);
            if (record.size() > 0 && "METADATA FIELDS:".equalsIgnoreCase(record.get(0).trim())) {
                markerIndex = i;
                break;
            }
        }

        if (markerIndex < 0) {
            if (allRecords.isEmpty()) {
                throw new BadRequestException("CSV file has no header record");
            }
            return parseMappedRecords(allRecords.get(0).toList(), allRecords.subList(1, allRecords.size()), delimiter, false);
        }

        List<String> headers = withoutFirstField(allRecords.get(markerIndex));
        if (headers.stream().allMatch(String::isBlank)) {
            throw new BadRequestException("METADATA FIELDS: record has no usable headers");
        }
        return parseMappedRecords(headers, allRecords.subList(markerIndex + 1, allRecords.size()), delimiter, true);
    }

    public char detectDelimiter(File file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != 0xFEFF) {
                reader.reset();
            }
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) {
                return ',';
            }
            long tabs = firstLine.chars().filter(c -> c == '\t').count();
            long commas = firstLine.chars().filter(c -> c == ',').count();
            long semicolons = firstLine.chars().filter(c -> c == ';').count();
            if (tabs > commas && tabs > semicolons) return '\t';
            if (semicolons > commas) return ';';
            return ',';
        }
    }

    public String getMappedValue(CSVRecord record, String... headers) {
        for (String header : headers) {
            Integer index = findHeaderIndex(record, header);
            if (index != null) return index < record.size() ? record.get(index) : null;
        }
        return null;
    }

    public String normalizeHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    private List<CSVRecord> parseMappedRecords(
            List<String> headers, List<CSVRecord> dataRecords, char delimiter, boolean removeFirstField) throws IOException {
        StringWriter content = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(content, CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get())) {
            printer.printRecord(headers);
            for (CSVRecord dataRecord : dataRecords) {
                printer.printRecord(removeFirstField ? withoutFirstField(dataRecord) : dataRecord.toList());
            }
        }
        CSVFormat mappedFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).setTrim(true)
                .setIgnoreHeaderCase(true).setAllowMissingColumnNames(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        try (CSVParser parser = mappedFormat.parse(new StringReader(content.toString()))) {
            return parser.getRecords();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("CSV file contains duplicate headers", e);
        }
    }

    private List<String> withoutFirstField(CSVRecord record) {
        return record.size() <= 1 ? List.of() : record.toList().subList(1, record.size());
    }

    private Integer findHeaderIndex(CSVRecord record, String expectedHeader) {
        String normalized = normalizeHeader(expectedHeader);
        Integer match = null;
        List<String> headers = record.getParser().getHeaderNames();
        for (int i = 0; i < headers.size(); i++) {
            if (normalizeHeader(headers.get(i)).equals(normalized)) {
                if (match != null) throw new BadRequestException("Duplicate metadata column '" + expectedHeader + "'");
                match = i;
            }
        }
        return match;
    }
}