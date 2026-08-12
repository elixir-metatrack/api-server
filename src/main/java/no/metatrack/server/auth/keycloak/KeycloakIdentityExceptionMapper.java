package no.metatrack.server.auth.keycloak;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class KeycloakIdentityExceptionMapper implements ExceptionMapper<KeycloakIdentityException> {
    @Override
    public Response toResponse(KeycloakIdentityException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "identity_provider_unavailable"))
                .build();
    }
}