package no.metatrack.server.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        UUID owner,
        String ownerUsername,
        long sampleCount,
        Instant createdOn,
        Instant modifiedOn,
        Long parentProjectId
) {
}
