package no.metatrack.server.auth.keycloak;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakIdentityExceptionMapperTest {
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