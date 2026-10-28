package no.metatrack.server.project;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Project summary. Comments are null for anonymous or non-member callers; sampleCount includes linked samples for sub-projects.")
public record ProjectResponse(
        Long id,
        String name,
        String description,
        UUID owner,
        String ownerUsername,
        long sampleCount,
        Instant createdOn,
        Instant modifiedOn,
        Long parentProjectId,
        String dataOwningOrganization,
        String detailedDescription,
        String comments
) {
}
