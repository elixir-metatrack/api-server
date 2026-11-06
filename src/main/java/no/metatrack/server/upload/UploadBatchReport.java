package no.metatrack.server.upload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadBatchReport(
        UUID batchId,
        Long requestedProjectId,
        Long owningProjectId,
        UUID uploaderId,
        UploadBatchStatus status,
        String sftpUsername,
        String sftpHost,
        int sftpPort,
        Instant expiresAt,
        long reservedBytes,
        String error,
        List<UploadBatchEntryReport> entries
) {
    public UploadBatchReport {
        entries = List.copyOf(entries);
    }
}