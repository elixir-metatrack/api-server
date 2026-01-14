package no.metatrack.server.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PresignUploadRequest(
        @NotNull Long projectId,
        @NotBlank String sampleName,
        @NotBlank String fileName) {}
