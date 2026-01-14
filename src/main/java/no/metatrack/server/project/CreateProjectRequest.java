package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateProjectRequest(
        @NotBlank
        @Pattern(
                regexp = "^[a-zA-Z0-9_-]+$",
                message = "Field can only contain alphanumeric characters, hyphens, and underscores")
        String name,

        String description) {}
