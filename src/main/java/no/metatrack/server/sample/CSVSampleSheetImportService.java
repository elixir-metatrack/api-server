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
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class CSVSampleSheetImportService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("[d/M/yyyy][d/M/yy]");

    @Transactional
    public List<CSVUploadRowError> importNewSamples(Long projectId, File file) {
        List<CSVUploadRowError> errors = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != 0xFEFF) {
                reader.reset();
            }
            Iterable<CSVRecord> records = CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .setIgnoreHeaderCase(true)
                    .setAllowMissingColumnNames(true)
                    .get()
                    .parse(reader);

            for (CSVRecord rec : records) {

                if (!rec.isMapped("name") || rec.get("name").isBlank()) {
                    errors.add(new CSVUploadRowError(
                            "Row " + rec.getRecordNumber(), "name", "Name column is missing or empty"));
                    continue;
                }

                Sample sample = new Sample();
                sample.project = Project.findById(projectId);

                // TODO: Validate input against controlled vocabularies if available
                sample.name = rec.isMapped("name") ? rec.get("name") : null;
                sample.taxId = parseOptionalInt(rec, "tax_id");
                sample.alias = rec.isMapped("alias") ? rec.get("alias") : null;
                sample.mlst = rec.isMapped("mlst") ? rec.get("mlst") : null;
                sample.isolationSource = rec.isMapped("isolation_source") ? rec.get("isolation_source") : null;
                String rawDate = rec.isMapped("collection_date") ? rec.get("collection_date") : null;
                if (rawDate != null && !rawDate.isBlank()) {
                    sample.collectionDate = LocalDate.parse(rawDate, DATE_FORMATTER);
                }
                sample.location = rec.isMapped("location") ? rec.get("location") : null;
                sample.sequencingLab = rec.isMapped("sequencing_lab") ? rec.get("sequencing_lab") : null;
                sample.institution = rec.isMapped("institution") ? rec.get("institution") : null;
                sample.hostHealthState = rec.isMapped("host_health_state") ? rec.get("host_health_state") : null;
                sample.hostTaxId = parseOptionalInt(rec, "host_tax_id");
                sample.createdOn = Instant.now();
                sample.modifiedOn = Instant.now();

                sample.persist();
            }
        } catch (IOException e) {
            throw new WebApplicationException(e.getMessage(), 500);
        }

        return errors;
    }

    private Integer parseOptionalInt(CSVRecord rec, String column) {
        if (rec.isMapped(column) && rec.get(column) != null && !rec.get(column).isBlank()) {
            try {
                return Integer.parseInt(rec.get(column));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
