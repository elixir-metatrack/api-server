package no.metatrack.server.sample.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/sample-vocabularies")
@Authenticated
public class GlobalSampleVocabularyController {
    @Inject
    GlobalSampleVocabularyManagementService vocabularyService;

    @GET
    public List<SampleVocabularyResponse> list() {
        return vocabularyService.list();
    }

    @GET
    @Path("/{fieldKey}")
    public SampleVocabularyResponse get(@PathParam("fieldKey") String fieldKey) {
        return vocabularyService.get(fieldKey);
    }

    @PUT
    @Path("/{fieldKey}")
    @RolesAllowed("system-admin")
    public SampleVocabularyResponse replace(
            @PathParam("fieldKey") String fieldKey,
            @Valid PutSampleVocabularyRequest request) {
        return vocabularyService.replace(fieldKey, request);
    }

    @DELETE
    @Path("/{fieldKey}")
    @RolesAllowed("system-admin")
    public Response delete(@PathParam("fieldKey") String fieldKey) {
        vocabularyService.delete(fieldKey);
        return Response.noContent().build();
    }
}