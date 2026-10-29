package no.metatrack.server.project;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Partial project update. Only non-null fields are applied; null and omitted fields remain unchanged.")
public record UpdateProjectRequest(
        String name,
        String description,
        @Size(max = 255) String dataOwningOrganization,
        String detailedDescription,
        String comments) {}
