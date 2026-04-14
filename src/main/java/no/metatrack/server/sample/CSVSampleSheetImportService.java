package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.Project;
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
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CSVSampleSheetImportService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(
            "[yyyy-MM-dd][d/M/yyyy][d/M/yy][MM/dd/yyyy][MM/dd/yy]");

    @Transactional
    public List<CSVUploadRowError> importNewSamples(Long projectId, File file) {
        List<CSVUploadRowError> errors = new ArrayList<>();
        List<Sample> samplesToSave = new ArrayList<>();
        Project project = Project.findById(projectId);

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
                    String name = rec.isMapped("name") ? rec.get("name") : null;

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
                    sample.alias = rec.isMapped("alias") ? rec.get("alias") : null;
                    sample.taxId = parseOptionalInt(rec, "tax_id", name, rowErrors);
                    sample.hostTaxId = parseOptionalInt(rec, "host_tax_id", name, rowErrors);
                    sample.mlst = rec.isMapped("mlst") ? rec.get("mlst") : null;
                    sample.isolationSource = rec.isMapped("isolation_source") ? rec.get("isolation_source") : null;

                    String rawDate = rec.isMapped("collection_date") ? rec.get("collection_date") : null;
                    if (rawDate != null && !rawDate.isBlank()) {
                        try {
                            sample.collectionDate = LocalDate.parse(rawDate.trim(), DATE_FORMATTER);
                        } catch (DateTimeParseException e) {
                            rowErrors.add(new CSVUploadRowError(name, "collection_date",
                                    "Invalid date '" + rawDate + "'. Accepted formats: yyyy-MM-dd, d/M/yyyy, MM/dd/yyyy"));
                        }
                    }

                    sample.location = rec.isMapped("location") ? rec.get("location") : null;
                    sample.sequencingLab = rec.isMapped("sequencing_lab") ? rec.get("sequencing_lab") : null;
                    sample.institution = rec.isMapped("institution") ? rec.get("institution") : null;
                    sample.hostHealthState = rec.isMapped("host_health_state") ? rec.get("host_health_state") : null;
                    sample.createdOn = Instant.now();
                    sample.modifiedOn = Instant.now();

                    if (rowErrors.isEmpty()) {
                        samplesToSave.add(sample);
                    } else {
                        errors.addAll(rowErrors);
                    }
                }
            }
        } catch (IOException e) {
            throw new WebApplicationException(e.getMessage(), 500);
        }

        samplesToSave.forEach(Sample::persist);

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

    private Integer parseOptionalInt(CSVRecord rec, String column, String sampleName, List<CSVUploadRowError> rowErrors) {
        if (!rec.isMapped(column)) return null;
        String value = rec.get(column);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            rowErrors.add(new CSVUploadRowError(sampleName, column, "Invalid integer value: '" + value + "'"));
            return null;
        }
    }
}
