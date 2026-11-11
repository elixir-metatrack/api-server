package no.metatrack.server.admin;

import no.metatrack.server.upload.UploadBatchStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminUploadIssueResponse(
        UUID batchId,
        Long projectId,
        String projectName,
        UploadBatchStatus status,
        Instant updatedAt,
        long failedEntries) {
}