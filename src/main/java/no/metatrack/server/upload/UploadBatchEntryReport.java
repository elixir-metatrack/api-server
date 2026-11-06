package no.metatrack.server.upload;

import no.metatrack.server.file.ReadRole;

import java.util.UUID;

public record UploadBatchEntryReport(
        int row,
        String relativePath,
        String sampleName,
        UUID sampleId,
        UUID assayId,
        long expectedSizeBytes,
        ReadRole readRole,
        UploadBatchEntryStatus status,
        long bytesTransferred,
        int attempts,
        String error
) {
}