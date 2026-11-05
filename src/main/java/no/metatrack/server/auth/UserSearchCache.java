package no.metatrack.server.auth;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import no.metatrack.server.auth.keycloak.KeycloakAdminClient;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.auth.keycloak.KeycloakProfileRepresentation;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserSearchCache {
    private static final int FIRST_RESULT = 0;
    private static final int REQUESTED_RESULTS = 21;
    private static final int MAX_RESULTS = 20;
    private static final int OK = 200;

    private final KeycloakAdminClient keycloakAdminClient;
    private final String realm;

    public UserSearchCache(
            @RestClient KeycloakAdminClient keycloakAdminClient,
            @ConfigProperty(name = "keycloak.realm") String realm
    ) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.realm = realm;
    }

    @CacheResult(cacheName = "keycloak-user-search")
    public UserSearchResponse searchNormalizedQuery(String normalizedQuery) {
        try (RestResponse<List<KeycloakProfileRepresentation>> response = keycloakAdminClient.searchUsers(
                realm, normalizedQuery, false, FIRST_RESULT, REQUESTED_RESULTS)) {
            if (response.getStatus() != OK) {
                throw new KeycloakIdentityException(
                        "Keycloak user search failed (category=upstream_http, status=" + response.getStatus() + ")");
            }
            List<KeycloakProfileRepresentation> profiles = response.getEntity();
            if (profiles == null) {
                throw new KeycloakIdentityException("Keycloak user search returned an empty response");
            }

            int resultCount = Math.min(profiles.size(), MAX_RESULTS);
            List<UserSearchResult> users = new ArrayList<>(resultCount);
            for (int index = 0; index < resultCount; index++) {
                users.add(toSearchResult(profiles.get(index)));
            }
            return new UserSearchResponse(users, profiles.size() > MAX_RESULTS);
        } catch (KeycloakIdentityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KeycloakIdentityException(
                    "Keycloak user search failed (category=" + failureCategory(exception) + ")", exception);
        }
    }

    private UserSearchResult toSearchResult(KeycloakProfileRepresentation profile) {
        if (profile == null || profile.id() == null) {
            throw new KeycloakIdentityException("Keycloak user search returned an invalid identity");
        }
        UUID userId;
        try {
            userId = UUID.fromString(profile.id());
        } catch (IllegalArgumentException exception) {
            throw new KeycloakIdentityException("Keycloak user search returned an invalid identity", exception);
        }
        if (!userId.toString().equalsIgnoreCase(profile.id())) {
            throw new KeycloakIdentityException("Keycloak user search returned an invalid identity");
        }

        String displayName = joinName(profile.firstName(), profile.lastName());
        if (displayName == null) {
            displayName = nonBlank(profile.email());
        }
        if (displayName == null) {
            displayName = nonBlank(profile.username());
        }
        if (displayName == null) {
            displayName = userId.toString();
        }
        return new UserSearchResult(userId.toString(), profile.firstName(), profile.lastName(), displayName, profile.email());
    }

    private String joinName(String firstName, String lastName) {
        String first = nonBlank(firstName);
        String last = nonBlank(lastName);
        if (first == null) {
            return last;
        }
        if (last == null) {
            return first;
        }
        return first + " " + last;
    }

    private String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String failureCategory(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String typeName = current.getClass().getName().toLowerCase();
            if (typeName.contains("oidc") || typeName.contains("tokenclient")) {
                return "token_client";
            }
            current = current.getCause();
        }
        return "transport";
    }
}