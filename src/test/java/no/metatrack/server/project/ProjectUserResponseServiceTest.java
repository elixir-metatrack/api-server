package no.metatrack.server.project;

import no.metatrack.server.auth.keycloak.IdentityLookupService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectUserResponseServiceTest {
    @Test
    void memberResponsesIncludeResolvedAndMissingUsernames() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID foundId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        when(identities.usernames(List.of(foundId, missingId)))
                .thenReturn(Map.of(foundId, Optional.of("member@example.org"), missingId, Optional.empty()));
        ProjectMember found = member(foundId, ProjectRole.EDITOR);
        ProjectMember missing = member(missingId, ProjectRole.VIEWER);

        List<ProjectMemberResponse> responses = new ProjectMemberService(identities).toResponses(List.of(found, missing));

        assertEquals(new ProjectMemberResponse(foundId, "member@example.org", ProjectRole.EDITOR), responses.getFirst());
        assertEquals(new ProjectMemberResponse(missingId, null, ProjectRole.VIEWER), responses.getLast());
    }

    @Test
    void repeatedJoinRequestUsersAreResolvedInOneBatch() {
        IdentityLookupService identities = mock(IdentityLookupService.class);
        UUID userId = UUID.randomUUID();
        List<UUID> requestedIds = List.of(userId, userId);
        when(identities.usernames(requestedIds)).thenReturn(Map.of(userId, Optional.of("joiner@example.org")));
        JoinProject first = joinRequest(1L, userId, ProjectRole.VIEWER);
        JoinProject second = joinRequest(2L, userId, ProjectRole.EDITOR);

        List<JoinProjectResponse> responses = new JoinProjectService(null, identities).toResponses(List.of(first, second));

        assertEquals("joiner@example.org", responses.getFirst().username());
        assertEquals("joiner@example.org", responses.getLast().username());
        verify(identities).usernames(requestedIds);
    }

    private ProjectMember member(UUID memberId, ProjectRole role) {
        ProjectMember member = new ProjectMember();
        member.memberId = memberId;
        member.role = role;
        return member;
    }

    private JoinProject joinRequest(long projectId, UUID userId, ProjectRole role) {
        JoinProject request = new JoinProject();
        request.projectId = projectId;
        request.userId = userId;
        request.role = role;
        return request;
    }
}