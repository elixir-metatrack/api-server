package no.metatrack.server.project;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        String name,
        String description,
        @Size(max = 255) String dataOwningOrganization,
        String detailedDescription,
        String comments) {}
