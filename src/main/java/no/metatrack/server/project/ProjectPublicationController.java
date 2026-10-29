package no.metatrack.server.project;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.util.List;

@Path("/api/projects/{projectId}/publications")
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Project publications", description = "Publication records associated with a project")
public class ProjectPublicationController {
    @Inject
    ProjectPublicationService publicationService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    @Operation(summary = "List project publications", description = "Requires VIEWER membership in the project.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    public List<PublicationResponse> list(@PathParam("projectId") Long projectId) {
        requireRole(projectId, ProjectRole.VIEWER);
        return publicationService.list(projectId);
    }

    @POST
    @Operation(summary = "Add a project publication", description = "Requires OWNER membership. Returns the created publication and its resource location.")
    @APIResponse(responseCode = "201", description = "Publication created")
    @APIResponse(responseCode = "403", description = "Project OWNER membership required")
    public Response create(@PathParam("projectId") Long projectId, @NotNull @Valid PublicationRequest request) {
        requireRole(projectId, ProjectRole.OWNER);
        PublicationResponse publication = publicationService.create(projectId, request);
        return Response.created(URI.create("/api/projects/" + projectId + "/publications/" + publication.id()))
                .entity(publication).build();
    }

    @GET
    @Path("/{publicationId}")
    @Operation(summary = "Get a project publication", description = "Requires VIEWER membership in the project.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    @APIResponse(responseCode = "404", description = "Publication not found in the project")
    public PublicationResponse get(@PathParam("projectId") Long projectId, @PathParam("publicationId") Long publicationId) {
        requireRole(projectId, ProjectRole.VIEWER);
        return publicationService.get(projectId, publicationId);
    }

    @PUT
    @Path("/{publicationId}")
    @Operation(summary = "Replace a project publication", description = "Requires OWNER membership; replaces the publication record.")
    @APIResponse(responseCode = "403", description = "Project OWNER membership required")
    @APIResponse(responseCode = "404", description = "Publication not found in the project")
    public PublicationResponse replace(@PathParam("projectId") Long projectId, @PathParam("publicationId") Long publicationId,
                                       @NotNull @Valid PublicationRequest request) {
        requireRole(projectId, ProjectRole.OWNER);
        return publicationService.replace(projectId, publicationId, request);
    }

    @DELETE
    @Path("/{publicationId}")
    @Operation(summary = "Delete a project publication", description = "Requires OWNER membership. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Publication deleted")
    @APIResponse(responseCode = "403", description = "Project OWNER membership required")
    @APIResponse(responseCode = "404", description = "Publication not found in the project")
    public Response delete(@PathParam("projectId") Long projectId, @PathParam("publicationId") Long publicationId) {
        requireRole(projectId, ProjectRole.OWNER);
        publicationService.delete(projectId, publicationId);
        return Response.noContent().build();
    }

    private void requireRole(Long projectId, ProjectRole role) {
        if (!projectRoleCheck.isAtLeast(projectId, role)) throw new ForbiddenException();
    }
}