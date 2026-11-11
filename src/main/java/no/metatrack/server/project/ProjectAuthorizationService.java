package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectAuthorizationService {
    @Inject
    UserService userService;

    public ProjectRole roleFor(UUID userId, Long projectId) {
        if (!Project.projectExists(projectId)) {
            throw new WebApplicationException("No project with id " + projectId + " exists", 404);
        }
        ProjectMember member = ProjectMember.findMemberInProjectOptional(userId, projectId)
                .orElseThrow(() -> new WebApplicationException("User is not a member of project " + projectId, 403));
        return member.role;
    }

    public boolean isAtLeast(UUID userId, Long projectId, ProjectRole requiredRole) {
        if (!Project.projectExists(projectId)) {
            throw new WebApplicationException("No project with id " + projectId + " exists", 404);
        }
        if (isCurrentSystemAdmin(userId)) {
            return true;
        }
        return roleRank(roleFor(userId, projectId)) >= roleRank(requiredRole);
    }

    public boolean hasAtLeast(UUID userId, Long projectId, ProjectRole requiredRole) {
        if (!Project.projectExists(projectId)) {
            throw new WebApplicationException("No project with id " + projectId + " exists", 404);
        }
        if (isCurrentSystemAdmin(userId)) {
            return true;
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

    private boolean isCurrentSystemAdmin(UUID userId) {
        if (userService == null) {
            return false;
        }
        try {
            CurrentUser currentUser = userService.requireCurrentUser();
            return currentUser.roles().contains("system-admin")
                    && UUID.fromString(currentUser.id()).equals(userId);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}