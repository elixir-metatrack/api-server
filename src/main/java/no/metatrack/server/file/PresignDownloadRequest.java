package no.metatrack.server.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PresignDownloadRequest(
        @NotNull Long projectId,
        @NotNull UUID assayId,
        @NotBlank String sampleName,
        @NotBlank String fileName) {}
