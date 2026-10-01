package no.metatrack.server.spreadsheet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;

@ApplicationScoped
public class SpreadsheetImportSupport {
    public static final String VERSION_MARKER = "TEMPLATE VERSION:";
    public static final String VERSION = "1";
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_COLUMNS = 512;
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final long MAX_EXPANDED_FILE_SIZE = 256L * 1024 * 1024;

    public SpreadsheetTable read(File file, boolean sampleTable) {
        if (file.length() > MAX_FILE_SIZE) throw new BadRequestException("Excel files must not exceed 20 MiB");
        try {
            // POI may allocate rows from sparse cell references, so inspect OOXML dimensions first.
            if (FileMagic.valueOf(file) == FileMagic.OOXML) preflightOoxml(file, MAX_EXPANDED_FILE_SIZE);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Unable to inspect Excel workbook", e);
        }
        try (Workbook workbook = WorkbookFactory.create(file, null, true)) {
            if (workbook.getNumberOfSheets() == 0) throw new BadRequestException("Workbook contains no worksheets");
            // Displayed values preserve identifiers such as zero-padded accession numbers.
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Sheet sheet = workbook.getSheetAt(0);
            List<List<String>> cells = readCells(sheet, formatter);
            int headerRow = firstNonEmpty(cells);
            if (headerRow < 0) throw new BadRequestException("First worksheet has no headers");
            boolean versioned = VERSION_MARKER.equalsIgnoreCase(first(cells.get(headerRow)));
            if (versioned) {
                List<String> version = cells.get(headerRow);
                if (version.size() < 2 || !VERSION.equals(version.get(1))) {
                    throw new BadRequestException("Unsupported Excel template version; download the current template");
                }
                headerRow++;
            }
            boolean marker = false;
            // Downloaded templates keep instructions above the actual table header.
            for (int i = headerRow; i < cells.size(); i++) {
                if ("METADATA FIELDS:".equalsIgnoreCase(first(cells.get(i)))) {
                    headerRow = i;
                    marker = true;
                    break;
                }
            }
            if (headerRow >= cells.size() || (versioned && !marker)) {
                throw new BadRequestException("Template is missing the METADATA FIELDS: header row");
            }
            List<String> headers = new ArrayList<>(cells.get(headerRow));
            if (marker && !headers.isEmpty()) headers.removeFirst();
            while (!headers.isEmpty() && headers.getLast().isBlank()) headers.removeLast();
            if (headers.isEmpty()) throw new BadRequestException("First worksheet has no usable headers");
            Set<String> uniqueHeaders = new HashSet<>();
            for (String header : headers) {
                if (header.isBlank() || !uniqueHeaders.add(header.toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("Blank or duplicate column in " + sheet.getSheetName() + ": '" + header + "'");
                }
            }

            Map<Long, Integer> rowNumbers = new LinkedHashMap<>();
            StringWriter text = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(text, CSVFormat.DEFAULT)) {
                printer.printRecord(headers);
                long number = 0;
                for (int i = headerRow + 1; i < cells.size(); i++) {
                    List<String> row = new ArrayList<>(cells.get(i));
                    if (marker && !row.isEmpty()) row.removeFirst();
                    if (row.stream().allMatch(String::isBlank)) continue;
                    if (row.size() > headers.size() && row.subList(headers.size(), row.size()).stream().anyMatch(v -> !v.isBlank())) {
                        throw new BadRequestException(sheet.getSheetName() + "!row " + (i + 1) + ": value has no column header");
                    }
                    while (row.size() < headers.size()) row.add("");
                    printer.printRecord(row.subList(0, headers.size()));
                    rowNumbers.put(++number, i + 1);
                }
            }
            List<CSVRecord> records;
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                    .setTrim(true).setIgnoreHeaderCase(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
            try (CSVParser parser = format.parse(new StringReader(text.toString()))) {
                records = parser.getRecords();
            }
            Map<String, List<String>> vocabularies = new LinkedHashMap<>();
            for (int i = 1; i < workbook.getNumberOfSheets(); i++) {
                List<List<String>> extra = readCells(workbook.getSheetAt(i), formatter);
                if (firstNonEmpty(extra) < 0) continue;
                if (!sampleTable) throw new BadRequestException("Experiment workbooks must not contain a vocabulary worksheet");
                if (i > 1) throw new BadRequestException("Sample workbooks support only the data sheet and a second vocabulary sheet");
                vocabularies = readVocabularies(extra, workbook.getSheetAt(i).getSheetName());
            }
            return new SpreadsheetTable(sheet.getSheetName(), List.copyOf(headers), records, rowNumbers, vocabularies);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Unable to read Excel workbook; use an unencrypted XLS or XLSX file", e);
        }
    }

    private Map<String, List<String>> readVocabularies(List<List<String>> rows, String sheetName) {
        int start = firstNonEmpty(rows);
        List<String> headers = rows.get(start);
        if (headers.size() < 2 || !"field_key".equalsIgnoreCase(headers.get(0)) || !"value".equalsIgnoreCase(headers.get(1))
                || headers.subList(2, headers.size()).stream().anyMatch(v -> !v.isBlank())) {
            throw new BadRequestException(sheetName + ": expected columns field_key and value");
        }
        Map<String, List<String>> terms = new LinkedHashMap<>();
        for (int i = start + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) continue;
            if (row.size() < 2 || row.get(0).isBlank() || row.get(1).isBlank()
                    || row.subList(2, row.size()).stream().anyMatch(v -> !v.isBlank())) {
                throw new BadRequestException(sheetName + "!row " + (i + 1) + ": provide a field_key and value only");
            }
            String key = row.get(0).toLowerCase(Locale.ROOT);
            List<String> values = terms.computeIfAbsent(key, unused -> new ArrayList<>());
            if (values.contains(row.get(1))) throw new BadRequestException(sheetName + "!row " + (i + 1) + ": duplicate term for " + key);
            values.add(row.get(1));
        }
        return terms;
    }

    private List<List<String>> readCells(Sheet sheet, DataFormatter formatter) {
        if (sheet.getLastRowNum() >= MAX_ROWS) throw new BadRequestException("Excel worksheets must not exceed " + MAX_ROWS + " rows");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            List<String> cells = new ArrayList<>();
            if (row != null) {
                if (row.getLastCellNum() > MAX_COLUMNS) throw new BadRequestException("Excel worksheets must not exceed " + MAX_COLUMNS + " columns");
                for (int j = 0; j < row.getLastCellNum(); j++) cells.add(cellValue(row.getCell(j), formatter));
            }
            rows.add(cells);
        }
        return rows;
    }

    private String cellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        String location = cell.getSheet().getSheetName() + "!" + new CellReference(cell).formatAsString(false);
        return switch (cell.getCellType()) {
            case FORMULA -> throw new BadRequestException(location + ": formulas are not supported; paste values instead");
            case ERROR -> throw new BadRequestException(location + ": cell contains an Excel error");
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : formatter.formatCellValue(cell).trim();
            default -> formatter.formatCellValue(cell).trim();
        };
    }

    private int firstNonEmpty(List<List<String>> rows) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).stream().anyMatch(v -> !v.isBlank())) return i;
        return -1;
    }

    private String first(List<String> row) {
        return row.isEmpty() ? "" : row.getFirst();
    }

    void preflightOoxml(File file, long maxExpandedSize) {
        try (ZipFile archive = new ZipFile(file)) {
            long expandedSize = 0;
            byte[] buffer = new byte[8192];
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream input = archive.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        expandedSize += read;
                        if (expandedSize > maxExpandedSize) {
                            throw new BadRequestException("Expanded Excel workbook content is too large");
                        }
                    }
                }
            }

            entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isWorksheet(entry.getName())) {
                    try (InputStream input = archive.getInputStream(entry)) {
                        validateWorksheetDimensions(input);
                    }
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (SAXException e) {
            throw new BadRequestException(e.getMessage(), e);
        } catch (Exception e) {
            throw new BadRequestException("Unable to inspect Excel workbook", e);
        }
    }

    private boolean isWorksheet(String entryName) {
        return entryName.startsWith("xl/worksheets/") && entryName.endsWith(".xml");
    }

    private void validateWorksheetDimensions(InputStream input) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.newSAXParser().parse(new InputSource(input), new WorksheetLimitHandler());
    }

    private static final class WorksheetLimitHandler extends DefaultHandler {
        private int physicalRows;
        private int cellsInRow;

        @Override
        public void startElement(String uri, String localName, String qualifiedName, Attributes attributes)
                throws SAXException {
            String element = localName.isEmpty() ? qualifiedName : localName;
            if ("row".equals(element)) {
                cellsInRow = 0;
                physicalRows++;
                int rowNumber = parsePositive(attributes.getValue("r"), physicalRows);
                if (physicalRows > MAX_ROWS || rowNumber > MAX_ROWS) {
                    throw new SAXException("Excel worksheets must not exceed " + MAX_ROWS + " rows");
                }
            } else if ("c".equals(element)) {
                cellsInRow++;
                String reference = attributes.getValue("r");
                int column = reference == null ? cellsInRow : new CellReference(reference).getCol() + 1;
                if (cellsInRow > MAX_COLUMNS || column > MAX_COLUMNS) {
                    throw new SAXException("Excel worksheets must not exceed " + MAX_COLUMNS + " columns");
                }
            }
        }

        private int parsePositive(String value, int fallback) {
            if (value == null) return fallback;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
