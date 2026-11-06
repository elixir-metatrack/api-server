package no.metatrack.server.project;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import no.metatrack.server.auth.UserService;

import java.util.UUID;

@RequestScoped
public class ProjectRoleCheck {

    @Inject
    UserService userService;

    @Inject
    ProjectAuthorizationService authorizationService;

    public boolean isAtLeast(Long projectId, ProjectRole requiredRole) {
        UUID currentUserId = UUID.fromString(userService.requireCurrentUser().id());
        return authorizationService.isAtLeast(currentUserId, projectId, requiredRole);
    }
}
