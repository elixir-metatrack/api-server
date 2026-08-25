package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.auth.keycloak.KeycloakIdentity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ProjectMemberService {
    private final IdentityLookupService identityLookupService;

    public ProjectMemberService(IdentityLookupService identityLookupService) {
        this.identityLookupService = identityLookupService;
    }

    public List<ProjectMemberResponse> listAllProjectMembers(long projectId) {
        return toResponses(ProjectMember.listAllMembersInProject(projectId));
    }

    List<ProjectMemberResponse> toResponses(List<ProjectMember> members) {
        Map<UUID, Optional<KeycloakIdentity>> identities = identityLookupService.identities(
                members.stream().map(member -> member.memberId).toList()
        );
        return members.stream()
                .map(member -> {
                    KeycloakIdentity identity = identities.get(member.memberId).orElse(null);
                    return new ProjectMemberResponse(
                            member.memberId,
                            identity != null ? identity.username() : null,
                            identity != null ? identity.email() : null,
                            member.role
                    );
                })
                .toList();
    }
}
