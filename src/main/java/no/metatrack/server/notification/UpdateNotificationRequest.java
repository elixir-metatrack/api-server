package no.metatrack.server.notification;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Set read state without changing invitation state")
public record UpdateNotificationRequest(@Schema(description = "True marks read; false marks unread", required = true) @NotNull Boolean read) {}