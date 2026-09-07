package no.metatrack.server.project;

import jakarta.validation.constraints.NotNull;

public record AddMemberRequest(@NotNull ProjectRole role) {}
