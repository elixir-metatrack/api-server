package no.metatrack.server.notification;

import no.metatrack.server.project.InvitationStatus;
import no.metatrack.server.project.ProjectRole;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Recipient notification; read state does not decide the invitation")
public record NotificationResponse(UUID id, NotificationType type, Instant createdOn,
                                   @Schema(description = "Null means unread", nullable = true) Instant readOn,
                                   String title, String content,
                                   @Schema(nullable = true) Invitation invitation) {
    static NotificationResponse from(Notification notification, Instant now) {
        var source = notification.invitation;
        Invitation invitation = null;
        if (source != null) {
            boolean actionable = source.isActionable(now);
            var status = source.status == InvitationStatus.PENDING && !actionable
                    ? InvitationStatus.EXPIRED : source.status;
            invitation = new Invitation(source.id, source.project.id, source.role, status, source.expiresOn, actionable);
        }
        return new NotificationResponse(notification.id, notification.type, notification.createdOn,
                notification.readOn, notification.title, notification.content, invitation);
    }

    @Schema(name = "NotificationInvitation", description = "Live invitation summary without recipient email or delivery details")
    public record Invitation(UUID id, Long projectId, ProjectRole role, InvitationStatus status,
                             Instant expiresOn,
                             @Schema(description = "True only while pending and not expired; acceptance also rechecks inviter authority") boolean actionable) {}
}