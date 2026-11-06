package no.metatrack.server.upload;

import java.util.UUID;

public record UploadBatchRevocationClaim(UUID batchId, String username, int attempts) {
}