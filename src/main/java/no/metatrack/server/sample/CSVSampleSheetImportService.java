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
                    .get()
                    .parse(reader);

            for (CSVRecord rec : records) {

                if (Sample.sampleExistsByName(rec.get("name"), projectId)) {
                    errors.add(new CSVUploadRowError(rec.get("name"), "name", "Already exits"));
                    continue;
                }

                Sample sample = new Sample();
                sample.project = Project.findById(projectId);

                // TODO: Validate input against controlled vocabularies if available
                sample.name = rec.get("name");
                sample.taxId = Integer.parseInt(rec.get("tax_id"));
                sample.alias = rec.get("alias");
                sample.mlst = rec.get("mlst");
                sample.isolationSource = rec.get("isolation_source");
                String rawDate = rec.get("collection_date");
                if (rawDate != null && !rawDate.isBlank()) {
                    sample.collectionDate = LocalDate.parse(rawDate, DATE_FORMATTER);
                }
                sample.location = rec.get("location");
                sample.sequencingLab = rec.get("sequencing_lab");
                sample.institution = rec.get("institution");
                sample.hostHealthState = rec.get("host_health_state");
                sample.hostTaxId = Integer.parseInt(rec.get("host_tax_id"));
                sample.createdOn = Instant.now();
                sample.modifiedOn = Instant.now();

                sample.persist();
            }
        } catch (IOException e) {
            throw new WebApplicationException(e.getMessage(), 500);
        }

        return errors;
    }
}
