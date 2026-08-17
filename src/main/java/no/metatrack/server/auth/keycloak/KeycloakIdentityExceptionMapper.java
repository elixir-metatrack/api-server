package no.metatrack.server.auth.keycloak;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;

@Provider
public class KeycloakIdentityExceptionMapper implements ExceptionMapper<KeycloakIdentityException> {
    private static final Logger LOG = Logger.getLogger(KeycloakIdentityExceptionMapper.class);

    @Override
    public Response toResponse(KeycloakIdentityException exception) {
        LOG.errorf("Identity provider unavailable: %s", exception.getMessage());
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "identity_provider_unavailable"))
                .build();
    }
}