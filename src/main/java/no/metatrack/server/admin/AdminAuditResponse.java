package no.metatrack.server.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminAuditResponse(
        Long id,
        UUID actorId,
        String action,
        String target,
        int responseStatus,
        Instant occurredAt) {
}