package no.metatrack.server.sample.vocabulary;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "A rejected sample field value. Bulk/import endpoints may return multiple violations while retaining successful rows.")
public record SampleValidationViolation(
        String sample,
        String fieldKey,
        Object rejectedValue,
        String message) {}