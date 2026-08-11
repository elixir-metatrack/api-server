package no.metatrack.server.sample.vocabulary;

public record SampleValidationViolation(
        String sample,
        String fieldKey,
        Object rejectedValue,
        String message) {}