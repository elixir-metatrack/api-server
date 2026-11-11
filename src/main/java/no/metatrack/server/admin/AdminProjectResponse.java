package no.metatrack.server.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminProjectResponse(
        Long id,
        String name,
        UUID ownerId,
        long memberCount,
        boolean ownershipNeedsAttention,
        Instant createdOn,
        Instant modifiedOn) {
}