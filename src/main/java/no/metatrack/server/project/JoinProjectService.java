package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.auth.keycloak.IdentityLookupService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JoinProjectService {
    private final ProjectService projectService;
    private final IdentityLookupService identityLookupService;

    public JoinProjectService(ProjectService projectService, IdentityLookupService identityLookupService) {
        this.projectService = projectService;
        this.identityLookupService = identityLookupService;
    }

    @Transactional
    public void joinProject(long projectId, UUID userId, ProjectRole role) {
        JoinProject joinProject = new JoinProject();
        joinProject.projectId = projectId;
        joinProject.userId = userId;
        joinProject.role = role;
        joinProject.persist();
    }

    public List<JoinProjectResponse> getJoinRequests(long projectId) {
        return toResponses(JoinProject.list("projectId", projectId));
    }

    List<JoinProjectResponse> toResponses(List<JoinProject> joinRequests) {
        Map<UUID, Optional<String>> usernames = identityLookupService.usernames(
                joinRequests.stream().map(joinRequest -> joinRequest.userId).toList()
        );
        return joinRequests.stream()
                .map(joinRequest -> new JoinProjectResponse(
                        joinRequest.projectId,
                        joinRequest.userId,
                        usernames.get(joinRequest.userId).orElse(null),
                        joinRequest.role
                ))
                .toList();
    }

    @Transactional
    public void removeJoinRequest(long projectId, UUID userId) {
        JoinProject joinProject = JoinProject.findByUserIdAndProjectId(projectId, userId);
        if (joinProject == null) {
            throw new NotFoundException("Join request not found");
        }
        joinProject.delete();
    }

    @Transactional
    public void approveJoinRequest(long projectId, UUID userId) {
        JoinProject joinRequest = JoinProject.findByUserIdAndProjectId(projectId, userId);
        if (joinRequest == null) {
            throw new NotFoundException("Join request not found");
        }
        projectService.addMember(projectId, userId, joinRequest.role);
        joinRequest.delete();
    }
}
