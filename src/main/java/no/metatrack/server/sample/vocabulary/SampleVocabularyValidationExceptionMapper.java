package no.metatrack.server.sample.vocabulary;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class SampleVocabularyValidationExceptionMapper implements ExceptionMapper<SampleVocabularyValidationException> {
    @Override
    public Response toResponse(SampleVocabularyValidationException exception) {
        return Response.status(Response.Status.BAD_REQUEST).entity(exception.violations()).build();
    }
}