package no.metatrack.server.assay;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.vocabulary.AssayVocabularyValidationException;
import no.metatrack.server.assay.vocabulary.AssayVocabularyRules;
import no.metatrack.server.assay.vocabulary.AssayVocabularyService;
import no.metatrack.server.csv.CSVImportSupport;
import no.metatrack.server.file.File;
import no.metatrack.server.file.PresignUrlService;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.sample.Sample;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CSVExperimentImportService {
    private static final Map<String, String> VOCABULARY_HEADERS = Map.of(
            "instrument_model", "Sequencing instrument",
            "library_name", "Library Name",
            "library_source", "Library Source",
            "library_selection", "Library Selection",
            "library_strategy", "Library Strategy",
            "library_layout", "Library Layout",
            "sequencing_platform", "Sequencing platform",
            "sequencing_laboratory", "Sequencing Laboratory");

    @Inject
    CSVImportSupport csvImportSupport;

    @Inject
    AssayVocabularyService vocabularyService;

    @Inject
    CSVExperimentRowWriter rowWriter;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public List<CSVExperimentRowError> importIntoAssay(Long projectId, UUID assayId, java.io.File file) {
        Assay assay = Assay.<Assay>find("id = ?1 and project.id = ?2", assayId, projectId)
                .firstResultOptional().orElseThrow(NotFoundException::new);
        if (file == null || !file.isFile()) {
            throw new BadRequestException("CSV file is missing");
        }

        List<CSVExperimentRowError> errors = new ArrayList<>();
        Set<String> importedReferences = new HashSet<>();
        AssayVocabularyRules rules = vocabularyService.loadRules();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            skipBom(reader);
            for (CSVRecord record : csvImportSupport.prepareRecords(reader, csvImportSupport.detectDelimiter(file))) {
                importRecord(projectId, assay, record, errors, importedReferences, rules);
            }
        } catch (IOException e) {
            throw new BadRequestException("Unable to read CSV file", e);
        }
        return errors;
    }

    private void importRecord(Long projectId, Assay assay, CSVRecord record, List<CSVExperimentRowError> errors,
            Set<String> importedReferences, AssayVocabularyRules rules) {
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

        Map<String, String> metadata = new LinkedHashMap<>();
        VOCABULARY_HEADERS.forEach((field, header) -> metadata.put(field, value(record, header)));
        var violations = AssayVocabularyService.validate(rules, assay.name, metadata);
        if (!violations.isEmpty()) {
            violations.forEach(violation -> errors.add(new CSVExperimentRowError(row,
                    VOCABULARY_HEADERS.get(violation.field()), metadata.get(violation.field()), violation.message())));
            return;
        }

        Integer insertSize = parseInteger(record, "Insert Size", row, errors);
        if (hasMetadataConflict(assay, metadata, insertSize, row, errors)) return;
        int rowErrorCount = errors.size();
        Set<String> rowReferences = new HashSet<>();
        List<PendingFile> pendingFiles = new ArrayList<>();
        prepareFile(projectId, assay, sample.get(), value(record, "File Name"), value(record, "File md5"),
                value(record, "File Unencrypted md5"), ReadRole.SINGLE, row, "File Name", importedReferences,
                rowReferences, pendingFiles, errors);
        prepareFile(projectId, assay, sample.get(), value(record, "Forward File Name"), value(record, "Forward File md5"),
                value(record, "Forward File Unencrypted md5"), ReadRole.FORWARD, row, "Forward File Name",
                importedReferences, rowReferences, pendingFiles, errors);
        prepareFile(projectId, assay, sample.get(), value(record, "Reverse File Name"), value(record, "Reverse File md5"),
                value(record, "Reverse File Unencrypted md5"), ReadRole.REVERSE, row, "Reverse File Name",
                importedReferences, rowReferences, pendingFiles, errors);
        if (errors.size() > rowErrorCount || hasError(errors, row, "Insert Size")) return;

        try {
            rowWriter.write(projectId, assay.id, sampleName.trim(),
                    patchValue(record, "Sequencing instrument"), patchValue(record, "Library Name"),
                    patchValue(record, "Library Source"), patchValue(record, "Library Selection"),
                    patchValue(record, "Library Strategy"), patchValue(record, "Library Layout"), insertSize,
                    patchValue(record, "Sequencing platform"), patchValue(record, "Sequencing Laboratory"), pendingFiles);
        } catch (AssayVocabularyValidationException e) {
            e.violations().forEach(violation -> errors.add(new CSVExperimentRowError(row,
                    VOCABULARY_HEADERS.getOrDefault(violation.field(), violation.field()),
                    metadata.get(violation.field()), violation.message())));
            return;
        } catch (CSVExperimentRowWriter.FileConflictException e) {
            errors.add(new CSVExperimentRowError(row, e.field(), e.fileName(), e.getMessage()));
            return;
        }
        importedReferences.addAll(rowReferences);
    }

    private void prepareFile(Long projectId, Assay assay, Sample sample, String fileName, String md5,
            String unencryptedMd5, ReadRole readRole, String row, String field, Set<String> importedReferences,
            Set<String> rowReferences, List<PendingFile> pendingFiles, List<CSVExperimentRowError> errors) {
        if ((fileName == null || fileName.isBlank()) && (md5 == null || md5.isBlank())) return;
        boolean valid = true;
        if (fileName == null || fileName.isBlank()) {
            errors.add(new CSVExperimentRowError(row, field, fileName, "File name is required"));
            valid = false;
        } else {
            String reference = PresignUrlService.virtualPath(projectId, assay.id, sample.name, fileName);
            if (importedReferences.contains(reference) || !rowReferences.add(reference)) {
                errors.add(new CSVExperimentRowError(row, field, fileName, "Duplicate file reference in import"));
                valid = false;
            }
        }
        if (md5 == null || md5.isBlank()) {
            errors.add(new CSVExperimentRowError(row, field + " md5", md5, "File md5 is required"));
            valid = false;
        }
        if (!valid) return;

        File.validateImportPending(projectId, assay.id, sample, assay, fileName, md5, unencryptedMd5, readRole)
                .ifPresent(message -> {
                    errors.add(new CSVExperimentRowError(row, field, fileName, message));
                });
        if (errors.stream().anyMatch(error -> error.row().equals(row) && error.field().equals(field))) {
            valid = false;
        }
        if (valid) {
            pendingFiles.add(new PendingFile(field, fileName, md5, unencryptedMd5, readRole));
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

    private boolean hasMetadataConflict(Assay assay, Map<String, String> metadata, Integer insertSize,
            String row, List<CSVExperimentRowError> errors) {
        Map<String, String> existingValues = Map.of(
                "instrument_model", assay.instrumentModel == null ? "" : assay.instrumentModel,
                "library_name", assay.libraryName == null ? "" : assay.libraryName,
                "library_source", assay.librarySource == null ? "" : assay.librarySource,
                "library_selection", assay.librarySelection == null ? "" : assay.librarySelection,
                "library_strategy", assay.libraryStrategy == null ? "" : assay.libraryStrategy,
                "library_layout", assay.libraryLayout == null ? "" : assay.libraryLayout,
                "sequencing_platform", assay.sequencingPlatform == null ? "" : assay.sequencingPlatform,
                "sequencing_laboratory", assay.sequencingLaboratory == null ? "" : assay.sequencingLaboratory);
        boolean hasConflict = false;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String incoming = entry.getValue();
            String existing = existingValues.get(entry.getKey());
            if (incoming != null && !incoming.isBlank() && !existing.isBlank() && !existing.equals(incoming)) {
                errors.add(new CSVExperimentRowError(row, VOCABULARY_HEADERS.get(entry.getKey()), incoming,
                        "Cannot replace existing value '" + existing + "' with '" + incoming + "'"));
                hasConflict = true;
            }
        }
        if (insertSize != null && assay.insertSize != null && !assay.insertSize.equals(insertSize)) {
            errors.add(new CSVExperimentRowError(row, "Insert Size", insertSize.toString(),
                    "Cannot replace existing value '" + assay.insertSize + "' with '" + insertSize + "'"));
            hasConflict = true;
        }
        return hasConflict;
    }

    private boolean hasError(List<CSVExperimentRowError> errors, String row, String field) {
        return errors.stream().anyMatch(error -> error.row().equals(row) && error.field().equals(field));
    }

    record PendingFile(String field, String fileName, String md5, String unencryptedMd5, ReadRole readRole) {
    }

    private String value(CSVRecord record, String header) {
        String value = csvImportSupport.getMappedValue(record, header);
        return value == null ? null : value.trim();
    }

    private String patchValue(CSVRecord record, String header) {
        String value = value(record, header);
        return value == null || value.isBlank() ? null : value;
    }

    private void skipBom(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != 0xFEFF) reader.reset();
    }
}