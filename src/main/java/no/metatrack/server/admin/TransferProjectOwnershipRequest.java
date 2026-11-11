package no.metatrack.server.admin;

import jakarta.validation.constraints.NotNull;
import no.metatrack.server.project.ProjectRole;

import java.util.UUID;

public record TransferProjectOwnershipRequest(@NotNull UUID newOwnerId, @NotNull ProjectRole formerOwnerRole) {
}