package no.metatrack.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.auth.keycloak.ProfileClientTestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(ProfileClientTestProfile.class)
class UserSearchControllerTest {
    @TestHTTPResource
    URI baseUri;

    @Inject
    ObjectMapper mapper;

    private UserSearchService userSearchService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void installServiceMock() {
        userSearchService = mock(UserSearchService.class);
        QuarkusMock.installMockForType(userSearchService, UserSearchService.class);
    }

    @Test
    void anonymousCallerIsRejectedBeforeSearchService() throws Exception {
        HttpResponse<String> response = get("/api/users/search?q=ada");

        assertEquals(401, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        verifyNoInteractions(userSearchService);
    }

    @Test
    @TestSecurity(user = "picker")
    void returnsTypedResultsAndPreventsDownstreamCaching() throws Exception {
        String id = UUID.randomUUID().toString();
        when(userSearchService.search("ada")).thenReturn(new UserSearchResponse(
                List.of(new UserSearchResult(id, "Ada", "Lovelace", "Ada Lovelace", "ada@example.org")), true));

        HttpResponse<String> response = get("/api/users/search?q=ada");

        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        assertEquals(mapper.readTree("{\"users\":[{\"userId\":\"" + id
                        + "\",\"firstName\":\"Ada\",\"lastName\":\"Lovelace\","
                        + "\"displayName\":\"Ada Lovelace\",\"email\":\"ada@example.org\"}],\"hasMore\":true}"),
                mapper.readTree(response.body()));
        verify(userSearchService).search("ada");
    }

    @Test
    @TestSecurity(user = "picker")
    void returnsEmptySearchResults() throws Exception {
        when(userSearchService.search("none")).thenReturn(new UserSearchResponse(List.of(), false));

        HttpResponse<String> response = get("/api/users/search?q=none");

        assertEquals(200, response.statusCode());
        assertEquals(mapper.readTree("{\"users\":[],\"hasMore\":false}"), mapper.readTree(response.body()));
    }

    @Test
    @TestSecurity(user = "picker")
    void mapsInvalidQueriesAndKeycloakFailuresWithoutLeakingDetails() throws Exception {
        when(userSearchService.search("a")).thenThrow(new BadRequestException("invalid query"));
        HttpResponse<String> invalid = get("/api/users/search?q=a");
        assertEquals(400, invalid.statusCode());
        assertEquals("no-store", invalid.headers().firstValue("Cache-Control").orElseThrow());

        when(userSearchService.search("secret-query")).thenThrow(
                new KeycloakIdentityException("Keycloak unavailable", new RuntimeException("sensitive response")));
        HttpResponse<String> upstreamFailure = get("/api/users/search?q=secret-query");
        assertEquals(502, upstreamFailure.statusCode());
        assertEquals("no-store", upstreamFailure.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("{\"error\":\"identity_provider_unavailable\"}", upstreamFailure.body());
        assertFalse(upstreamFailure.body().contains("sensitive response"));
    }

    @Test
    void publishesAuthenticatedTypedOpenApiContract() throws Exception {
        HttpResponse<String> response = get("/openapi?format=json");

        assertEquals(200, response.statusCode());
        var operation = mapper.readTree(response.body()).path("paths").path("/api/users/search").path("get");
        assertTrue(operation.has("security"));
        var responses = operation.path("responses");
        for (String code : new String[]{"200", "400", "401", "502"}) {
            assertTrue(responses.has(code), code);
        }
        assertTrue(responses.path("200").path("content").path("application/json")
                .path("schema").path("$ref").asText().endsWith("UserSearchResponse"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(baseUri.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}