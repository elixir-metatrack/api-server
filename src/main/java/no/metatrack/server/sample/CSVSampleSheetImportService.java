package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.CreateSampleMetadataFieldRequest;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
import no.metatrack.server.sample.vocabulary.SampleVocabularyValidationException;
import no.metatrack.server.sample.vocabulary.SpreadsheetVocabularyImportService;
import no.metatrack.server.spreadsheet.SpreadsheetImportSupport;
import no.metatrack.server.spreadsheet.SpreadsheetTable;
import no.metatrack.server.spreadsheet.TableUploadSupport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CSVSampleSheetImportService {

    private static final Set<String> BUILT_IN_HEADERS = Set.of(
            "name", "sample name", "alias", "mlst", "tax_id", "tax id", "host_tax_id", "host tax id",
            "isolation_source", "isolation source", "collection_date", "collection date", "location",
            "geographic location", "sequencing_lab", "collected by", "institution", "collecting institution",
            "host_health_state", "host health state", "project_title", "project title", "description", "isolate",
            "collected_by", "latitude", "longitude", "environmental_sample", "environmental sample",
            "host_associated", "host associated", "host_common_name", "host common name", "host_subject_id",
            "host subject id", "collector_name", "collector name", "host_sex", "host sex", "influenza_test_method",
            "influenza test method", "influenza_test_result", "influenza test result", "other_pathogens_tested",
            "other pathogens tested", "other_pathogens_test_result", "other pathogens test result", "host_habitat",
            "host habitat", "isolation_source_host_associated", "isolation source host-associated", "host_behaviour",
            "host behaviour", "isolation_source_non_host_associated", "isolation source non-host-associated",
            "influenza_virus_type", "influenza virus type", "influenza_sub_type", "influenza sub type", "serovar",
            "strain", "host_age", "host age", "county", "commune", "hospital_health_institution",
            "hospital/health institution", "collecting_institution", "project_accession", "project accession",
            "study_accession", "study accession", "sample_accession", "sample accession",
            "secondary_accession", "secondary accession");

    @Inject
    SampleMetadataService metadataService;

    @Inject
    SampleMetadataFieldService metadataFieldService;

    @Inject
    SampleService sampleService;

    @Inject
    SampleVocabularyService vocabularyService;

    @Inject
    SpreadsheetImportSupport spreadsheetSupport;

    @Inject
    SpreadsheetVocabularyImportService spreadsheetVocabularies;

    // Prefer day/month for ambiguous slash dates, preserving the existing import convention.
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("M/d/uu").withResolverStyle(ResolverStyle.STRICT));

    private record ParsedRecords(List<String> headers, List<CSVRecord> records) {}

    private record MetadataColumn(String header, int index, SampleMetadataField field, String generatedKey) {}

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public List<SampleValidationViolation> importNewSamples(Long projectId, File file) {
        List<SampleValidationViolation> errors = new ArrayList<>();
        Project targetProject = Project.<Project>findByIdOptional(projectId).orElseThrow(NotFoundException::new);
        Project project = targetProject.isSubProject() ? targetProject.parentProject : targetProject;
        List<SampleMetadataField> customFields = SampleMetadataField.list(
                "project.id = ?1 and archivedOn is null order by key", project.id);
        List<SampleMetadataField> archivedFields = SampleMetadataField.list(
                "project.id = ?1 and archivedOn is not null order by key", project.id);
        Set<String> customNumberHeaders = new HashSet<>();
        for (SampleMetadataField field : customFields) {
            if (field.type == SampleMetadataFieldType.NUMBER) {
                customNumberHeaders.add(field.key);
                if (!isAmbiguousLabel(field, customFields)) customNumberHeaders.add(field.label);
            }
        }
        SpreadsheetTable spreadsheet = TableUploadSupport.isWorkbook(file)
                ? spreadsheetSupport.read(file, true, customNumberHeaders) : null;
        List<CSVRecord> records;
        List<String> headers;
        if (spreadsheet != null) {
            validateSpreadsheet(spreadsheet, customFields);
            records = spreadsheet.records();
            headers = spreadsheet.headers();
        } else {
            ParsedRecords parsed = readCsvRecords(file);
            records = parsed.records();
            headers = parsed.headers();
        }
        List<MetadataColumn> metadataColumns = resolveMetadataColumns(headers, customFields, archivedFields);
        if (spreadsheet != null) spreadsheetVocabularies.apply(project.id, spreadsheet, customFields);
        SampleVocabularyRules vocabularyRules = vocabularyService.loadRules(project.id);
        Set<String> namesInFile = new HashSet<>();
        Set<String> createdMetadataFields = new HashSet<>();

        for (CSVRecord rec : records) {
            String name = getMappedValue(rec, "name", "Sample Name");
            String row = spreadsheet == null
                    ? "Row " + rec.getRecordNumber()
                    : spreadsheet.location(rec);
            String rowLabel = name == null || name.isBlank() || spreadsheet == null
                    ? (name == null || name.isBlank() ? row : name)
                    : name + " (" + row + ")";

            if (name == null || name.isBlank()) {
                errors.add(new SampleValidationViolation(
                        row, "name", name, "Name column is missing or empty"));
                continue;
            }

            if (!namesInFile.add(name)) {
                errors.add(new SampleValidationViolation(
                        rowLabel, "name", name, "Duplicate sample name within this file: '" + name + "'"));
                continue;
            }

            List<SampleValidationViolation> rowErrors = new ArrayList<>();

            Sample sample = new Sample();
            sample.name = name;
            sample.alias = getMappedValue(rec, "alias");
            sample.taxId = parseOptionalInt(rec, new String[] {"tax_id", "Tax ID"}, rowLabel, rowErrors);
            sample.hostTaxId =
                    parseOptionalInt(rec, new String[] {"host_tax_id", "Host Tax ID"}, rowLabel, rowErrors);
            sample.mlst = getMappedValue(rec, "mlst", "MLST");
            sample.isolationSource = getMappedValue(rec, "isolation_source", "Isolation Source");

            String rawDate = getMappedValue(rec, "collection_date", "Collection Date");
            if (rawDate != null && !rawDate.isBlank()) {
                try {
                    sample.collectionDate = parseCollectionDate(rawDate.trim());
                } catch (DateTimeParseException e) {
                    rowErrors.add(new SampleValidationViolation(
                            rowLabel,
                            "collection_date",
                            rawDate,
                            "Invalid date '" + rawDate
                                    + "'. Accepted formats: yyyy-MM-dd, d/M/yyyy, M/d/yyyy (also two-digit years)."
                                    + " Ambiguous slash dates use day/month/year."));
                }
            }

            sample.location = getMappedValue(rec, "location", "Geographic Location");
            sample.sequencingLab = getMappedValue(rec, "sequencing_lab", "Collected By");
            sample.institution = getMappedValue(rec, "institution", "Collecting Institution");
            sample.hostHealthState = getMappedValue(rec, "host_health_state", "Host Health State");

            sample.projectTitle = getMappedValue(rec, "project_title", "Project Title");
            sample.description = getMappedValue(rec, "description", "Description");
            sample.isolate = getMappedValue(rec, "isolate", "Isolate");
            sample.collectedBy = getMappedValue(rec, "collected_by", "Collected By");
            sample.latitude = parseOptionalDouble(rec, new String[] {"latitude", "Latitude"}, rowLabel, rowErrors);
            sample.longitude =
                    parseOptionalDouble(rec, new String[] {"longitude", "Longitude"}, rowLabel, rowErrors);
            sample.environmentalSample = getMappedValue(rec, "environmental_sample", "Environmental Sample");
            sample.hostAssociated = getMappedValue(rec, "host_associated", "Host Associated");
            sample.hostCommonName = getMappedValue(rec, "host_common_name", "Host Common Name");
            sample.hostSubjectId = getMappedValue(rec, "host_subject_id", "Host Subject ID");
            sample.collectorName = getMappedValue(rec, "collector_name", "Collector Name");
            sample.collectingInstitution =
                    getMappedValue(rec, "collecting_institution", "Collecting Institution");
            sample.hostSex = getMappedValue(rec, "host_sex", "Host Sex");
            sample.influenzaTestMethod = getMappedValue(rec, "influenza_test_method", "Influenza Test Method");
            sample.influenzaTestResult = getMappedValue(rec, "influenza_test_result", "Influenza Test Result");
            sample.otherPathogensTested =
                    getMappedValue(rec, "other_pathogens_tested", "Other Pathogens Tested");
            sample.otherPathogensTestResult =
                    getMappedValue(rec, "other_pathogens_test_result", "Other Pathogens Test Result");
            sample.hostHabitat = getMappedValue(rec, "host_habitat", "Host Habitat");
            sample.isolationSourceHostAssociated =
                    getMappedValue(rec, "isolation_source_host_associated", "Isolation Source Host-Associated");
            sample.hostBehaviour = getMappedValue(rec, "host_behaviour", "Host Behaviour");
            sample.isolationSourceNonHostAssociated = getMappedValue(
                    rec, "isolation_source_non_host_associated", "Isolation Source Non-Host-Associated");
            sample.influenzaVirusType = getMappedValue(rec, "influenza_virus_type", "Influenza Virus Type");
            sample.influenzaSubType = getMappedValue(rec, "influenza_sub_type", "Influenza Sub Type");
            sample.serovar = getMappedValue(rec, "serovar", "Serovar");
            sample.strain = getMappedValue(rec, "strain", "Strain");
            sample.hostAge = getMappedValue(rec, "host_age", "Host Age");
            sample.county = getMappedValue(rec, "county", "County");
            sample.commune = getMappedValue(rec, "commune", "Commune");
            sample.hospitalHealthInstitution =
                    getMappedValue(rec, "hospital_health_institution", "Hospital/Health institution");
            sample.projectAccession = getMappedValue(rec, "project_accession", "Project Accession");
            sample.studyAccession = getMappedValue(rec, "study_accession", "Study Accession");
            sample.sampleAccession = getMappedValue(rec, "sample_accession", "Sample Accession");
            sample.secondaryAccession = getMappedValue(rec, "secondary_accession", "Secondary Accession");

            Map<String, Object> customMetadata = new LinkedHashMap<>();
            for (MetadataColumn column : metadataColumns) {
                String rawValue = valueAt(rec, column.index());
                if (rawValue == null || rawValue.isBlank()) continue;
                try {
                    if (column.field() != null) {
                        customMetadata.put(column.field().key,
                                metadataService.parseCsvValue(column.field().type, rawValue));
                    } else {
                        customMetadata.put(column.generatedKey(),
                                metadataService.parseCsvValue(SampleMetadataFieldType.TEXT, rawValue));
                    }
                } catch (BadRequestException e) {
                    String key = column.field() == null ? column.generatedKey() : column.field().key;
                    rowErrors.add(new SampleValidationViolation(rowLabel, key, rawValue, e.getMessage()));
                }
            }

            if (rowErrors.isEmpty()) {
                rowErrors.addAll(SampleVocabularyService.validate(
                        vocabularyRules, rowLabel, builtInValues(sample), customMetadata));
            }

            if (rowErrors.isEmpty()) {
                boolean metadataCreationFailed = false;
                for (MetadataColumn column : metadataColumns) {
                    String key = column.generatedKey();
                    if (key == null || !customMetadata.containsKey(key) || createdMetadataFields.contains(key)) {
                        continue;
                    }
                    try {
                        metadataFieldService.create(project.id,
                                new CreateSampleMetadataFieldRequest(
                                        key, column.header().trim(), SampleMetadataFieldType.TEXT, null));
                        createdMetadataFields.add(key);
                    } catch (WebApplicationException e) {
                        if (e.getResponse().getStatus() < 400 || e.getResponse().getStatus() >= 500) throw e;
                        errors.add(new SampleValidationViolation(rowLabel, key,
                                customMetadata.get(key).toString(),
                                "Unable to create custom metadata field for column '" + column.header()
                                        + "': " + e.getMessage()));
                        metadataCreationFailed = true;
                        break;
                    }
                }
                if (metadataCreationFailed) continue;
                try {
                    sampleService.createSample(projectId, sample.name, sample.alias, sample.taxId,
                            sample.hostTaxId, sample.mlst, sample.location, sample.sequencingLab,
                            sample.institution, sample.isolationSource, sample.collectionDate,
                            sample.hostHealthState, sample.projectTitle, sample.description, sample.isolate,
                            sample.collectedBy, sample.latitude, sample.longitude, sample.environmentalSample,
                            sample.hostAssociated, sample.hostCommonName, sample.hostSubjectId,
                            sample.collectorName, sample.collectingInstitution, sample.hostSex,
                            sample.influenzaTestMethod, sample.influenzaTestResult, sample.otherPathogensTested,
                            sample.otherPathogensTestResult, sample.hostHabitat,
                            sample.isolationSourceHostAssociated, sample.hostBehaviour,
                            sample.isolationSourceNonHostAssociated, sample.influenzaVirusType,
                            sample.influenzaSubType, sample.serovar, sample.strain, sample.hostAge,
                            sample.county, sample.commune, sample.hospitalHealthInstitution,
                            sample.projectAccession, sample.studyAccession, sample.sampleAccession,
                            sample.secondaryAccession, customMetadata);
                } catch (SampleVocabularyValidationException e) {
                    for (SampleValidationViolation violation : e.violations()) {
                        errors.add(new SampleValidationViolation(
                                rowLabel, violation.fieldKey(), violation.rejectedValue(), violation.message()));
                    }
                } catch (WebApplicationException e) {
                    if (e.getResponse().getStatus() != 409) throw e;
                    errors.add(new SampleValidationViolation(rowLabel, "name", name,
                            "Sample name '" + name + "' already exists in this project"));
                }
            } else {
                errors.addAll(rowErrors);
            }
        }

        return errors;
    }

    private LocalDate parseCollectionDate(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try each format separately: optional sections do not provide parsing alternatives.
            }
        }
        throw new DateTimeParseException("Invalid collection date", value, 0);
    }

    private ParsedRecords readCsvRecords(File file) {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != 0xFEFF) reader.reset();
            return prepareRecordsWithHeaders(reader, detectDelimiter(file));
        } catch (IOException e) {
            throw new BadRequestException("Unable to read CSV file", e);
        }
    }

    void validateSpreadsheet(SpreadsheetTable table, List<SampleMetadataField> customFields) {
        if (!hasHeader(table.headers(), "name", "Sample Name")) {
            throw new BadRequestException("Samples worksheet must contain a name or Sample Name column");
        }
        if (table.records().isEmpty()) {
            throw new BadRequestException("Samples worksheet contains no data rows");
        }
    }

    private List<MetadataColumn> resolveMetadataColumns(
            List<String> headers,
            List<SampleMetadataField> activeFields,
            List<SampleMetadataField> archivedFields) {
        List<SampleMetadataField> allFields = new ArrayList<>(activeFields);
        allFields.addAll(archivedFields);
        List<MetadataColumn> columns = new ArrayList<>();
        Map<String, String> generatedKeys = new LinkedHashMap<>();
        Map<String, String> normalizedHeaders = new LinkedHashMap<>();

        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index);
            String normalizedHeader = normalizeHeader(header);
            if (normalizedHeader.isEmpty()) continue;
            String previousHeader = normalizedHeaders.putIfAbsent(normalizedHeader, header);
            if (previousHeader != null) {
                throw new BadRequestException("Columns '" + previousHeader + "' and '" + header
                        + "' are identical after trimming and case normalization. Keep only one of these columns.");
            }
            if (BUILT_IN_HEADERS.contains(normalizedHeader)) continue;

            SampleMetadataField keyMatch = findFieldByKey(activeFields, normalizedHeader);
            if (keyMatch != null) {
                if (findHeaderIndex(headers, keyMatch.key) == index) {
                    columns.add(new MetadataColumn(header, index, keyMatch, null));
                }
                continue;
            }

            SampleMetadataField archivedKeyMatch = findFieldByKey(archivedFields, normalizedHeader);
            if (archivedKeyMatch != null) throw archivedFieldError(header, archivedKeyMatch);

            List<SampleMetadataField> labelMatches = allFields.stream()
                    .filter(field -> normalizeHeader(field.label).equals(normalizedHeader))
                    .toList();
            if (labelMatches.size() > 1) {
                String matches = labelMatches.stream()
                        .map(field -> "'" + field.label + "' (key '" + field.key + "')")
                        .collect(java.util.stream.Collectors.joining(", "));
                throw new BadRequestException("Column '" + header
                        + "' matches multiple custom metadata labels: " + matches
                        + ". Use a unique field key or label.");
            }
            if (!labelMatches.isEmpty()) {
                SampleMetadataField field = labelMatches.getFirst();
                if (field.archivedOn != null) throw archivedFieldError(header, field);
                if (findHeaderIndex(headers, field.key) == null) {
                    columns.add(new MetadataColumn(header, index, field, null));
                }
                continue;
            }

            String label = header.trim();
            if (label.length() > 255) {
                throw new BadRequestException("Column '" + header
                        + "' cannot be imported as custom metadata because its label exceeds 255 characters."
                        + " Shorten the column header.");
            }
            String key = generateMetadataKey(header);
            try {
                SampleMetadataFieldService.validateKey(key);
            } catch (BadRequestException e) {
                String guidance = e.getMessage().contains("reserved")
                        ? "Rename it so the generated key is not reserved."
                        : "Rename it to use an ASCII letter first, followed by ASCII letters, numbers, or spaces.";
                throw new BadRequestException("Column '" + header
                        + "' cannot be imported as custom metadata: " + e.getMessage()
                        + ". " + guidance, e);
            }

            SampleMetadataField keyCollision = findFieldByKey(allFields, key);
            if (keyCollision != null) {
                String action = keyCollision.archivedOn == null
                        ? "Use the existing field key or a unique label."
                        : "Restore this field or rename the column.";
                throw new BadRequestException("Column '" + header + "' would generate metadata key '" + key
                        + "', which already belongs to " + (keyCollision.archivedOn == null ? "active" : "archived")
                        + " field '" + keyCollision.label + "'. " + action);
            }
            String previousGeneratedHeader = generatedKeys.putIfAbsent(key, header);
            if (previousGeneratedHeader != null) {
                throw new BadRequestException("Columns '" + previousGeneratedHeader + "' and '" + header
                        + "' both generate metadata key '" + key + "'. Rename one column so each has a unique key.");
            }
            columns.add(new MetadataColumn(header, index, null, key));
        }

        return columns;
    }

    private SampleMetadataField findFieldByKey(List<SampleMetadataField> fields, String normalizedKey) {
        return fields.stream()
                .filter(field -> normalizeHeader(field.key).equals(normalizedKey))
                .findFirst()
                .orElse(null);
    }

    private BadRequestException archivedFieldError(String header, SampleMetadataField field) {
        return new BadRequestException("Column '" + header + "' matches archived custom metadata field '"
                + field.key + "' ('" + field.label + "'). Restore this field or rename the column.");
    }

    private String generateMetadataKey(String header) {
        StringBuilder key = new StringBuilder();
        boolean underscorePending = false;
        for (int i = 0; i < header.length(); i++) {
            char character = header.charAt(i);
            if (character > 0x7f) {
                throw new BadRequestException("Column '" + header
                        + "' contains non-ASCII characters and cannot be converted to a metadata key."
                        + " Rename it using ASCII letters and numbers.");
            }
            char lower = Character.toLowerCase(character);
            if ((lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')) {
                if (underscorePending && key.length() > 0) key.append('_');
                key.append(lower);
                underscorePending = false;
            } else {
                underscorePending = true;
            }
        }
        if (key.length() > 0 && key.length() > 64) {
            throw new BadRequestException("Column '" + header
                    + "' generates a metadata key longer than 64 characters. Shorten the column header.");
        }
        return key.toString();
    }

    private boolean hasHeader(List<String> headers, String... expectedHeaders) {
        Set<String> expected = new HashSet<>();
        for (String header : expectedHeaders) expected.add(normalizeHeader(header));
        return headers.stream().map(this::normalizeHeader).anyMatch(expected::contains);
    }

    List<CSVRecord> prepareRecords(Reader reader, char delimiter) throws IOException {
        return prepareRecordsWithHeaders(reader, delimiter).records();
    }

    private ParsedRecords prepareRecordsWithHeaders(Reader reader, char delimiter) throws IOException {
        CSVFormat inputFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setTrim(true)
                .get();

        List<CSVRecord> allRecords;
        try (CSVParser parser = inputFormat.parse(reader)) {
            allRecords = parser.getRecords();
        }

        if (allRecords.isEmpty()) throw new BadRequestException("CSV file has no header record");

        List<String> headers = new ArrayList<>(allRecords.getFirst().toList());
        if (!headers.isEmpty() && headers.getFirst().startsWith("\uFEFF")) {
            headers.set(0, headers.getFirst().substring(1));
        }
        if (headers.stream().allMatch(String::isBlank)) {
            throw new BadRequestException("CSV file has no usable headers");
        }
        return parseMappedRecords(headers, allRecords.subList(1, allRecords.size()), delimiter);
    }

    private ParsedRecords parseMappedRecords(
            List<String> headers, List<CSVRecord> dataRecords, char delimiter) throws IOException {
        StringWriter content = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(content, CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get())) {
            printer.printRecord(headers);
            for (CSVRecord dataRecord : dataRecords) {
                printer.printRecord(dataRecord.toList());
            }
        }

        CSVFormat mappedFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreHeaderCase(true)
                .setAllowMissingColumnNames(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .get();
        try (CSVParser parser = mappedFormat.parse(new StringReader(content.toString()))) {
            return new ParsedRecords(headers, parser.getRecords());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("CSV file contains duplicate headers", e);
        }
    }

    private char detectDelimiter(File file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != 0xFEFF) {
                reader.reset();
            }
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) return ',';

            long tabs = firstLine.chars().filter(c -> c == '\t').count();
            long commas = firstLine.chars().filter(c -> c == ',').count();
            long semicolons = firstLine.chars().filter(c -> c == ';').count();

            if (tabs > commas && tabs > semicolons) return '\t';
            if (semicolons > commas) return ';';
            return ',';
        }
    }

    private Integer parseOptionalInt(
            CSVRecord rec, String[] columns, String sampleName, List<SampleValidationViolation> rowErrors) {
        String value = getMappedValue(rec, columns);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            rowErrors.add(new SampleValidationViolation(
                    sampleName, columns[0], value, "Invalid integer value: '" + value + "'"));
            return null;
        }
    }

    private Double parseOptionalDouble(
            CSVRecord rec, String[] columns, String sampleName, List<SampleValidationViolation> rowErrors) {
        String value = getMappedValue(rec, columns);
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            rowErrors.add(new SampleValidationViolation(
                    sampleName, columns[0], value, "Invalid decimal value: '" + value + "'"));
            return null;
        }
    }

    private String getMappedValue(CSVRecord rec, String... headers) {
        for (String header : headers) {
            if (rec.isMapped(header)) {
                return rec.get(header);
            }
        }
        return null;
    }

    String getCustomMetadataValue(CSVRecord rec, SampleMetadataField field) {
        return getCustomMetadataValue(rec, field, List.of(field));
    }

    String getCustomMetadataValue(
            CSVRecord rec, SampleMetadataField field, List<SampleMetadataField> customFields) {
        Integer keyIndex = findHeaderIndex(rec, field.key);
        if (keyIndex != null) return valueAt(rec, keyIndex);

        if (isAmbiguousLabel(field, customFields)) return null;
        Integer labelIndex = findHeaderIndex(rec, field.label);
        return labelIndex == null ? null : valueAt(rec, labelIndex);
    }

    private boolean isAmbiguousLabel(SampleMetadataField field, List<SampleMetadataField> customFields) {
        String normalizedLabel = normalizeHeader(field.label);
        if (BUILT_IN_HEADERS.contains(normalizedLabel)) return true;

        return customFields.stream()
                .filter(other -> other != field)
                .anyMatch(other -> normalizeHeader(other.key).equals(normalizedLabel)
                        || normalizeHeader(other.label).equals(normalizedLabel));
    }

    private Integer findHeaderIndex(CSVRecord rec, String expectedHeader) {
        String normalizedExpectedHeader = normalizeHeader(expectedHeader);
        Integer match = null;
        List<String> headers = rec.getParser().getHeaderNames();
        for (int i = 0; i < headers.size(); i++) {
            if (normalizeHeader(headers.get(i)).equals(normalizedExpectedHeader)) {
                if (match != null) {
                    throw new BadRequestException("Duplicate metadata column '" + expectedHeader + "'");
                }
                match = i;
            }
        }
        return match;
    }

    private Integer findHeaderIndex(List<String> headers, String expectedHeader) {
        String normalizedExpectedHeader = normalizeHeader(expectedHeader);
        for (int i = 0; i < headers.size(); i++) {
            if (normalizeHeader(headers.get(i)).equals(normalizedExpectedHeader)) return i;
        }
        return null;
    }

    private String valueAt(CSVRecord rec, int index) {
        return index < rec.size() ? rec.get(index) : null;
    }

    private String normalizeHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    static Map<String, String> builtInValues(Sample sample) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("alias", sample.alias);
        values.put("mlst", sample.mlst);
        values.put("project_title", sample.projectTitle);
        values.put("description", sample.description);
        values.put("isolate", sample.isolate);
        values.put("collected_by", sample.collectedBy);
        values.put("environmental_sample", sample.environmentalSample);
        values.put("host_associated", sample.hostAssociated);
        values.put("host_common_name", sample.hostCommonName);
        values.put("host_subject_id", sample.hostSubjectId);
        values.put("collector_name", sample.collectorName);
        values.put("collecting_institution", sample.collectingInstitution);
        values.put("host_sex", sample.hostSex);
        values.put("influenza_test_method", sample.influenzaTestMethod);
        values.put("influenza_test_result", sample.influenzaTestResult);
        values.put("other_pathogens_tested", sample.otherPathogensTested);
        values.put("other_pathogens_test_result", sample.otherPathogensTestResult);
        values.put("host_habitat", sample.hostHabitat);
        values.put("isolation_source_host_associated", sample.isolationSourceHostAssociated);
        values.put("host_behaviour", sample.hostBehaviour);
        values.put("isolation_source_non_host_associated", sample.isolationSourceNonHostAssociated);
        values.put("influenza_virus_type", sample.influenzaVirusType);
        values.put("influenza_sub_type", sample.influenzaSubType);
        values.put("serovar", sample.serovar);
        values.put("strain", sample.strain);
        values.put("host_age", sample.hostAge);
        values.put("county", sample.county);
        values.put("commune", sample.commune);
        values.put("hospital_health_institution", sample.hospitalHealthInstitution);
        values.put("isolation_source", sample.isolationSource);
        values.put("location", sample.location);
        values.put("sequencing_lab", sample.sequencingLab);
        values.put("institution", sample.institution);
        values.put("host_health_state", sample.hostHealthState);
        values.put("project_accession", sample.projectAccession);
        values.put("study_accession", sample.studyAccession);
        values.put("sample_accession", sample.sampleAccession);
        values.put("secondary_accession", sample.secondaryAccession);
        return values;
    }
}
