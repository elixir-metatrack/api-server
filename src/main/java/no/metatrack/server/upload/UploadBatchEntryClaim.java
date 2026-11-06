package no.metatrack.server.upload;

import java.util.UUID;

public record UploadBatchEntryClaim(
        UUID entryId,
        UUID batchId,
        UUID uploaderId,
        Long requestedProjectId,
        String relativePath,
        String stagingKey,
        String destinationObjectKey,
        long expectedSizeBytes,
        String expectedSha256,
        String expectedMd5,
        String expectedUnencryptedMd5,
        String sourceEtag,
        String sourceVersionId,
        String multipartUploadId,
        Long multipartPartSizeBytes,
        int attempts,
        UUID leaseToken
) {
}