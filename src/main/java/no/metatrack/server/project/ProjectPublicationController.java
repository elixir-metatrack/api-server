package no.metatrack.server.project;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.List;

@Path("/api/projects/{projectId}/publications")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectPublicationController {
    @Inject
    ProjectPublicationService publicationService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    public List<PublicationResponse> list(@PathParam("projectId") Long projectId) {
        requireRole(projectId, ProjectRole.VIEWER);
        return publicationService.list(projectId);
    }

    @POST
    public Response create(@PathParam("projectId") Long projectId, @NotNull @Valid PublicationRequest request) {
        requireRole(projectId, ProjectRole.OWNER);
        PublicationResponse publication = publicationService.create(projectId, request);
        return Response.created(URI.create("/api/projects/" + projectId + "/publications/" + publication.id()))
                .entity(publication).build();
    }

    @GET
    @Path("/{publicationId}")
    public PublicationResponse get(@PathParam("projectId") Long projectId, @PathParam("publicationId") Long publicationId) {
        requireRole(projectId, ProjectRole.VIEWER);
        return publicationService.get(projectId, publicationId);
    }

    @PUT
    @Path("/{publicationId}")
    public PublicationResponse replace(@PathParam("projectId") Long projectId, @PathParam("publicationId") Long publicationId,
                                       @NotNull @Valid PublicationRequest request) {
        requireRole(projectId, ProjectRole.OWNER);
        return publicationService.replace(projectId, publicationId, request);
    }

    @DELETE
    @Path("/{publicationId}")
    public Response delete(@PathParam("projectId") Long projectId, @PathParam("publicationId") Long publicationId) {
        requireRole(projectId, ProjectRole.OWNER);
        publicationService.delete(projectId, publicationId);
        return Response.noContent().build();
    }

    private void requireRole(Long projectId, ProjectRole role) {
        if (!projectRoleCheck.isAtLeast(projectId, role)) throw new ForbiddenException();
    }
}