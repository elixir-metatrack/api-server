package no.metatrack.server.auth.keycloak;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakIdentityExceptionMapperTest {
    @Test
    void formatsCauseTypesWithoutSensitiveMessages() {
        RuntimeException failure = new RuntimeException(
                "https://secret-host.example/token?client_secret=secret",
                new ConnectException("secret-host.example")
        );

        String causeTypes = KeycloakIdentityExceptionMapper.safeCauseTypes(failure);

        assertEquals("java.lang.RuntimeException -> java.net.ConnectException", causeTypes);
    }

    @Test
    void formatsMissingCause() {
        assertEquals("none", KeycloakIdentityExceptionMapper.safeCauseTypes(null));
    }

    @Test
    void preservesSafeBadGatewayContract() {
        try (Response response = new KeycloakIdentityExceptionMapper().toResponse(
                new KeycloakIdentityException("internal diagnostic", new RuntimeException("secret"))
        )) {
            assertEquals(Response.Status.BAD_GATEWAY.getStatusCode(), response.getStatus());
            assertEquals(Map.of("error", "identity_provider_unavailable"), response.getEntity());
        }
    }
}