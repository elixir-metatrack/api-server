package no.metatrack.server.project;

import no.metatrack.server.auth.VerifiedEmailIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProjectInvitationTest {
    private static final Instant NOW = Instant.parse("2026-09-10T08:00:00Z");

    @Test
    void bindsOnlyMatchingVerifiedEmailAndNeverReassignsSubject() {
        var invitation = invitation();
        var identity = new VerifiedEmailIdentity(UUID.randomUUID(), " Invitee@Example.org ");
        assertTrue(invitation.bindRecipient(identity, NOW));
        assertFalse(invitation.bindRecipient(identity, NOW));
        assertThrows(IllegalArgumentException.class, () -> invitation.bindRecipient(
                new VerifiedEmailIdentity(UUID.randomUUID(), identity.email()), NOW));
        assertThrows(IllegalArgumentException.class, () -> invitation.bindRecipient(
                new VerifiedEmailIdentity(identity.subject(), "other@example.org"), NOW));
        assertEquals(identity.subject(), invitation.recipientId);
    }

    @Test
    void terminalDecisionsAreIdempotentButCannotBeChanged() {
        for (var decision : new InvitationStatus[]{InvitationStatus.ACCEPTED, InvitationStatus.DECLINED, InvitationStatus.REVOKED}) {
            var invitation = invitation();
            invitation.recipientId = UUID.randomUUID();
            assertTrue(invitation.transitionTo(decision, NOW));
            assertFalse(invitation.transitionTo(decision, NOW.plusSeconds(1000)));
            assertEquals(NOW, invitation.respondedOn);
            assertFalse(invitation.expire(NOW.plusSeconds(1000)));
            for (var other : new InvitationStatus[]{InvitationStatus.ACCEPTED, InvitationStatus.DECLINED, InvitationStatus.REVOKED}) {
                if (other != decision) {
                    assertThrows(IllegalStateException.class, () -> invitation.transitionTo(other, NOW));
                }
            }
            invitation.validateStorage();
        }
    }

    @Test
    void expiryIsEnforcedAtTheExactBoundaryWithoutAScheduler() {
        var invitation = invitation();
        assertTrue(invitation.isActionable(invitation.expiresOn.minusNanos(1)));
        assertFalse(invitation.isActionable(invitation.expiresOn));
        assertThrows(IllegalStateException.class, () -> invitation.transitionTo(InvitationStatus.REVOKED, invitation.expiresOn));
        assertThrows(IllegalStateException.class, () -> invitation.bindRecipient(
                new VerifiedEmailIdentity(UUID.randomUUID(), invitation.recipientEmail), invitation.expiresOn));
        assertFalse(invitation.expire(NOW));
        assertTrue(invitation.expire(invitation.expiresOn));
        assertFalse(invitation.expire(invitation.expiresOn));
        assertEquals(InvitationStatus.EXPIRED, invitation.status);
        invitation.validateStorage();
    }

    @Test
    void storageRejectsInvalidResponseTimestamps() {
        var invitation = invitation();
        invitation.status = InvitationStatus.EXPIRED;
        invitation.respondedOn = NOW;
        assertThrows(IllegalArgumentException.class, invitation::validateStorage);
        invitation.status = InvitationStatus.REVOKED;
        invitation.respondedOn = invitation.expiresOn;
        assertThrows(IllegalArgumentException.class, invitation::validateStorage);
        invitation.respondedOn = NOW.minusSeconds(1);
        assertThrows(IllegalArgumentException.class, invitation::validateStorage);
        invitation.respondedOn = null;
        assertThrows(IllegalArgumentException.class, invitation::validateStorage);
    }

    @Test
    void rejectsInvalidRolesStatesAndUnboundResponses() {
        var invitation = invitation();
        assertThrows(IllegalStateException.class, () -> invitation.transitionTo(InvitationStatus.ACCEPTED, NOW));
        assertThrows(IllegalStateException.class, () -> invitation.transitionTo(InvitationStatus.DECLINED, NOW));
        assertThrows(IllegalArgumentException.class, () -> invitation.transitionTo(InvitationStatus.PENDING, NOW));
        assertThrows(IllegalArgumentException.class, () -> invitation.transitionTo(InvitationStatus.EXPIRED, NOW));
        invitation.role = ProjectRole.OWNER;
        assertThrows(IllegalArgumentException.class, invitation::validateStorage);
        invitation.role = ProjectRole.VIEWER;
        invitation.expiresOn = invitation.createdOn;
        assertThrows(IllegalArgumentException.class, invitation::validateStorage);
    }

    private ProjectInvitation invitation() {
        var invitation = new ProjectInvitation();
        invitation.recipientEmail = " Invitee@Example.org ";
        invitation.role = ProjectRole.VIEWER;
        invitation.createdOn = NOW;
        invitation.expiresOn = NOW.plusSeconds(600);
        invitation.validateStorage();
        return invitation;
    }
}