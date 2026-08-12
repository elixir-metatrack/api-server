package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import no.metatrack.server.auth.keycloak.IdentityLookupService;

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
        Map<UUID, Optional<String>> usernames = identityLookupService.usernames(
                members.stream().map(member -> member.memberId).toList()
        );
        return members.stream()
                .map(member -> new ProjectMemberResponse(
                        member.memberId,
                        usernames.get(member.memberId).orElse(null),
                        member.role
                ))
                .toList();
    }
}
