package no.metatrack.server.file;

import java.time.Instant;
import java.util.Map;

public record StorageObjectIdentity(
        long size,
        String eTag,
        String versionId,
        Instant lastModified,
        Map<String, String> metadata
) {
    public StorageObjectIdentity {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}