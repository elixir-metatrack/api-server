package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Sub-project details. The optional sample IDs must belong to the parent project; omitted organization inherits the parent's organization.")
public record CreateSubProjectRequest(
        @NotBlank
        @Pattern(
                regexp = "^[a-zA-Z0-9_-]+$",
                message = "Field can only contain alphanumeric characters, hyphens, and underscores")
        String name,

        String description,

        @Schema(description = "Optional sample UUIDs to link from the parent project")
        List<UUID> sampleIds,
        @Size(max = 255) String dataOwningOrganization,
        String detailedDescription,
        String comments) {}
