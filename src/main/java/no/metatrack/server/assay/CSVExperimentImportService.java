package no.metatrack.server.assay;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.csv.CSVImportSupport;
import no.metatrack.server.file.File;
import no.metatrack.server.file.PresignUrlService;
import no.metatrack.server.sample.Sample;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CSVExperimentImportService {
    @Inject
    CSVImportSupport csvImportSupport;

    @Transactional
    public List<CSVExperimentRowError> importIntoAssay(Long projectId, UUID assayId, java.io.File file) {
        Assay assay = Assay.<Assay>find("id = ?1 and project.id = ?2", assayId, projectId)
                .firstResultOptional().orElseThrow(NotFoundException::new);
        if (file == null || !file.isFile()) {
            throw new BadRequestException("CSV file is missing");
        }

        List<CSVExperimentRowError> errors = new ArrayList<>();
        Set<String> importedReferences = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            skipBom(reader);
            for (CSVRecord record : csvImportSupport.prepareRecords(reader, csvImportSupport.detectDelimiter(file))) {
                importRecord(projectId, assay, record, errors, importedReferences);
            }
        } catch (IOException e) {
            throw new BadRequestException("Unable to read CSV file", e);
        }
        return errors;
    }

    private void importRecord(Long projectId, Assay assay, CSVRecord record, List<CSVExperimentRowError> errors,
            Set<String> importedReferences) {
        String sampleName = value(record, "Sample");
        String row = "Row " + record.getRecordNumber();
        if (sampleName == null || sampleName.isBlank()) {
            errors.add(new CSVExperimentRowError(row, "Sample", sampleName, "Sample column is missing or empty"));
            return;
        }

        Optional<Sample> sample = Sample.find(
                        "project.id = ?1 and name = ?2 and exists (select 1 from Assay a join a.samples s "
                                + "where a = ?3 and s = Sample)",
                        projectId, sampleName.trim(), assay)
                .firstResultOptional();
        if (sample.isEmpty()) {
            errors.add(new CSVExperimentRowError(row, "Sample", sampleName,
                    "Sample does not exist in the project or is not associated with the assay"));
            return;
        }

        Integer insertSize = parseInteger(record, "Insert Size", row, errors);
        int rowErrorCount = errors.size();
        List<PendingFile> pendingFiles = new ArrayList<>();
        prepareFile(projectId, assay, sample.get(), value(record, "File Name"), value(record, "File md5"),
                value(record, "File Unencrypted md5"), row, "File Name", importedReferences, pendingFiles, errors);
        prepareFile(projectId, assay, sample.get(), value(record, "Forward File Name"), value(record, "Forward File md5"),
                value(record, "Forward File Unencrypted md5"), row, "Forward File Name", importedReferences, pendingFiles, errors);
        prepareFile(projectId, assay, sample.get(), value(record, "Reverse File Name"), value(record, "Reverse File md5"),
                value(record, "Reverse File Unencrypted md5"), row, "Reverse File Name", importedReferences, pendingFiles, errors);
        if (errors.size() > rowErrorCount || hasError(errors, row, "Insert Size")) return;

        assay.instrumentModel = value(record, "Sequencing instrument");
        assay.libraryName = value(record, "Library Name");
        assay.librarySource = value(record, "Library Source");
        assay.librarySelection = value(record, "Library Selection");
        assay.libraryStrategy = value(record, "Library Strategy");
        assay.libraryLayout = value(record, "Library Layout");
        assay.insertSize = insertSize;
        assay.sequencingPlatform = value(record, "Sequencing platform");
        assay.sequencingLaboratory = value(record, "Sequencing Laboratory");
        assay.modifiedOn = Instant.now();
        assay.addSample(sample.get());
        pendingFiles.forEach(pendingFile -> File.importPending(projectId, assay.id, sample.get(), assay,
                pendingFile.fileName(), pendingFile.md5(), pendingFile.unencryptedMd5()));
    }

    private void prepareFile(Long projectId, Assay assay, Sample sample, String fileName, String md5,
            String unencryptedMd5, String row, String field, Set<String> importedReferences,
            List<PendingFile> pendingFiles, List<CSVExperimentRowError> errors) {
        boolean valid = true;
        if (fileName == null || fileName.isBlank()) {
            errors.add(new CSVExperimentRowError(row, field, fileName, "File name is required"));
            valid = false;
        } else {
            String reference = PresignUrlService.virtualPath(projectId, assay.id, sample.name, fileName);
            if (!importedReferences.add(reference)) {
                errors.add(new CSVExperimentRowError(row, field, fileName, "Duplicate file reference in import"));
                valid = false;
            }
        }
        if (md5 == null || md5.isBlank()) {
            errors.add(new CSVExperimentRowError(row, field + " md5", md5, "File md5 is required"));
            valid = false;
        }
        if (!valid) return;

        File.validateImportPending(projectId, assay.id, sample, assay, fileName, md5, unencryptedMd5)
                .ifPresent(message -> {
                    errors.add(new CSVExperimentRowError(row, field, fileName, message));
                });
        if (errors.stream().anyMatch(error -> error.row().equals(row) && error.field().equals(field))) {
            valid = false;
        }
        if (valid) {
            pendingFiles.add(new PendingFile(fileName, md5, unencryptedMd5));
        }
    }

    private Integer parseInteger(CSVRecord record, String field, String row, List<CSVExperimentRowError> errors) {
        String value = value(record, field);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            errors.add(new CSVExperimentRowError(row, field, value, "Invalid integer value: '" + value + "'"));
            return null;
        }
    }

    private boolean hasError(List<CSVExperimentRowError> errors, String row, String field) {
        return errors.stream().anyMatch(error -> error.row().equals(row) && error.field().equals(field));
    }

    private record PendingFile(String fileName, String md5, String unencryptedMd5) {
    }

    private String value(CSVRecord record, String header) {
        String value = csvImportSupport.getMappedValue(record, header);
        return value == null ? null : value.trim();
    }

    private void skipBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != 0xFEFF) reader.reset();
    }
}