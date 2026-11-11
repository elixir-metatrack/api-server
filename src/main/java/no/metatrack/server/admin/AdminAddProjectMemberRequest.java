package no.metatrack.server.admin;

import jakarta.validation.constraints.NotNull;
import no.metatrack.server.project.ProjectRole;

public record AdminAddProjectMemberRequest(@NotNull ProjectRole role) {
}