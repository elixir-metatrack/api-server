package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;

import java.util.UUID;
import java.util.Optional;

@ApplicationScoped
public class ProjectAuthorizationService {
    public ProjectRole roleFor(UUID userId, Long projectId) {
        if (!Project.projectExists(projectId)) {
            throw new WebApplicationException("No project with id " + projectId + " exists", 404);
        }
        ProjectMember member = ProjectMember.findMemberInProjectOptional(userId, projectId)
                .orElseThrow(() -> new WebApplicationException("User is not a member of project " + projectId, 403));
        return member.role;
    }

    public boolean isAtLeast(UUID userId, Long projectId, ProjectRole requiredRole) {
        return roleRank(roleFor(userId, projectId)) >= roleRank(requiredRole);
    }

    public boolean hasAtLeast(UUID userId, Long projectId, ProjectRole requiredRole) {
        if (!Project.projectExists(projectId)) {
            throw new WebApplicationException("No project with id " + projectId + " exists", 404);
        }
        return ProjectMember.findMemberInProjectOptional(userId, projectId)
                .map(member -> roleRank(member.role) >= roleRank(requiredRole))
                .orElse(false);
    }

    public void requireUploadRights(UUID userId, Long projectId) {
        Project project = Project.<Project>findByIdOptional(projectId)
                .orElseThrow(() -> new WebApplicationException("No project with id " + projectId + " exists", 404));
        requireAtLeast(userId, project.id, ProjectRole.EDITOR);
        if (project.parentProject != null) {
            requireAtLeast(userId, project.parentProject.id, ProjectRole.EDITOR);
        }
    }

    public void requireAtLeast(UUID userId, Long projectId, ProjectRole requiredRole) {
        if (!isAtLeast(userId, projectId, requiredRole)) {
            throw new ForbiddenException("Project " + requiredRole + " membership required");
        }
    }

    private int roleRank(ProjectRole role) {
        return switch (role) {
            case VIEWER -> 1;
            case EDITOR -> 2;
            case ADMIN -> 3;
            case OWNER -> 4;
        };
    }
}