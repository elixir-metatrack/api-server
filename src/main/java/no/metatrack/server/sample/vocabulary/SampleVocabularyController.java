package no.metatrack.server.sample.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;

import java.util.List;

@Path("/api/projects/{projectId}/sample-vocabularies")
@Authenticated
public class SampleVocabularyController {
    @Inject
    SampleVocabularyManagementService vocabularyService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @GET
    public List<SampleVocabularyResponse> list(@PathParam("projectId") Long projectId) {
        requireRole(projectId, ProjectRole.VIEWER);
        return vocabularyService.list(projectId);
    }

    @GET
    @Path("/{fieldKey}")
    public SampleVocabularyResponse get(
            @PathParam("projectId") Long projectId, @PathParam("fieldKey") String fieldKey) {
        requireRole(projectId, ProjectRole.VIEWER);
        return vocabularyService.get(projectId, fieldKey);
    }

    @PUT
    @Path("/{fieldKey}")
    public SampleVocabularyResponse replace(
            @PathParam("projectId") Long projectId,
            @PathParam("fieldKey") String fieldKey,
            @Valid PutSampleVocabularyRequest request) {
        requireRole(projectId, ProjectRole.ADMIN);
        return vocabularyService.replace(projectId, fieldKey, request);
    }

    @DELETE
    @Path("/{fieldKey}")
    public Response delete(@PathParam("projectId") Long projectId, @PathParam("fieldKey") String fieldKey) {
        requireRole(projectId, ProjectRole.ADMIN);
        vocabularyService.delete(projectId, fieldKey);
        return Response.noContent().build();
    }

    private void requireRole(Long projectId, ProjectRole role) {
        if (!projectRoleCheck.isAtLeast(projectId, role)) throw new ForbiddenException();
    }
}