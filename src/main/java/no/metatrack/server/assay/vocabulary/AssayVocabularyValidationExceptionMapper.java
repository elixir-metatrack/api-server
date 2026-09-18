package no.metatrack.server.assay.vocabulary;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AssayVocabularyValidationExceptionMapper implements ExceptionMapper<AssayVocabularyValidationException> {
    @Override
    public Response toResponse(AssayVocabularyValidationException exception) {
        return Response.status(Response.Status.BAD_REQUEST).entity(exception.violations()).build();
    }
}