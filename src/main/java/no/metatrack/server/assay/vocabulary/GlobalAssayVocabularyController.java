package no.metatrack.server.assay.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/assay-vocabularies")
@Authenticated
public class GlobalAssayVocabularyController {
    @Inject
    GlobalAssayVocabularyManagementService vocabularyService;

    @GET
    public List<AssayVocabularyResponse> list() {
        return vocabularyService.list();
    }

    @GET
    @Path("/{fieldKey}")
    public AssayVocabularyResponse get(@PathParam("fieldKey") String fieldKey) {
        return vocabularyService.get(fieldKey);
    }

    @PUT
    @Path("/{fieldKey}")
    @RolesAllowed("system-admin")
    public AssayVocabularyResponse replace(
            @PathParam("fieldKey") String fieldKey,
            @Valid PutAssayVocabularyRequest request) {
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