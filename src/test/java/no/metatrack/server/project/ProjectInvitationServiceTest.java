package no.metatrack.server.project;

import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.auth.keycloak.KeycloakUserRepresentation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectInvitationServiceTest {
    private final UserService users = mock(UserService.class);
    private final ProjectRoleCheck roles = mock(ProjectRoleCheck.class);
    private final IdentityLookupService identities = mock(IdentityLookupService.class);
    private final ProjectInvitationTransactions transactions = mock(ProjectInvitationTransactions.class);
    private final ProjectInvitationService service = new ProjectInvitationService(users, roles, identities, transactions);
    private final UUID inviter = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        when(users.requireCurrentUser()).thenReturn(new CurrentUser(inviter.toString(), "Inviter", Set.of(), null, null, null));
    }

    @Test
    void authorizationPrecedesLookupAndValidation() {
        assertStatus(403, () -> service.create(1L, null));
        verifyNoInteractions(identities, transactions);
        when(users.requireCurrentUser()).thenThrow(new WebApplicationException(401));
        assertStatus(401, () -> service.create(1L, null));
    }

    @Test
    void invalidRolesAndEmailNeverReachLookup() {
        when(roles.isAtLeast(1L, ProjectRole.ADMIN)).thenReturn(true);
        for (var request : new CreateProjectInvitationRequest[]{null,
                new CreateProjectInvitationRequest("a@example.org", null),
                new CreateProjectInvitationRequest("a@example.org", ProjectRole.OWNER),
                new CreateProjectInvitationRequest(null, ProjectRole.VIEWER),
                new CreateProjectInvitationRequest("invalid", ProjectRole.VIEWER)}) {
            assertStatus(400, () -> service.create(1L, request));
        }
        verifyNoInteractions(identities, transactions);
    }

    @Test
    void normalizesAndBindsExistingAccountWithoutExposingRegistration() {
        when(roles.isAtLeast(1L, ProjectRole.ADMIN)).thenReturn(true);
        UUID recipient = UUID.randomUUID();
        when(identities.findByEmail("a@example.org")).thenReturn(Optional.of(
                new KeycloakUserRepresentation(recipient.toString(), "Recipient", "a@example.org", true)));
        service.create(1L, new CreateProjectInvitationRequest(" A@Example.org ", ProjectRole.ADMIN));
        var order = inOrder(roles, identities, transactions);
        order.verify(roles).isAtLeast(1L, ProjectRole.ADMIN);
        order.verify(identities).findByEmail("a@example.org");
        order.verify(transactions).create(1L, "a@example.org", recipient, inviter, "Inviter", ProjectRole.ADMIN);
        assertTrue(java.util.Arrays.stream(ProjectInvitationResponse.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("recipientId")));
    }

    @Test
    void missingAccountIsUnboundButLookupFailureDoesNotCreate() {
        when(roles.isAtLeast(1L, ProjectRole.ADMIN)).thenReturn(true);
        when(identities.findByEmail("a@example.org")).thenReturn(Optional.empty());
        service.create(1L, new CreateProjectInvitationRequest("a@example.org", ProjectRole.VIEWER));
        verify(transactions).create(1L, "a@example.org", null, inviter, "Inviter", ProjectRole.VIEWER);
        clearInvocations(transactions);
        when(identities.findByEmail("a@example.org")).thenThrow(new KeycloakIdentityException("Unavailable"));
        assertThrows(KeycloakIdentityException.class,
                () -> service.create(1L, new CreateProjectInvitationRequest("a@example.org", ProjectRole.VIEWER)));
        verifyNoInteractions(transactions);
    }

    @Test
    void decisionsRequireVerifiedIdentityAndNeverAcceptCallerSuppliedEmail() {
        UUID id = UUID.randomUUID();
        when(users.requireVerifiedEmailIdentity()).thenThrow(new WebApplicationException(403));
        assertStatus(403, () -> service.accept(id));
        assertStatus(403, () -> service.decline(id));
        verifyNoInteractions(transactions);
        var identity = new VerifiedEmailIdentity(UUID.randomUUID(), "a@example.org");
        doReturn(identity).when(users).requireVerifiedEmailIdentity();
        service.accept(id);
        service.decline(id);
        verify(transactions).decide(id, identity, InvitationStatus.ACCEPTED);
        verify(transactions).decide(id, identity, InvitationStatus.DECLINED);
    }

    @Test
    void administrativeListAndRevokeRequireRoleAndPaginationIsBounded() {
        assertStatus(403, () -> service.list(1L, 0, 20));
        assertStatus(403, () -> service.revoke(1L, UUID.randomUUID()));
        when(roles.isAtLeast(1L, ProjectRole.ADMIN)).thenReturn(true);
        for (int[] pagination : new int[][]{{-1, 20}, {0, 0}, {0, 101}, {Integer.MAX_VALUE, 100}}) {
            assertStatus(400, () -> service.list(1L, pagination[0], pagination[1]));
        }
        verifyNoInteractions(transactions);
        service.list(1L, 2, 20);
        verify(transactions).list(1L, inviter, 2, 20);
    }

    private void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(WebApplicationException.class, action::run).getResponse().getStatus());
    }
}