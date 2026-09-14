package no.metatrack.server.notification;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Result of claiming eligible invitations for the verified recipient")
public record NotificationSyncResponse(@Schema(description = "New notifications created by this call", minimum = "0") int created) {}