package no.metatrack.server.sample.metadata;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.UUID;

@Path("/api/projects/{projectId}/sample-metadata-fields")
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Sample metadata fields", description = "Project-defined custom sample attributes")
public class SampleMetadataFieldController {
    @Inject
    SampleMetadataFieldService fieldService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    @Operation(summary = "List sample metadata fields", description = "Requires VIEWER membership when listing active fields; setting `includeArchived=true` requires ADMIN membership.")
    @APIResponse(responseCode = "403", description = "Insufficient project role for the requested field visibility")
    public List<SampleMetadataFieldResponse> list(
            @PathParam("projectId") Long projectId,
            @Parameter(description = "Include archived field definitions; requires ADMIN membership", example = "false")
            @QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived) {
        requireRole(projectId, includeArchived ? ProjectRole.ADMIN : ProjectRole.VIEWER);
        return fieldService.list(projectId, includeArchived).stream()
                .map(SampleMetadataFieldResponse::fromEntity)
                .toList();
    }

    @POST
    @Operation(summary = "Create a sample metadata field", description = "Requires ADMIN membership in the project. A field definition determines the type of custom metadata accepted for samples.")
    @APIResponse(responseCode = "201", description = "Metadata field created")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    public Response create(
            @PathParam("projectId") Long projectId,
            @RequestBody(description = "Custom metadata-field definition", content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = CreateSampleMetadataFieldRequest.class),
                    examples = @ExampleObject(value = "{\"key\":\"host_origin\",\"label\":\"Host origin\",\"type\":\"TEXT\",\"tooltip\":\"Where the host was sampled\"}")))
            @Valid CreateSampleMetadataFieldRequest request) {
        requireRole(projectId, ProjectRole.ADMIN);
        SampleMetadataField field = fieldService.create(projectId, request);
        return Response.status(Response.Status.CREATED).entity(SampleMetadataFieldResponse.fromEntity(field)).build();
    }

    @PATCH
    @Path("/{fieldId}")
    @Operation(summary = "Update or restore a sample metadata field", description = "Requires ADMIN membership. Non-null fields are applied; setting `archived` to false restores an archived field.")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    @APIResponse(responseCode = "404", description = "Metadata field not found in the project")
    public SampleMetadataFieldResponse patch(
            @PathParam("projectId") Long projectId,
            @PathParam("fieldId") UUID fieldId,
            @Valid PatchSampleMetadataFieldRequest request) {
        requireRole(projectId, ProjectRole.ADMIN);
        return SampleMetadataFieldResponse.fromEntity(fieldService.patch(projectId, fieldId, request));
    }

    @DELETE
    @Path("/{fieldId}")
    @Operation(summary = "Archive a sample metadata field", description = "Requires ADMIN membership. Existing sample values are retained; returns no response body.")
    @APIResponse(responseCode = "204", description = "Metadata field archived")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    @APIResponse(responseCode = "404", description = "Metadata field not found in the project")
    public Response archive(@PathParam("projectId") Long projectId, @PathParam("fieldId") UUID fieldId) {
        requireRole(projectId, ProjectRole.ADMIN);
        fieldService.archive(projectId, fieldId);
        return Response.noContent().build();
    }

    private void requireRole(Long projectId, ProjectRole role) {
        if (!projectRoleCheck.isAtLeast(projectId, role)) throw new ForbiddenException();
    }
}