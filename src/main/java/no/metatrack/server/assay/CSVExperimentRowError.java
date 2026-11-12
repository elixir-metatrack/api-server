package no.metatrack.server.assay;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A row-level experiment import error. Other valid rows may still be imported.")
public record CSVExperimentRowError(String row, String field, String value, String message, String code) {
    public CSVExperimentRowError(String row, String field, String value, String message) {
        this(row, field, value, message, "VALIDATION_ERROR");
    }
}