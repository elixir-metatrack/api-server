package no.metatrack.server.file;

import jakarta.validation.constraints.NotBlank;

public record PresignUploadRequest(
        @NotBlank String projectId,
        @NotBlank String sampleName,
        @NotBlank String fileName) {}
