package no.metatrack.server.sample.metadata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateSampleMetadataFieldRequest(
        @NotBlank String key,
        @NotBlank String label,
        @NotNull SampleMetadataFieldType type) {}