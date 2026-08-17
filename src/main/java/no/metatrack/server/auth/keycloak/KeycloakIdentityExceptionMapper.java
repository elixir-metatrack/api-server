package no.metatrack.server.auth.keycloak;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.StringJoiner;

@Provider
public class KeycloakIdentityExceptionMapper implements ExceptionMapper<KeycloakIdentityException> {
    private static final Logger LOG = Logger.getLogger(KeycloakIdentityExceptionMapper.class);
    private static final int MAX_CAUSE_DEPTH = 8;

    @Override
    public Response toResponse(KeycloakIdentityException exception) {
        LOG.errorf(
                "Identity provider unavailable: %s, cause_types=%s",
                exception.getMessage(),
                safeCauseTypes(exception.getCause())
        );
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "identity_provider_unavailable"))
                .build();
    }

    static String safeCauseTypes(Throwable failure) {
        if (failure == null) {
            return "none";
        }

        StringJoiner causeTypes = new StringJoiner(" -> ");
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            causeTypes.add(current.getClass().getName());
            current = current.getCause();
            depth++;
        }
        if (current != null) {
            causeTypes.add("truncated");
        }
        return causeTypes.toString();
    }
}