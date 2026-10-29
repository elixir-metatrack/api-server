package no.metatrack.server.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Identify an existing sample and assay for which to obtain a temporary upload URL.")
public record PresignUploadRequest(
        @NotNull Long projectId,
        @NotNull UUID assayId,
        @Schema(description = "Sample name within the project")
        @NotBlank String sampleName,
        @Schema(description = "Name of the file to upload")
        @NotBlank String fileName) {}
