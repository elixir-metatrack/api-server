package no.metatrack.server.auth;

import no.metatrack.server.auth.keycloak.KeycloakAdminClient;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.auth.keycloak.KeycloakProfileRepresentation;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UserSearchServiceTest {
    private static final String REALM = "metatrack";

    @Test
    void trimsQueryAndMapsProfileAndDisplayName() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        String id = UUID.randomUUID().toString();
        var profile = new KeycloakProfileRepresentation(
                id, "ada@example.org", "ada@example.org", " Ada ", "Lovelace", null);
        when(client.searchUsers(REALM, "Ada Lovelace", false, 0, 21)).thenReturn(RestResponse.ok(List.of(profile)));

        UserSearchResponse response = service(client).search("  Ada Lovelace  ");

        assertEquals(List.of(new UserSearchResult(id, " Ada ", "Lovelace", "Ada Lovelace", "ada@example.org")),
                response.users());
        assertFalse(response.hasMore());
        verify(client).searchUsers(REALM, "Ada Lovelace", false, 0, 21);
    }

    @Test
    void appliesResultLimitAndHasMoreForZeroTwentyAndTwentyOneMatches() {
        for (int count : List.of(0, 20, 21)) {
            KeycloakAdminClient client = mock(KeycloakAdminClient.class);
            List<KeycloakProfileRepresentation> profiles = profiles(count);
            when(client.searchUsers(anyString(), anyString(), anyBoolean(), anyInt(), anyInt()))
                    .thenReturn(RestResponse.ok(profiles));

            UserSearchResponse response = service(client).search("ad");

            assertEquals(Math.min(count, 20), response.users().size());
            assertEquals(count > 20, response.hasMore());
            verify(client).searchUsers(REALM, "ad", false, 0, 21);
        }
    }

    @Test
    void fallsBackFromNamesToEmailUsernameAndId() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        String emailId = UUID.randomUUID().toString();
        String usernameId = UUID.randomUUID().toString();
        String fallbackId = UUID.randomUUID().toString();
        when(client.searchUsers(REALM, "ada", false, 0, 21)).thenReturn(RestResponse.ok(List.of(
                profile(emailId, "account", "ada@example.org", null, "  "),
                profile(usernameId, "account-name", null, null, null),
                profile(fallbackId, null, null, null, null)
        )));

        UserSearchResponse response = service(client).search("ada");

        assertEquals(List.of("ada@example.org", "account-name", fallbackId),
                response.users().stream().map(UserSearchResult::displayName).toList());
        assertNull(response.users().getFirst().firstName());
        assertEquals("  ", response.users().getFirst().lastName());
    }

    @Test
    void invalidQueriesDoNotCallKeycloak() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        UserSearchService service = service(client);

        for (String query : new String[]{null, "", " ", "a", "*", "ada*", "\"ada\"", "x".repeat(257)}) {
            assertThrows(jakarta.ws.rs.BadRequestException.class, () -> service.search(query));
        }

        verifyNoInteractions(client);
    }

    @Test
    void rejectsMalformedIdentitiesAndEmptyUpstreamBodies() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        when(client.searchUsers(REALM, "ada", false, 0, 21)).thenReturn(RestResponse.ok(List.of(
                profile("not-a-uuid", "ada", "ada@example.org", "Ada", null))));
        assertThrows(KeycloakIdentityException.class, () -> service(client).search("ada"));

        when(client.searchUsers(REALM, "ada", false, 0, 21)).thenReturn(RestResponse.ok());
        assertThrows(KeycloakIdentityException.class, () -> service(client).search("ada"));
    }

    @Test
    void translatesUpstreamAndTransportFailuresWithoutExposingDetails() {
        KeycloakAdminClient client = mock(KeycloakAdminClient.class);
        when(client.searchUsers(REALM, "ada", false, 0, 21)).thenReturn(RestResponse.status(403));
        KeycloakIdentityException httpFailure = assertThrows(
                KeycloakIdentityException.class, () -> service(client).search("ada"));
        assertEquals("Keycloak user search failed (category=upstream_http, status=403)", httpFailure.getMessage());

        doThrow(new IllegalStateException("private host")).when(client)
                .searchUsers(REALM, "ada", false, 0, 21);
        KeycloakIdentityException transportFailure = assertThrows(
                KeycloakIdentityException.class, () -> service(client).search("ada"));
        assertEquals("Keycloak user search failed (category=transport)", transportFailure.getMessage());

        doThrow(new TokenClientFailure("secret token")).when(client)
                .searchUsers(REALM, "ada", false, 0, 21);
        KeycloakIdentityException tokenFailure = assertThrows(
                KeycloakIdentityException.class, () -> service(client).search("ada"));
        assertEquals("Keycloak user search failed (category=token_client)", tokenFailure.getMessage());
        assertFalse(tokenFailure.getMessage().contains("secret token"));
    }

    private UserSearchService service(KeycloakAdminClient client) {
        return new UserSearchService(new UserSearchCache(client, REALM));
    }

    private List<KeycloakProfileRepresentation> profiles(int count) {
        List<KeycloakProfileRepresentation> profiles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            profiles.add(profile(UUID.randomUUID().toString(), "user" + index,
                    "user" + index + "@example.org", "User", Integer.toString(index)));
        }
        return profiles;
    }

    private KeycloakProfileRepresentation profile(
            String id, String username, String email, String firstName, String lastName) {
        return new KeycloakProfileRepresentation(id, username, email, firstName, lastName, null);
    }

    private static final class TokenClientFailure extends RuntimeException {
        private TokenClientFailure(String message) {
            super(message);
        }
    }
}