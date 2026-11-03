package no.metatrack.server.project;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        String comments,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Authenticated caller's membership role; only included by GET /api/projects/me")
        ProjectRole role
) {
}
