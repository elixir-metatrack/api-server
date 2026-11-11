package no.metatrack.server.sample;

import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Sample sheet import result. Valid rows are saved independently, even when other rows fail.")
public record SampleImportResult(
        @Schema(description = "Number of sample rows that created or changed a sample") int acceptedCount,
        List<SampleValidationViolation> errors) {
    public SampleImportResult {
        errors = List.copyOf(errors);
    }
}