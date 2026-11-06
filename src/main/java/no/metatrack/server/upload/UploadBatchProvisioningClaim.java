package no.metatrack.server.upload;

import java.time.Instant;
import java.util.UUID;

public record UploadBatchProvisioningClaim(
        UUID batchId,
        String username,
        String publicKey,
        Instant expiresAt,
        int attempts,
        long reservedBytes,
        int fileCount
) {
}