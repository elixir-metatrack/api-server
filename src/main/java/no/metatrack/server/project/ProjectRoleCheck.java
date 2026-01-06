package no.metatrack.server.project;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.UserService;

import java.util.UUID;

@RequestScoped
public class ProjectRoleCheck {

    @Inject
    UserService userService;

    public boolean isAtLeast(Long projectId, ProjectRole requiredRole) {
        // check if user is member of project

        if (!Project.projectExists(projectId))
            throw new WebApplicationException("No project with id " + projectId + " exists", 404);

        UUID currentUserId = UUID.fromString(userService.requireCurrentUser().id());

        ProjectMember member = ProjectMember.findMemberInProjectOptional(currentUserId, projectId)
                .orElseThrow(() -> new WebApplicationException("User is not a member of project" + projectId, 403));

        // check current role
        ProjectRole memberRole = member.role;

        switch (requiredRole) {
            case VIEWER -> {
                return true;
            }
            case EDITOR -> {
                if (memberRole == ProjectRole.EDITOR
                        || memberRole == ProjectRole.ADMIN
                        || memberRole == ProjectRole.OWNER) {
                    return true;
                }
            }
            case ADMIN -> {
                if (memberRole == ProjectRole.ADMIN || memberRole == ProjectRole.OWNER) {
                    return true;
                }
            }
            case OWNER -> {
                if (memberRole == ProjectRole.OWNER) {
                    return true;
                }
            }
        }
        return false;
    }
}
