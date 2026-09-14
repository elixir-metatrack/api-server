package no.metatrack.server.project;

import no.metatrack.server.invitation.DeliveryStatus;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Invitation lifecycle and independent email delivery outcome; contains no bearer token")
public record ProjectInvitationResponse(
        UUID id, Long projectId, String recipientEmail, UUID inviterId, String inviterDisplayName,
        ProjectRole role,
        @Schema(description = "Current lifecycle status; pending invitations past expiry are reported as EXPIRED") InvitationStatus status,
        Instant createdOn, Instant expiresOn,
        @Schema(description = "Decision time, or expiry time for expired invitations", nullable = true) Instant respondedOn,
        @Schema(description = "Independent delivery status; UNKNOWN does not prove delivery failed") DeliveryStatus deliveryStatus,
        @Schema(nullable = true) Instant deliveryAttemptedOn,
        @Schema(nullable = true) Instant deliveryCompletedOn,
        @Schema(description = "Earliest delivery retry time", nullable = true) Instant deliveryRetryAfter) {
    public static ProjectInvitationResponse from(ProjectInvitation invitation, Instant now) {
        boolean expired = invitation.status == InvitationStatus.PENDING && !invitation.isActionable(now);
        return new ProjectInvitationResponse(invitation.id, invitation.project.id, invitation.recipientEmail,
                invitation.inviterId, invitation.inviterDisplayName, invitation.role,
                expired ? InvitationStatus.EXPIRED : invitation.status, invitation.createdOn, invitation.expiresOn,
                expired ? invitation.expiresOn : invitation.respondedOn,
                invitation.deliveryStatus == DeliveryStatus.SENDING && invitation.deliveryRetryAfter != null
                        && !now.isBefore(invitation.deliveryRetryAfter) ? DeliveryStatus.UNKNOWN : invitation.deliveryStatus,
                invitation.deliveryAttemptedOn, invitation.deliveryCompletedOn, invitation.deliveryRetryAfter);
    }

    public ProjectInvitationResponse withDelivery(DeliveryStatus outcome, Instant attemptedOn, Instant completedOn, Instant retryAfter) {
        return new ProjectInvitationResponse(id, projectId, recipientEmail, inviterId, inviterDisplayName, role,
                status, createdOn, expiresOn, respondedOn, outcome, attemptedOn, completedOn, retryAfter);
    }
}