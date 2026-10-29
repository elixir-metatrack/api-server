package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "New project details. The authenticated caller becomes owner; data-owning organization is required.")
public record CreateProjectRequest(
        @NotBlank
        @Schema(description = "Unique project name")
        String name,

        String description,
        @NotBlank @Size(max = 255) String dataOwningOrganization,
        String detailedDescription,
        String comments) {}
