package no.metatrack.server.invitation;

import io.quarkus.mailer.Mail;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.auth.keycloak.KeycloakUserRepresentation;
import no.metatrack.server.project.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvitationCoordinatorTest {
    private final ProjectInvitationService invitations = mock(ProjectInvitationService.class);
    private final InvitationDeliveryTransactions delivery = mock(InvitationDeliveryTransactions.class);
    private final InvitationEmailService email = mock(InvitationEmailService.class);
    private final IdentityLookupService identities = mock(IdentityLookupService.class);
    private final UserService users = mock(UserService.class);
    private final InvitationCoordinator coordinator = new InvitationCoordinator(invitations, delivery, email, identities, users);
    private final CreateProjectInvitationRequest request = new CreateProjectInvitationRequest("recipient@example.org", ProjectRole.EDITOR);
    private ProjectInvitationResponse saved;
    private InvitationDeliveryTransactions.Attempt attempt;
    private Mail mail;

    @BeforeEach
    void setup() {
        var invitation = new ProjectInvitation();
        invitation.id = UUID.randomUUID();
        invitation.project = new Project();
        invitation.project.id = 1L;
        invitation.inviterId = UUID.randomUUID();
        invitation.recipientEmail = request.email();
        invitation.createdOn = Instant.parse("2026-09-10T10:00:00Z");
        invitation.expiresOn = invitation.createdOn.plusSeconds(604800);
        saved = ProjectInvitationResponse.from(invitation, invitation.createdOn);
        var envelope = new InvitationDeliveryTransactions.Envelope(request.email(), false, "Owner", "Project", request.role(), saved.expiresOn());
        attempt = new InvitationDeliveryTransactions.Attempt(UUID.randomUUID(), envelope,
                saved.withDelivery(DeliveryStatus.SENDING, saved.createdOn(), null, saved.createdOn().plusSeconds(300)));
        when(invitations.create(1L, request)).thenReturn(saved);
        when(delivery.claim(1L, saved.id(), saved.inviterId())).thenReturn(attempt);
        mail = Mail.withText(request.email(), "Invitation", "Body");
        when(email.render(envelope, false)).thenReturn(mail);
        when(delivery.complete(eq(saved.id()), eq(attempt.id()), any())).thenAnswer(call ->
                saved.withDelivery(call.getArgument(2), saved.createdOn(), saved.createdOn(), saved.createdOn().plusSeconds(60)));
    }

    @Test
    void commitsBeforeSendingAndRecordsSuccess() {
        assertEquals(DeliveryStatus.SENT, coordinator.create(1L, request).deliveryStatus());
        var order = inOrder(invitations, delivery, email);
        order.verify(invitations).create(1L, request);
        order.verify(delivery).claim(1L, saved.id(), saved.inviterId());
        order.verify(email).render(attempt.envelope(), false);
        order.verify(email).send(mail);
        order.verify(delivery).complete(saved.id(), attempt.id(), DeliveryStatus.SENT);
    }

    @Test
    void smtpTimeoutIsUncertainAndPreservesInvitation() {
        doThrow(new RuntimeException("SMTP timeout with private details")).when(email).send(mail);
        var response = coordinator.create(1L, request);
        assertEquals(saved.id(), response.id());
        assertEquals(saved.expiresOn(), response.expiresOn());
        assertEquals(DeliveryStatus.UNKNOWN, response.deliveryStatus());
    }

    @Test
    void renderingFailureIsKnownFailureWithoutSmtp() {
        when(email.render(attempt.envelope(), false)).thenThrow(new IllegalArgumentException());
        assertEquals(DeliveryStatus.FAILED, coordinator.create(1L, request).deliveryStatus());
        verify(email, never()).send(any());
    }

    @Test
    void recordingFailureReturnsUncertaintyWithoutRetryingSend() {
        when(delivery.complete(saved.id(), attempt.id(), DeliveryStatus.SENT)).thenThrow(new IllegalStateException());
        var response = coordinator.create(1L, request);
        assertEquals(DeliveryStatus.UNKNOWN, response.deliveryStatus());
        assertEquals(attempt.response().deliveryRetryAfter(), response.deliveryRetryAfter());
        verify(email, times(1)).send(mail);
    }

    @Test
    void failedClaimNeverSendsButCreationStillReturnsSavedId() {
        when(delivery.claim(1L, saved.id(), saved.inviterId())).thenThrow(new IllegalStateException());
        assertEquals(saved.id(), coordinator.create(1L, request).id());
        verifyNoInteractions(email);
    }

    @Test
    void failedPersistenceNeverSends() {
        when(invitations.create(1L, request)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> coordinator.create(1L, request));
        verifyNoInteractions(delivery, email);
    }

    @Test
    void resendRechecksRegistrationAndPropagatesLookupFailureWithoutClaim() {
        when(users.requireCurrentUser()).thenReturn(new CurrentUser(saved.inviterId().toString(), "Owner", Set.of(), null, null, null));
        when(delivery.prepare(1L, saved.id(), saved.inviterId())).thenReturn(attempt.envelope());
        when(identities.findByEmail(request.email())).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> coordinator.resend(1L, saved.id()));
        verify(delivery, never()).claim(any(), any(), any());
        doReturn(Optional.of(new KeycloakUserRepresentation(UUID.randomUUID().toString(), "Recipient", request.email(), true)))
                .when(identities).findByEmail(request.email());
        when(email.render(attempt.envelope(), true)).thenReturn(mail);
        var response = coordinator.resend(1L, saved.id());
        assertEquals(saved.expiresOn(), response.expiresOn());
        verify(email).render(attempt.envelope(), true);
    }
}