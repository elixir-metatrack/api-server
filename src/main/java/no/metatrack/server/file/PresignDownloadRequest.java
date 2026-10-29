package no.metatrack.server.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Identify an existing file associated with a sample and assay for which to obtain a temporary download URL.")
public record PresignDownloadRequest(
        @NotNull Long projectId,
        @NotNull UUID assayId,
        @Schema(description = "Sample name within the project")
        @NotBlank String sampleName,
        @Schema(description = "Stored file name")
        @NotBlank String fileName) {}
