package no.metatrack.server.project;

import java.util.UUID;

public record ProjectMemberResponse(UUID memberId, ProjectRole role) {
    public static ProjectMemberResponse fromEntity(ProjectMember projectMember) {
        return new ProjectMemberResponse(projectMember.memberId, projectMember.role);
    }
}
