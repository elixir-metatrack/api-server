package no.metatrack.server.sample.metadata;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;

import java.util.List;
import java.util.UUID;

@Path("/api/projects/{projectId}/sample-metadata-fields")
@Authenticated
public class SampleMetadataFieldController {
    @Inject
    SampleMetadataFieldService fieldService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    public List<SampleMetadataFieldResponse> list(
            @PathParam("projectId") Long projectId,
            @QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived) {
        requireRole(projectId, includeArchived ? ProjectRole.ADMIN : ProjectRole.VIEWER);
        return fieldService.list(projectId, includeArchived).stream()
                .map(SampleMetadataFieldResponse::fromEntity)
                .toList();
    }

    @POST
    public Response create(
            @PathParam("projectId") Long projectId, @Valid CreateSampleMetadataFieldRequest request) {
        requireRole(projectId, ProjectRole.ADMIN);
        SampleMetadataField field = fieldService.create(projectId, request);
        return Response.status(Response.Status.CREATED).entity(SampleMetadataFieldResponse.fromEntity(field)).build();
    }

    @PATCH
    @Path("/{fieldId}")
    public SampleMetadataFieldResponse patch(
            @PathParam("projectId") Long projectId,
            @PathParam("fieldId") UUID fieldId,
            @Valid PatchSampleMetadataFieldRequest request) {
        requireRole(projectId, ProjectRole.ADMIN);
        return SampleMetadataFieldResponse.fromEntity(fieldService.patch(projectId, fieldId, request));
    }

    @DELETE
    @Path("/{fieldId}")
    public Response archive(@PathParam("projectId") Long projectId, @PathParam("fieldId") UUID fieldId) {
        requireRole(projectId, ProjectRole.ADMIN);
        fieldService.archive(projectId, fieldId);
        return Response.noContent().build();
    }

    private void requireRole(Long projectId, ProjectRole role) {
        if (!projectRoleCheck.isAtLeast(projectId, role)) throw new ForbiddenException();
    }
}