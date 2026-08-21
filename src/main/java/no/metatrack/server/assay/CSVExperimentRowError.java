package no.metatrack.server.assay;

public record CSVExperimentRowError(String row, String field, String value, String message) {
}