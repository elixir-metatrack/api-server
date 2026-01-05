package no.metatrack.server.file;

import jakarta.validation.constraints.NotBlank;

public record PresignDownloadRequest(
        @NotBlank String projectId,
        @NotBlank String sampleId,
        @NotBlank String fileName) {}
