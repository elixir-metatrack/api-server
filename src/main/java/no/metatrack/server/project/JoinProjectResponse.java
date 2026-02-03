package no.metatrack.server.project;

import java.util.UUID;

public record JoinProjectResponse(long projectId, UUID userId, ProjectRole role) {
    public static JoinProjectResponse fromEntity(JoinProject joinProject) {
        return new JoinProjectResponse(joinProject.projectId, joinProject.userId, joinProject.role);
    }
}
