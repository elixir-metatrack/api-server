package no.metatrack.server.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        Long id, String name, String description, UUID owner, Instant createdOn, Instant modifiedOn) {
    public static ProjectResponse fromEntity(Project project) {
        return new ProjectResponse(
                project.id, project.name, project.description, project.owner, project.createdOn, project.modifiedOn);
    }
}
