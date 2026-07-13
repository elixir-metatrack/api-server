package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
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
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CSVSampleSheetImportService {

    @Inject
    SampleMetadataService metadataService;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("[yyyy-MM-dd][d/M/yyyy][d/M/yy][MM/dd/yyyy][MM/dd/yy]");

    @Transactional
    public List<CSVUploadRowError> importNewSamples(Long projectId, File file) {
        List<CSVUploadRowError> errors = new ArrayList<>();
        List<Sample> samplesToSave = new ArrayList<>();
        Map<Sample, Map<String, Object>> metadataToSave = new IdentityHashMap<>();
        Project project = Project.findById(projectId);
        List<SampleMetadataField> customFields = SampleMetadataField.list(
                "project.id = ?1 and archivedOn is null order by key", projectId);

        try {
            char delimiter = detectDelimiter(file);

            try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                reader.mark(1);
                if (reader.read() != 0xFEFF) {
                    reader.reset();
                }

                Iterable<CSVRecord> records = CSVFormat.DEFAULT
                        .builder()
                        .setDelimiter(delimiter)
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .setIgnoreHeaderCase(true)
                        .setAllowMissingColumnNames(true)
                        .get()
                        .parse(reader);

                Set<String> namesInFile = new HashSet<>();

                for (CSVRecord rec : records) {
                    String name = getMappedValue(rec, "name", "Sample Name");

                    if (name == null || name.isBlank()) {
                        errors.add(new CSVUploadRowError(
                                "Row " + rec.getRecordNumber(), "name", "Name column is missing or empty"));
                        continue;
                    }

                    if (!namesInFile.add(name)) {
                        errors.add(new CSVUploadRowError(
                                name, "name", "Duplicate sample name within this file: '" + name + "'"));
                        continue;
                    }

                    if (Sample.find("name = ?1 and project = ?2", name, project)
                            .firstResultOptional()
                            .isPresent()) {
                        errors.add(new CSVUploadRowError(
                                name, "name", "Sample name '" + name + "' already exists in this project"));
                        continue;
                    }

                    List<CSVUploadRowError> rowErrors = new ArrayList<>();

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
                            rowErrors.add(new CSVUploadRowError(
                                    name,
                                    "collection_date",
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
                        String rawValue = getMappedValue(rec, field.key);
                        if (rawValue == null || rawValue.isBlank()) continue;
                        try {
                            customMetadata.put(field.key, metadataService.parseCsvValue(field.type, rawValue));
                        } catch (BadRequestException e) {
                            rowErrors.add(new CSVUploadRowError(name, field.key, e.getMessage()));
                        }
                    }

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
            CSVRecord rec, String[] columns, String sampleName, List<CSVUploadRowError> rowErrors) {
        String value = getMappedValue(rec, columns);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            rowErrors.add(new CSVUploadRowError(sampleName, String.join("/", columns), "Invalid integer value: '" + value + "'"));
            return null;
        }
    }

    private Double parseOptionalDouble(
            CSVRecord rec, String[] columns, String sampleName, List<CSVUploadRowError> rowErrors) {
        String value = getMappedValue(rec, columns);
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            rowErrors.add(new CSVUploadRowError(sampleName, String.join("/", columns), "Invalid decimal value: '" + value + "'"));
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
}
