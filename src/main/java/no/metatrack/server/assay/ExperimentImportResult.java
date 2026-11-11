package no.metatrack.server.assay;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Experiment import result. Valid rows are saved independently, even when other rows fail.")
public record ExperimentImportResult(
        @Schema(description = "Number of sample rows newly associated with the assay") int acceptedCount,
        List<CSVExperimentRowError> errors) {
    public ExperimentImportResult {
        errors = List.copyOf(errors);
    }
}