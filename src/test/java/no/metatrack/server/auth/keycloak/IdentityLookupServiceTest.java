package no.metatrack.server.auth.keycloak;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentityLookupServiceTest {
    private static final String REALM = "metatrack";

    @Test
    void resolvesUsername() {
        UUID userId = UUID.randomUUID();
        IdentityLookupService service = serviceReturning(
                RestResponse.ok(new KeycloakUserRepresentation(userId.toString(), "user@example.org"))
        );

        assertEquals(Optional.of("user@example.org"), service.username(userId));
    }

    @Test
    void missingUserIsUnresolved() {
        IdentityLookupService service = serviceReturning(RestResponse.status(404));

        assertEquals(Optional.empty(), service.username(UUID.randomUUID()));
    }

    @Test
    void resolvesEachDistinctUserOncePerBatch() {
        AtomicInteger calls = new AtomicInteger();
        KeycloakAdminClient client = (realm, userId) -> {
            calls.incrementAndGet();
            return RestResponse.ok(new KeycloakUserRepresentation(userId, userId + "@example.org"));
        };
        IdentityLookupService service = new IdentityLookupService(client, REALM);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        Map<UUID, Optional<String>> result = service.usernames(List.of(first, second, first));

        assertEquals(2, result.size());
        assertEquals(2, calls.get());
    }

    @Test
    void upstreamFailureIsNotTreatedAsMissingUser() {
        IdentityLookupService service = serviceReturning(RestResponse.status(401));

        assertThrows(KeycloakIdentityException.class, () -> service.username(UUID.randomUUID()));
    }

    @Test
    void transportFailureIsTranslated() {
        IdentityLookupService service = new IdentityLookupService((realm, userId) -> {
            throw new IllegalStateException("unavailable");
        }, REALM);

        assertThrows(KeycloakIdentityException.class, () -> service.username(UUID.randomUUID()));
    }

    private IdentityLookupService serviceReturning(RestResponse<KeycloakUserRepresentation> response) {
        return new IdentityLookupService((realm, userId) -> response, REALM);
    }
}