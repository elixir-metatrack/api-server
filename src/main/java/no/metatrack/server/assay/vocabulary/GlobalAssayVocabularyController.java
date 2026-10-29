package no.metatrack.server.assay.vocabulary;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/assay-vocabularies")
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Assay vocabularies", description = "Global assay field vocabularies and system-admin management")
public class GlobalAssayVocabularyController {
    @Inject
    GlobalAssayVocabularyManagementService vocabularyService;

    @GET
    @Operation(summary = "List global assay vocabularies", description = "Requires authentication; returns the vocabulary definitions used to validate assay fields.")
    public List<AssayVocabularyResponse> list() {
        return vocabularyService.list();
    }

    @GET
    @Path("/{fieldKey}")
    @Operation(summary = "Get a global assay vocabulary", description = "Requires authentication.")
    @APIResponse(responseCode = "404", description = "Vocabulary field key not found")
    public AssayVocabularyResponse get(@PathParam("fieldKey") String fieldKey) {
        return vocabularyService.get(fieldKey);
    }

    @PUT
    @Path("/{fieldKey}")
    @RolesAllowed("system-admin")
    @Operation(summary = "Replace a global assay vocabulary", description = "Requires the `system-admin` role. Replaces the allowed values for the specified field.")
    @APIResponse(responseCode = "404", description = "Vocabulary field key not found")
    public AssayVocabularyResponse replace(
            @PathParam("fieldKey") String fieldKey,
            @Valid PutAssayVocabularyRequest request) {
        return vocabularyService.replace(fieldKey, request);
    }

    @DELETE
    @Path("/{fieldKey}")
    @RolesAllowed("system-admin")
    @Operation(summary = "Delete a global assay vocabulary", description = "Requires the `system-admin` role. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Vocabulary deleted")
    @APIResponse(responseCode = "404", description = "Vocabulary field key not found")
    public Response delete(@PathParam("fieldKey") String fieldKey) {
        vocabularyService.delete(fieldKey);
        return Response.noContent().build();
    }
}