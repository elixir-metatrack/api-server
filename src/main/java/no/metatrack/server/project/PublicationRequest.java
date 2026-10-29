package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PublicationRequest(
        @NotBlank @Size(max = 255) String title,
        List<@NotBlank String> links,
        List<@NotBlank String> pids,
        @Size(max = 255) String doi) {}