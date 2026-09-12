package no.metatrack.server.auth.keycloak;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IdentityLookupServiceTest {
    private static final String REALM = "metatrack";

    @Test
    void resolvesUsername() {
        UUID userId = UUID.randomUUID();
        IdentityLookupService service = serviceReturning(
                RestResponse.ok(new KeycloakUserRepresentation(userId.toString(), "user@example.org", null, null))
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
        KeycloakAdminClient client = clientUsing((realm, userId) -> {
            calls.incrementAndGet();
            return RestResponse.ok(new KeycloakUserRepresentation(userId, userId + "@example.org", null, null));
        });
        IdentityLookupService service = new IdentityLookupService(client, REALM);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        Map<UUID, Optional<String>> result = service.usernames(List.of(first, second, first));

        assertEquals(2, result.size());
        assertEquals(2, calls.get());
    }

    @Test
    void upstreamFailuresIncludeSafeStatusContext() {
        for (int status : List.of(401, 403, 500, 503)) {
            IdentityLookupService service = serviceReturning(RestResponse.status(status));

            KeycloakIdentityException exception = assertThrows(
                    KeycloakIdentityException.class,
                    () -> service.username(UUID.randomUUID())
            );
            assertEquals("Keycloak user lookup failed (category=upstream_http, status=" + status + ")", exception.getMessage());
        }
    }

    @Test
    void transportFailureIsTranslated() {
        IdentityLookupService service = new IdentityLookupService(clientUsing((realm, userId) -> {
            throw new IllegalStateException("unavailable");
        }), REALM);

        KeycloakIdentityException exception = assertThrows(
                KeycloakIdentityException.class,
                () -> service.username(UUID.randomUUID())
        );

        assertEquals("Keycloak user lookup failed (category=transport)", exception.getMessage());
    }

    @Test
    void tokenClientFailureIsTranslatedWithSafeCategory() {
        IdentityLookupService service = new IdentityLookupService(clientUsing((realm, userId) -> {
            throw new TokenClientFailure("secret-token-must-not-be-logged");
        }), REALM);

        KeycloakIdentityException exception = assertThrows(
                KeycloakIdentityException.class,
                () -> service.username(UUID.randomUUID())
        );

        assertEquals("Keycloak user lookup failed (category=token_client)", exception.getMessage());
    }

    @Test
    void networkFailureIsTranslatedWithSafeCategory() {
        IdentityLookupService service = new IdentityLookupService(clientUsing((realm, userId) -> {
            throw new RuntimeException(new ConnectException("sensitive-hostname"));
        }), REALM);

        KeycloakIdentityException exception = assertThrows(
                KeycloakIdentityException.class,
                () -> service.username(UUID.randomUUID())
        );

        assertEquals("Keycloak user lookup failed (category=transport)", exception.getMessage());
    }

    private IdentityLookupService serviceReturning(RestResponse<KeycloakUserRepresentation> response) {
        return new IdentityLookupService(clientUsing((realm, userId) -> response), REALM);
    }

    private KeycloakAdminClient clientUsing(BiFunction<String, String, RestResponse<KeycloakUserRepresentation>> lookup) {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        when(client.getUser(anyString(), anyString())).thenAnswer(invocation ->
                lookup.apply(invocation.getArgument(0), invocation.getArgument(1)));
        return client;
    }

    @Test
    void emailLookupIsExactNormalizedAndNeverCachesMissingAccounts() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        var user = new KeycloakUserRepresentation(UUID.randomUUID().toString(), "invitee", "Invitee+Tag@Example.org", false);
        when(client.searchByEmail(REALM, "invitee+tag@example.org", true))
                .thenReturn(RestResponse.ok(List.of()), RestResponse.ok(List.of(user)));
        var service = new IdentityLookupService(client, REALM);

        assertEquals(Optional.empty(), service.findByEmail(" Invitee+Tag@Example.org "));
        assertEquals(Optional.of(user), service.findByEmail("Invitee+Tag@Example.org"));
        verify(client, times(2)).searchByEmail(REALM, "invitee+tag@example.org", true);
    }

    @Test
    void emailLookupRejectsAmbiguousUnexpectedAndMalformedResults() {
        var user = new KeycloakUserRepresentation(UUID.randomUUID().toString(), "user", "user@example.org", true);
        for (List<KeycloakUserRepresentation> users : List.of(
                List.of(user, user),
                List.of(new KeycloakUserRepresentation(user.id(), "user", "other@example.org", true)),
                List.of(new KeycloakUserRepresentation("invalid", "user", user.email(), true)),
                List.of(new KeycloakUserRepresentation(user.id(), "user", null, true)))) {
            KeycloakAdminClient client = mock(KeycloakAdminClient.class);
            when(client.searchByEmail(REALM, "user@example.org", true)).thenReturn(RestResponse.ok(users));
            assertThrows(KeycloakIdentityException.class,
                    () -> new IdentityLookupService(client, REALM).findByEmail("user@example.org"));
        }
    }

    @Test
    void emailLookupDoesNotTreatHttpFailuresOrEmptyBodiesAsMissingUsers() {
        for (int status : List.of(401, 403, 404, 429, 500, 503)) {
            KeycloakAdminClient client = mock(KeycloakAdminClient.class);
            when(client.searchByEmail(REALM, "user@example.org", true)).thenReturn(RestResponse.status(status));
            var exception = assertThrows(KeycloakIdentityException.class,
                    () -> new IdentityLookupService(client, REALM).findByEmail("user@example.org"));
            assertEquals("Keycloak email lookup failed (category=upstream_http, status=" + status + ")", exception.getMessage());
        }
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        when(client.searchByEmail(REALM, "user@example.org", true)).thenReturn(RestResponse.ok());
        assertThrows(KeycloakIdentityException.class,
                () -> new IdentityLookupService(client, REALM).findByEmail("user@example.org"));
    }

    @Test
    void emailLookupTranslatesTransportAndTokenFailuresSafely() {
        for (RuntimeException failure : List.of(new RuntimeException("sensitive"), new TokenClientFailure("secret"))) {
            KeycloakAdminClient client = mock(KeycloakAdminClient.class);
            when(client.searchByEmail(REALM, "user@example.org", true)).thenThrow(failure);
            var exception = assertThrows(KeycloakIdentityException.class,
                    () -> new IdentityLookupService(client, REALM).findByEmail("user@example.org"));
            assertEquals("Keycloak email lookup failed (category="
                    + (failure instanceof TokenClientFailure ? "token_client" : "transport") + ")", exception.getMessage());
        }
    }

    private static final class TokenClientFailure extends RuntimeException {
        private TokenClientFailure(String message) {
            super(message);
        }
    }
}