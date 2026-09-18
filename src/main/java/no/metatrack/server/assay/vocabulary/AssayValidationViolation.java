package no.metatrack.server.assay.vocabulary;

public record AssayValidationViolation(
        String assay,
        String field,
        Object rejectedValue,
        String message) {}