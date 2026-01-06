package no.metatrack.server.project;

import java.util.UUID;

public record ProjectResponse(Long id, String name, String description, UUID owner) {
    public static ProjectResponse fromEntity(Project project) {
        return new ProjectResponse(project.id, project.name, project.description, project.owner);
    }
}
