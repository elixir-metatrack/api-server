package no.metatrack.server.notification;

import no.metatrack.server.project.InvitationStatus;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectInvitation;
import no.metatrack.server.project.ProjectRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationResponseTest {
    @Test
    void lifecycleIsDerivedAtExpiryBoundaryIndependentlyOfReadState() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var invitation = new ProjectInvitation();
        invitation.id = UUID.randomUUID();
        invitation.project = new Project();
        invitation.project.id = 1L;
        invitation.project.name = "Project";
        invitation.recipientId = UUID.randomUUID();
        invitation.inviterDisplayName = "Inviter";
        invitation.role = ProjectRole.EDITOR;
        invitation.expiresOn = now.plusSeconds(1);
        var notification = Notification.forInvitation(invitation, now);
        assertTrue(NotificationResponse.from(notification, now).invitation().actionable());
        notification.readOn = now;
        assertTrue(NotificationResponse.from(notification, now).invitation().actionable());
        var expired = NotificationResponse.from(notification, invitation.expiresOn);
        assertFalse(expired.invitation().actionable());
        assertEquals(InvitationStatus.EXPIRED, expired.invitation().status());
        assertEquals(InvitationStatus.PENDING, invitation.status);
        for (var status : new InvitationStatus[]{InvitationStatus.ACCEPTED, InvitationStatus.DECLINED, InvitationStatus.REVOKED}) {
            invitation.status = status;
            assertFalse(NotificationResponse.from(notification, now).invitation().actionable());
            assertEquals(status, NotificationResponse.from(notification, now).invitation().status());
        }
    }
}