package no.metatrack.server.sample.metadata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Definition of a project-specific custom sample metadata field.")
public record CreateSampleMetadataFieldRequest(
        @Schema(description = "Stable key used in custom metadata objects")
        @NotBlank String key,
        @Schema(description = "Human-readable field label")
        @NotBlank String label,
        @Schema(description = "Value type accepted for this field", enumeration = {"TEXT", "NUMBER", "BOOLEAN", "DATE"})
        @NotNull SampleMetadataFieldType type,
        String tooltip) {}