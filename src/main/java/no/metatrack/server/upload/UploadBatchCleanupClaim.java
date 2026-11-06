package no.metatrack.server.upload;

import java.util.UUID;

public record UploadBatchCleanupClaim(
        UUID entryId,
        UUID batchId,
        String sftpUsername,
        String stagingKey,
        String destinationObjectKey,
        String previousObjectKey,
        String multipartUploadId,
        UploadBatchStatus batchStatus,
        UploadBatchEntryStatus entryStatus,
        boolean stagingDue,
        boolean destinationCleanupDue,
        boolean previousObjectDue
) {
}