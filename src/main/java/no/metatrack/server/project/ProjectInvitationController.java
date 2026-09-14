package no.metatrack.server.project;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.invitation.InvitationCoordinator;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.util.List;
import java.util.UUID;

@Path("/api/projects/{projectId}/invitations")
@Authenticated
@Produces("application/json")
@Consumes("application/json")
public class ProjectInvitationController {
    @Inject
    ProjectInvitationService invitationService;

    @Inject
    InvitationCoordinator invitationCoordinator;

    @POST
    @Operation(summary = "Invite a project member", description = "Requires project ADMIN or OWNER. Persistence succeeds independently of email delivery; inspect deliveryStatus.")
    @APIResponse(responseCode = "201", description = "Invitation persisted", content = @Content(schema = @Schema(implementation = ProjectInvitationResponse.class)))
    @APIResponse(responseCode = "400", description = "Invalid email or role; OWNER cannot be invited")
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Project ADMIN or OWNER required")
    @APIResponse(responseCode = "409", description = "Active invitation or existing membership")
    @APIResponse(responseCode = "502", description = "Identity lookup failed before persistence")
    public Response create(@PathParam("projectId") Long projectId, @Valid CreateProjectInvitationRequest request) {
        return Response.status(Response.Status.CREATED).entity(invitationCoordinator.create(projectId, request)).build();
    }

    @POST
    @Path("/{id}/resend")
    @Operation(summary = "Resend an invitation", description = "Requires project ADMIN or OWNER. Does not extend expiry or create another invitation.")
    @APIResponse(responseCode = "200", description = "Delivery attempted", content = @Content(schema = @Schema(implementation = ProjectInvitationResponse.class)))
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Project ADMIN or OWNER required")
    @APIResponse(responseCode = "404", description = "Project or project-scoped invitation not found")
    @APIResponse(responseCode = "409", description = "Invitation is no longer actionable")
    @APIResponse(responseCode = "429", description = "Delivery in progress or cooldown; retry after deliveryRetryAfter")
    @APIResponse(responseCode = "502", description = "Identity lookup failed before delivery")
    public ProjectInvitationResponse resend(@PathParam("projectId") Long projectId, @PathParam("id") UUID id) {
        return invitationCoordinator.resend(projectId, id);
    }

    @GET
    @Operation(summary = "List project invitations", description = "Requires project ADMIN or OWNER. Zero-based pages, size 1–100; newest first, with UUID tie-breaker.")
    @APIResponse(responseCode = "200", description = "Invitation page", content = @Content(schema = @Schema(type = org.eclipse.microprofile.openapi.annotations.enums.SchemaType.ARRAY, implementation = ProjectInvitationResponse.class)))
    @APIResponse(responseCode = "400", description = "Invalid pagination")
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Project ADMIN or OWNER required")
    public List<ProjectInvitationResponse> list(@PathParam("projectId") Long projectId,
            @QueryParam("page") @DefaultValue("0") int page, @QueryParam("size") @DefaultValue("20") int size) {
        return invitationService.list(projectId, page, size);
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Revoke an invitation", description = "Requires project ADMIN or OWNER. Repeating a revocation is idempotent.")
    @APIResponse(responseCode = "200", description = "Revoked invitation", content = @Content(schema = @Schema(implementation = ProjectInvitationResponse.class)))
    @APIResponse(responseCode = "401", description = "Authentication required")
    @APIResponse(responseCode = "403", description = "Project ADMIN or OWNER required")
    @APIResponse(responseCode = "404", description = "Project-scoped invitation not found")
    @APIResponse(responseCode = "409", description = "Invitation already decided or expired")
    public ProjectInvitationResponse revoke(@PathParam("projectId") Long projectId, @PathParam("id") UUID id) {
        return invitationService.revoke(projectId, id);
    }
}