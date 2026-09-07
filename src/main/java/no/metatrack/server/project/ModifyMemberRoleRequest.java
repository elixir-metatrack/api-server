package no.metatrack.server.project;

import jakarta.validation.constraints.NotNull;

public record ModifyMemberRoleRequest(@NotNull ProjectRole role) {}
