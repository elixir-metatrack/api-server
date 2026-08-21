package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
            "hospital/health institution");

    @Inject
    SampleMetadataService metadataService;

    @Inject
    SampleVocabularyService vocabularyService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("[yyyy-MM-dd][d/M/yyyy][d/M/yy][MM/dd/yyyy][MM/dd/yy]");

    @Transactional
    public List<SampleValidationViolation> importNewSamples(Long projectId, File file) {
        List<SampleValidationViolation> errors = new ArrayList<>();
        List<Sample> samplesToSave = new ArrayList<>();
        Map<Sample, Map<String, Object>> metadataToSave = new IdentityHashMap<>();
        Project project = Project.findById(projectId);
        List<SampleMetadataField> customFields = SampleMetadataField.list(
                "project.id = ?1 and archivedOn is null order by key", projectId);
        SampleVocabularyRules vocabularyRules = vocabularyService.loadRules(projectId);

        try {
            char delimiter = detectDelimiter(file);

            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                reader.mark(1);
                if (reader.read() != 0xFEFF) {
                    reader.reset();
                }

                List<CSVRecord> records = prepareRecords(reader, delimiter);

                Set<String> namesInFile = new HashSet<>();

                for (CSVRecord rec : records) {
                    String name = getMappedValue(rec, "name", "Sample Name");

                    if (name == null || name.isBlank()) {
                        errors.add(new SampleValidationViolation(
                                "Row " + rec.getRecordNumber(), "name", name, "Name column is missing or empty"));
                        continue;
                    }

                    if (!namesInFile.add(name)) {
                        errors.add(new SampleValidationViolation(
                                name, "name", name, "Duplicate sample name within this file: '" + name + "'"));
                        continue;
                    }

                    if (Sample.find("name = ?1 and project = ?2", name, project)
                            .firstResultOptional()
                            .isPresent()) {
                        errors.add(new SampleValidationViolation(
                                name, "name", name, "Sample name '" + name + "' already exists in this project"));
                        continue;
                    }

                    List<SampleValidationViolation> rowErrors = new ArrayList<>();

                    Sample sample = new Sample();
                    sample.project = project;
                    sample.name = name;
                    sample.alias = getMappedValue(rec, "alias");
                    sample.taxId = parseOptionalInt(rec, new String[] {"tax_id", "Tax ID"}, name, rowErrors);
                    sample.hostTaxId =
                            parseOptionalInt(rec, new String[] {"host_tax_id", "Host Tax ID"}, name, rowErrors);
                    sample.mlst = getMappedValue(rec, "mlst", "MLST");
                    sample.isolationSource = getMappedValue(rec, "isolation_source", "Isolation Source");

                    String rawDate = getMappedValue(rec, "collection_date", "Collection Date");
                    if (rawDate != null && !rawDate.isBlank()) {
                        try {
                            sample.collectionDate = LocalDate.parse(rawDate.trim(), DATE_FORMATTER);
                        } catch (DateTimeParseException e) {
                            rowErrors.add(new SampleValidationViolation(
                                    name,
                                    "collection_date",
                                    rawDate,
                                    "Invalid date '" + rawDate
                                            + "'. Accepted formats: yyyy-MM-dd, d/M/yyyy, MM/dd/yyyy"));
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
                    sample.latitude = parseOptionalDouble(rec, new String[] {"latitude", "Latitude"}, name, rowErrors);
                    sample.longitude =
                            parseOptionalDouble(rec, new String[] {"longitude", "Longitude"}, name, rowErrors);
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

                    Map<String, Object> customMetadata = new LinkedHashMap<>();
                    for (SampleMetadataField field : customFields) {
                        String rawValue = getCustomMetadataValue(rec, field, customFields);
                        if (rawValue == null || rawValue.isBlank()) continue;
                        try {
                            customMetadata.put(field.key, metadataService.parseCsvValue(field.type, rawValue));
                        } catch (BadRequestException e) {
                            rowErrors.add(new SampleValidationViolation(name, field.key, rawValue, e.getMessage()));
                        }
                    }

                    rowErrors.addAll(SampleVocabularyService.validate(
                            vocabularyRules, name, builtInValues(sample), customMetadata));

                    sample.createdOn = Instant.now();
                    sample.modifiedOn = Instant.now();

                    if (rowErrors.isEmpty()) {
                        samplesToSave.add(sample);
                        metadataToSave.put(sample, customMetadata);
                    } else {
                        errors.addAll(rowErrors);
                    }
                }
            }
        } catch (IOException e) {
            throw new WebApplicationException(e.getMessage(), 500);
        }

        samplesToSave.forEach(sample -> {
            sample.persist();
            metadataService.apply(projectId, sample, metadataToSave.get(sample));
        });

        return errors;
    }

    List<CSVRecord> prepareRecords(Reader reader, char delimiter) throws IOException {
        CSVFormat inputFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setTrim(true)
                .get();

        List<CSVRecord> allRecords;
        try (CSVParser parser = inputFormat.parse(reader)) {
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
            return parseMappedRecords(
                    allRecords.get(0).toList(), allRecords.subList(1, allRecords.size()), delimiter, false);
        }

        CSVRecord marker = allRecords.get(markerIndex);
        List<String> headers = withoutFirstField(marker);
        if (headers.stream().allMatch(String::isBlank)) {
            throw new BadRequestException("METADATA FIELDS: record has no usable headers");
        }

        return parseMappedRecords(headers, allRecords.subList(markerIndex + 1, allRecords.size()), delimiter);
    }

    private List<CSVRecord> parseMappedRecords(
            List<String> headers, List<CSVRecord> dataRecords, char delimiter) throws IOException {
        return parseMappedRecords(headers, dataRecords, delimiter, true);
    }

    private List<CSVRecord> parseMappedRecords(
            List<String> headers, List<CSVRecord> dataRecords, char delimiter, boolean removeFirstField)
            throws IOException {
        StringWriter content = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(content, CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get())) {
            printer.printRecord(headers);
            for (CSVRecord dataRecord : dataRecords) {
                printer.printRecord(removeFirstField ? withoutFirstField(dataRecord) : dataRecord.toList());
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
            return parser.getRecords();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("CSV file contains duplicate headers", e);
        }
    }

    private List<String> withoutFirstField(CSVRecord record) {
        return record.size() <= 1 ? List.of() : record.toList().subList(1, record.size());
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
        return values;
    }
}
