package no.metatrack.server.project;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.util.UUID;

@Path("/api/invitations")
@Authenticated
@Produces("application/json")
public class ProjectInvitationDecisionController {
    @Inject
    ProjectInvitationService invitationService;

    @POST
    @Path("/{id}/accept")
    @Operation(summary = "Accept an invitation", description = "Requires matching verified email and, once bound, recipient subject. Repeating acceptance is idempotent; existing membership is preserved.")
    @APIResponse(responseCode = "200", description = "Accepted invitation", content = @Content(schema = @Schema(implementation = ProjectInvitationResponse.class)))
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Verified email required or inviter authority lost")
    @APIResponse(responseCode = "404", description = "Invitation missing or belongs to another recipient")
    @APIResponse(responseCode = "409", description = "Invitation already decided differently or expired")
    public ProjectInvitationResponse accept(@PathParam("id") UUID id) {
        return invitationService.accept(id);
    }

    @POST
    @Path("/{id}/decline")
    @Operation(summary = "Decline an invitation", description = "Requires matching verified email and, once bound, recipient subject. Repeating decline is idempotent; no membership is created.")
    @APIResponse(responseCode = "200", description = "Declined invitation", content = @Content(schema = @Schema(implementation = ProjectInvitationResponse.class)))
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Verified email required")
    @APIResponse(responseCode = "404", description = "Invitation missing or belongs to another recipient")
    @APIResponse(responseCode = "409", description = "Invitation already decided differently or expired")
    public ProjectInvitationResponse decline(@PathParam("id") UUID id) {
        return invitationService.decline(id);
    }
}