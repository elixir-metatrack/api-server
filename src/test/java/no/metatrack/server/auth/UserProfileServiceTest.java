package no.metatrack.server.auth;

import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.keycloak.*;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTest {
    final KeycloakAdminClient client = mock(KeycloakAdminClient.class);
    final UserProfileService service = new UserProfileService(client, "realm", "country", "institution", "orcid");
    final CurrentUser user = new CurrentUser("subject", "old", Set.of("viewer"), "old", "old", "old");

    KeycloakProfileRepresentation profile() {
        return new KeycloakProfileRepresentation("subject", "live", "user@example.org", "First", "Last",
                Map.of("unrelated", List.of("one", "two"), "country", List.of("SE")));
    }

    @Test
    void mergesOnlyAllowedFieldsAndReturnsPostWriteValues() {
        var saved = new KeycloakProfileRepresentation("subject", "live", "user@example.org", "ADA", "Last", Map.of("country", List.of("NO")));
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()), RestResponse.ok(saved));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(204));
        var result = service.updateProfile(user, new UserProfilePatch(Map.of("firstName", " Ada ", "country", "NO")));
        assertEquals("ADA", result.firstName());
        var payload = org.mockito.ArgumentCaptor.forClass(KeycloakProfileUpdate.class);
        verify(client).updateProfile(eq("realm"), eq("subject"), payload.capture());
        assertEquals(Map.of("username", "live", "email", "user@example.org", "firstName", "Ada", "lastName", "Last",
                "attributes", Map.of("unrelated", List.of("one", "two"), "country", List.of("NO"))), payload.getValue().fields());
        verify(client, times(2)).getProfile("realm", "subject");
    }

    @Test
    void clearsAllFieldsAndPreservesUnrelatedMultivaluedAttributes() {
        when(client.getProfile("realm", "subject")).thenAnswer(invocation -> RestResponse.ok(profile()));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(204));
        Map<String, String> clear = new LinkedHashMap<>();
        UserProfilePatch.ALLOWED.forEach(field -> clear.put(field, null));
        service.updateProfile(user, new UserProfilePatch(clear));
        var payload = org.mockito.ArgumentCaptor.forClass(KeycloakProfileUpdate.class);
        verify(client).updateProfile(eq("realm"), eq("subject"), payload.capture());
        assertEquals(Map.of("username", "live", "email", "user@example.org", "firstName", "", "lastName", "", "attributes", Map.of("country", List.of(),
                "institution", List.of(), "orcid", List.of(), "unrelated", List.of("one", "two"))), payload.getValue().fields());
    }

    @Test
    void emptyPatchDoesNotWriteAndInvalidPatchDoesNotRead() {
        assertThrows(WebApplicationException.class, () -> service.updateProfile(user, new UserProfilePatch(Map.of("country", "ZZ"))));
        verifyNoInteractions(client);
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()));
        service.updateProfile(user, new UserProfilePatch(Map.of()));
        verify(client).getProfile("realm", "subject");
        verifyNoMoreInteractions(client);
    }

    @Test
    void mapsWriteFailuresWithoutRetryingOrReadingAfterFailure() {
        for (int status : List.of(400, 404, 409, 401, 403, 500)) {
            reset(client);
            when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()));
            when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(status));
            if (status == 400 || status == 404 || status == 409) {
                assertEquals(status, assertThrows(WebApplicationException.class,
                        () -> service.updateProfile(user, new UserProfilePatch(Map.of("firstName", "Ada")))).getResponse().getStatus());
            } else {
                assertThrows(KeycloakIdentityException.class, () -> service.updateProfile(user, new UserProfilePatch(Map.of("firstName", "Ada"))));
            }
            verify(client, times(1)).getProfile("realm", "subject");
            verify(client, times(1)).updateProfile(eq("realm"), eq("subject"), any());
        }
    }

    @Test
    void includesSafeUpstreamHttpStatusForReturnedAndThrownFailures() {
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(403));
        var returnedFailure = assertThrows(KeycloakIdentityException.class,
                () -> service.updateProfile(user, new UserProfilePatch(Map.of("firstName", "Ada"))));
        assertTrue(returnedFailure.getMessage().contains("category=upstream_http, status=403"));

        reset(client);
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()));
        when(client.updateProfile(eq("realm"), eq("subject"), any()))
                .thenThrow(new WebApplicationException("private upstream message", 401));
        var thrownFailure = assertThrows(KeycloakIdentityException.class,
                () -> service.updateProfile(user, new UserProfilePatch(Map.of("firstName", "Ada"))));
        assertTrue(thrownFailure.getMessage().contains("category=upstream_http, status=401"));
        assertFalse(thrownFailure.getMessage().contains("private upstream message"));

        reset(client);
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.status(503));
        var readFailure = assertThrows(KeycloakIdentityException.class, () -> service.getProfile(user));
        assertTrue(readFailure.getMessage().contains("category=upstream_http, status=503"));

        reset(client);
        when(client.getProfile("realm", "subject"))
                .thenThrow(new WebApplicationException("private read message", 502));
        var thrownReadFailure = assertThrows(KeycloakIdentityException.class, () -> service.getProfile(user));
        assertTrue(thrownReadFailure.getMessage().contains("category=upstream_http, status=502"));
        assertFalse(thrownReadFailure.getMessage().contains("private read message"));
    }

    @Test
    void reportsUncertainTimeoutAndFailedPostWriteReadWithoutRetry() {
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenThrow(new jakarta.ws.rs.ProcessingException("timeout"));
        assertThrows(KeycloakIdentityException.class, () -> service.updateProfile(user, new UserProfilePatch(Map.of("firstName", "Ada"))));
        verify(client).updateProfile(eq("realm"), eq("subject"), any());
        reset(client);
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(profile()), RestResponse.status(500));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(204));
        assertThrows(KeycloakIdentityException.class, () -> service.updateProfile(user, new UserProfilePatch(Map.of("firstName", "Ada"))));
        verify(client).updateProfile(eq("realm"), eq("subject"), any());
    }

    @Test
    void readsLiveFieldsAndRetainsTokenRoles() {
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(new KeycloakProfileRepresentation(
                "subject", "live", "user@example.org", "Ada", "Lovelace", Map.of("country", List.of("NO")))));
        var result = service.getProfile(user);
        assertEquals("live", result.username());
        assertEquals("Ada", result.firstName());
        assertEquals("NO", result.country());
        assertEquals(user.roles(), result.roles());
        assertNull(result.institution());
    }

    @Test
    void mapsConfiguredRealmAttributeNamesForReadsAndWrites() {
        var mappedService = new UserProfileService(client, "realm", "Country", "Institution", "ORCID");
        var current = new KeycloakProfileRepresentation("subject", "live", "user@example.org", "First", "Last", Map.of(
                "Country", List.of("SE"), "Institution", List.of("University"),
                "ORCID", List.of("0000-0002-1825-0097"), "unrelated", List.of("one", "two")));
        var saved = new KeycloakProfileRepresentation("subject", "live", "user@example.org", "First", "Last", Map.of(
                "Country", List.of("NO"), "Institution", List.of("University"),
                "ORCID", List.of("0000-0002-1825-0097"), "unrelated", List.of("one", "two")));
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(current), RestResponse.ok(saved));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(204));

        var result = mappedService.updateProfile(user, new UserProfilePatch(Map.of("country", "NO")));

        assertEquals("NO", result.country());
        assertEquals("University", result.institution());
        assertEquals("0000-0002-1825-0097", result.orcid());
        var payload = org.mockito.ArgumentCaptor.forClass(KeycloakProfileUpdate.class);
        verify(client).updateProfile(eq("realm"), eq("subject"), payload.capture());
        assertEquals(Map.of("username", "live", "email", "user@example.org", "firstName", "First", "lastName", "Last",
                "attributes", Map.of("Country", List.of("NO"), "Institution", List.of("University"),
                "ORCID", List.of("0000-0002-1825-0097"), "unrelated", List.of("one", "two"))), payload.getValue().fields());
    }

    @Test
    void clearsConfiguredAttributeWhenItIsMissingAndSkipsMappedNoOpWrites() {
        var mappedService = new UserProfileService(client, "realm", "Country", "Institution", "ORCID");
        var withoutInstitution = new KeycloakProfileRepresentation("subject", "live", "user@example.org", "First", "Last",
                Map.of("Country", List.of("NO"), "unrelated", List.of("one", "two")));
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(withoutInstitution), RestResponse.ok(withoutInstitution));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(204));

        mappedService.updateProfile(user, new UserProfilePatch(Collections.singletonMap("institution", null)));

        var payload = org.mockito.ArgumentCaptor.forClass(KeycloakProfileUpdate.class);
        verify(client).updateProfile(eq("realm"), eq("subject"), payload.capture());
        assertEquals(Map.of("username", "live", "email", "user@example.org", "firstName", "First", "lastName", "Last",
                "attributes", Map.of("Country", List.of("NO"), "Institution", List.of(),
                "unrelated", List.of("one", "two"))), payload.getValue().fields());

        reset(client);
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(withoutInstitution));
        var unchanged = mappedService.updateProfile(user, new UserProfilePatch(Map.of("country", "NO")));
        assertEquals("NO", unchanged.country());
        verify(client).getProfile("realm", "subject");
        verifyNoMoreInteractions(client);
    }

    @Test
    void preservesNullableStandardFieldsWhenUpdatingCustomAttributes() {
        var current = new KeycloakProfileRepresentation("subject", "live", null, null, null,
                Map.of("country", List.of("SE")));
        var saved = new KeycloakProfileRepresentation("subject", "live", null, null, null,
                Map.of("country", List.of("NO")));
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok(current), RestResponse.ok(saved));
        when(client.updateProfile(eq("realm"), eq("subject"), any())).thenReturn(RestResponse.status(204));

        service.updateProfile(user, new UserProfilePatch(Map.of("country", "NO")));

        var payload = org.mockito.ArgumentCaptor.forClass(KeycloakProfileUpdate.class);
        verify(client).updateProfile(eq("realm"), eq("subject"), payload.capture());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("username", "live");
        expected.put("email", null);
        expected.put("firstName", null);
        expected.put("lastName", null);
        expected.put("attributes", Map.of("country", List.of("NO")));
        assertEquals(expected, payload.getValue().fields());
    }

    @Test
    void handlesMissingAndUnavailableUsersWithoutStaleFallback() {
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.status(404));
        assertEquals(404, assertThrows(WebApplicationException.class, () -> service.getProfile(user)).getResponse().getStatus());
        for (int status : List.of(401, 403, 500)) {
            when(client.getProfile("realm", "subject")).thenReturn(RestResponse.status(status));
            assertThrows(KeycloakIdentityException.class, () -> service.getProfile(user));
        }
        when(client.getProfile("realm", "subject")).thenReturn(RestResponse.ok());
        assertThrows(KeycloakIdentityException.class, () -> service.getProfile(user));
        when(client.getProfile("realm", "subject")).thenThrow(new IllegalStateException("private"));
        assertThrows(KeycloakIdentityException.class, () -> service.getProfile(user));
    }
}