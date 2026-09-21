package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

public record CreateSubProjectRequest(
        @NotBlank
        @Pattern(
                regexp = "^[a-zA-Z0-9_-]+$",
                message = "Field can only contain alphanumeric characters, hyphens, and underscores")
        String name,

        String description,

        List<UUID> sampleIds) {}
