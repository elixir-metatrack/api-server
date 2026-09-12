package no.metatrack.server.notification;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

@Schema(description = "Stable recipient-scoped page, ordered by creation time and UUID descending")
public record NotificationPage(List<NotificationResponse> items,
        @Schema(minimum = "0") int page,
        @Schema(minimum = "1", maximum = "100") int size,
        @Schema(description = "Total matching the unread filter", minimum = "0") long total,
        @Schema(description = "Unread count across the whole inbox, independent of filter and page", minimum = "0") long unreadCount) {}