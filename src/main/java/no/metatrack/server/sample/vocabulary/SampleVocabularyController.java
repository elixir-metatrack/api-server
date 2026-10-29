package no.metatrack.server.sample.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/projects/{projectId}/sample-vocabularies")
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Sample vocabularies", description = "Project-level sample field vocabulary rules")
public class SampleVocabularyController {
    @Inject
    SampleVocabularyManagementService vocabularyService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    @Operation(summary = "List project sample vocabularies", description = "Requires VIEWER membership in the project.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    public List<SampleVocabularyResponse> list(@PathParam("projectId") Long projectId) {
        requireRole(projectId, ProjectRole.VIEWER);
        return vocabularyService.list(projectId);
    }

    @GET
    @Path("/{fieldKey}")
    @Operation(summary = "Get a project sample vocabulary", description = "Requires VIEWER membership in the project.")
    @APIResponse(responseCode = "403", description = "Project VIEWER membership required")
    @APIResponse(responseCode = "404", description = "Vocabulary field key not found")
    public SampleVocabularyResponse get(
            @PathParam("projectId") Long projectId, @PathParam("fieldKey") String fieldKey) {
        requireRole(projectId, ProjectRole.VIEWER);
        return vocabularyService.get(projectId, fieldKey);
    }

    @PUT
    @Path("/{fieldKey}")
    @Operation(summary = "Replace a project sample vocabulary", description = "Requires ADMIN membership in the project.")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    @APIResponse(responseCode = "404", description = "Vocabulary field key not found")
    public SampleVocabularyResponse replace(
            @PathParam("projectId") Long projectId,
            @PathParam("fieldKey") String fieldKey,
            @Valid PutSampleVocabularyRequest request) {
        requireRole(projectId, ProjectRole.ADMIN);
        return vocabularyService.replace(projectId, fieldKey, request);
    }

    @DELETE
    @Path("/{fieldKey}")
    @Operation(summary = "Delete a project sample vocabulary", description = "Requires ADMIN membership in the project. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Vocabulary deleted")
    @APIResponse(responseCode = "403", description = "Project ADMIN membership required")
    @APIResponse(responseCode = "404", description = "Vocabulary field key not found")
    public Response delete(@PathParam("projectId") Long projectId, @PathParam("fieldKey") String fieldKey) {
        requireRole(projectId, ProjectRole.ADMIN);
        vocabularyService.delete(projectId, fieldKey);
        return Response.noContent().build();
    }

    private void requireRole(Long projectId, ProjectRole role) {
        if (!projectRoleCheck.isAtLeast(projectId, role)) throw new ForbiddenException();
    }
}