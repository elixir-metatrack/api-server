package no.metatrack.server.project;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectInvitationControllerTest {
    @Test
    void smtpFailurePreservesCreatedResponse() {
        var service = mock(ProjectInvitationService.class);
        var delivery = mock(no.metatrack.server.invitation.InvitationDeliveryTransactions.class);
        var email = mock(no.metatrack.server.invitation.InvitationEmailService.class);
        var invitation = new ProjectInvitation();
        invitation.id = UUID.randomUUID();
        invitation.project = new Project();
        invitation.project.id = 1L;
        invitation.inviterId = UUID.randomUUID();
        invitation.expiresOn = java.time.Instant.now().plusSeconds(600);
        var saved = ProjectInvitationResponse.from(invitation, java.time.Instant.now());
        var request = new CreateProjectInvitationRequest("a@example.org", ProjectRole.VIEWER);
        when(service.create(1L, request)).thenReturn(saved);
        var envelope = new no.metatrack.server.invitation.InvitationDeliveryTransactions.Envelope(request.email(), false,
                "Owner", "Project", request.role(), invitation.expiresOn);
        var attempt = new no.metatrack.server.invitation.InvitationDeliveryTransactions.Attempt(UUID.randomUUID(), envelope, saved);
        when(delivery.claim(1L, saved.id(), saved.inviterId())).thenReturn(attempt);
        var mail = io.quarkus.mailer.Mail.withText(request.email(), "Invitation", "Body");
        when(email.render(envelope, false)).thenReturn(mail);
        doThrow(new RuntimeException("SMTP disconnected")).when(email).send(mail);
        var unknown = saved.withDelivery(no.metatrack.server.invitation.DeliveryStatus.UNKNOWN, null, null, null);
        when(delivery.complete(saved.id(), attempt.id(), no.metatrack.server.invitation.DeliveryStatus.UNKNOWN)).thenReturn(unknown);
        var controller = new ProjectInvitationController();
        controller.invitationCoordinator = new no.metatrack.server.invitation.InvitationCoordinator(service, delivery, email,
                mock(no.metatrack.server.auth.keycloak.IdentityLookupService.class), mock(no.metatrack.server.auth.UserService.class));
        try (var response = controller.create(1L, request)) {
            assertEquals(201, response.getStatus());
            assertEquals(unknown, response.getEntity());
        }
    }

    @Test
    void delegatesRoutesAndReturnsCreated() {
        var service = mock(ProjectInvitationService.class);
        var controller = new ProjectInvitationController();
        controller.invitationService = service;
        var coordinator = mock(no.metatrack.server.invitation.InvitationCoordinator.class);
        controller.invitationCoordinator = coordinator;
        var request = new CreateProjectInvitationRequest("a@example.org", ProjectRole.VIEWER);
        UUID id = UUID.randomUUID();
        try (var response = controller.create(1L, request)) {
            assertEquals(201, response.getStatus());
        }
        when(service.list(1L, 0, 20)).thenReturn(List.of());
        assertEquals(List.of(), controller.list(1L, 0, 20));
        controller.revoke(1L, id);
        var decisions = new ProjectInvitationDecisionController();
        decisions.invitationService = service;
        decisions.accept(id);
        decisions.decline(id);
        verify(coordinator).create(1L, request);
        controller.resend(1L, id);
        verify(coordinator).resend(1L, id);
        verify(service).revoke(1L, id);
        verify(service).accept(id);
        verify(service).decline(id);
        assertNotNull(ProjectInvitationController.class.getAnnotation(io.quarkus.security.Authenticated.class));
        assertNotNull(ProjectInvitationDecisionController.class.getAnnotation(io.quarkus.security.Authenticated.class));
    }
}