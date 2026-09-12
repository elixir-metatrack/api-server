package no.metatrack.server.auth.keycloak;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import no.metatrack.server.auth.EmailAddress;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class IdentityLookupService {
    private final KeycloakAdminClient keycloakAdminClient;
    private final String realm;

    public IdentityLookupService(
            @RestClient KeycloakAdminClient keycloakAdminClient,
            @ConfigProperty(name = "keycloak.realm") String realm
    ) {
        this.keycloakAdminClient = keycloakAdminClient;
        this.realm = realm;
    }

    @CacheResult(cacheName = "keycloak-usernames")
    public Optional<String> username(UUID userId) {
        try (RestResponse<KeycloakUserRepresentation> response = keycloakAdminClient.getUser(realm, userId.toString())) {
            if (response.getStatus() == ResponseStatus.NOT_FOUND) {
                return Optional.empty();
            }
            if (response.getStatus() != ResponseStatus.OK) {
                throw new KeycloakIdentityException(
                        "Keycloak user lookup failed (category=upstream_http, status=" + response.getStatus() + ")"
                );
            }

            KeycloakUserRepresentation user = response.getEntity();
            if (user == null) {
                throw new KeycloakIdentityException("Keycloak user lookup returned an empty response");
            }
            return Optional.ofNullable(user.username());
        } catch (KeycloakIdentityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KeycloakIdentityException(
                    "Keycloak user lookup failed (category=" + failureCategory(exception) + ")",
                    exception
            );
        }
    }

    public Optional<KeycloakUserRepresentation> findByEmail(String email) {
        String normalized = EmailAddress.normalize(email);
        try (RestResponse<List<KeycloakUserRepresentation>> response =
                     keycloakAdminClient.searchByEmail(realm, normalized, true)) {
            if (response.getStatus() != ResponseStatus.OK) {
                throw new KeycloakIdentityException(
                        "Keycloak email lookup failed (category=upstream_http, status=" + response.getStatus() + ")");
            }
            List<KeycloakUserRepresentation> users = response.getEntity();
            if (users == null) {
                throw new KeycloakIdentityException("Keycloak email lookup returned an empty response");
            }
            KeycloakUserRepresentation match = null;
            for (KeycloakUserRepresentation user : users) {
                if (user == null || !normalized.equals(EmailAddress.normalize(user.email()))) {
                    throw new KeycloakIdentityException("Keycloak email lookup returned an unexpected match");
                }
                UUID subject = UUID.fromString(user.id());
                if (!subject.toString().equalsIgnoreCase(user.id())) {
                    throw new KeycloakIdentityException("Keycloak email lookup returned an invalid subject");
                }
                if (match != null) {
                    throw new KeycloakIdentityException("Keycloak email lookup returned ambiguous matches");
                }
                match = user;
            }
            return Optional.ofNullable(match);
        } catch (KeycloakIdentityException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KeycloakIdentityException(
                    "Keycloak email lookup failed (category=" + failureCategory(exception) + ")", exception);
        }
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

    public Map<UUID, Optional<String>> usernames(Collection<UUID> userIds) {
        Map<UUID, Optional<String>> usernames = new LinkedHashMap<>();
        for (UUID userId : userIds) {
            usernames.computeIfAbsent(userId, this::username);
        }
        return usernames;
    }

    private static final class ResponseStatus {
        private static final int OK = 200;
        private static final int NOT_FOUND = 404;

        private ResponseStatus() {
        }
    }
}