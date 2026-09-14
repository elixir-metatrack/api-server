package no.metatrack.server.notification;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.util.UUID;

@Path("/api/notifications")
@Authenticated
@Produces("application/json")
@Consumes("application/json")
public class NotificationController {
    @Inject
    NotificationService notificationService;

    @POST
    @Path("/sync")
    @Operation(summary = "Synchronize invitation notifications", description = "Requires verified email. Claims eligible unbound invitations without accepting them; repeated synchronization is idempotent.")
    @APIResponse(responseCode = "200", description = "Synchronization result", content = @Content(schema = @Schema(implementation = NotificationSyncResponse.class)))
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Verified email required")
    public NotificationSyncResponse synchronize() {
        return notificationService.synchronize();
    }

    @GET
    @Operation(summary = "List current user's notifications", description = "Zero-based pages, size 1–100; newest first with UUID tie-breaker. unread=true selects unread, false selects read, omitted selects both. Does not synchronize or accept invitations.")
    @APIResponse(responseCode = "200", description = "Recipient-scoped notification page", content = @Content(schema = @Schema(implementation = NotificationPage.class)))
    @APIResponse(responseCode = "400", description = "Invalid pagination")
    @APIResponse(responseCode = "401", description = "Authentication required")
    public NotificationPage list(@QueryParam("page") @DefaultValue("0") int page,
                                 @QueryParam("size") @DefaultValue("20") int size,
                                 @QueryParam("unread") Boolean unread) {
        return notificationService.list(page, size, unread);
    }

    @PATCH
    @Path("/{id}")
    @Operation(summary = "Set notification read state", description = "Recipient only. Idempotent; independent of invitation acceptance or decline.")
    @APIResponse(responseCode = "204", description = "Read state updated; no response body")
    @APIResponse(responseCode = "400", description = "A non-null read flag is required")
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "404", description = "Notification missing or belongs to another recipient")
    public void updateRead(@PathParam("id") UUID id, @Valid UpdateNotificationRequest request) {
        notificationService.updateRead(id, request);
    }
}