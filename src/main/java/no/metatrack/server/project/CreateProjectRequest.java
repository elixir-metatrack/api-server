package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank
        String name,

        String description,
        @NotBlank @Size(max = 255) String dataOwningOrganization,
        String detailedDescription,
        String comments) {}
