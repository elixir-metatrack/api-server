package no.metatrack.server.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.keycloak.KeycloakAdminClient;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.auth.keycloak.KeycloakProfileRepresentation;
import no.metatrack.server.auth.keycloak.KeycloakProfileUpdate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class UserProfileService {
    private final KeycloakAdminClient client;
    private final String realm;

    public UserProfileService(@RestClient KeycloakAdminClient client,
                              @ConfigProperty(name = "keycloak.realm") String realm) {
        this.client = client;
        this.realm = realm;
    }

    public UserResponse getProfile(CurrentUser user) {
        return response(user, read(user));
    }

    public UserResponse updateProfile(CurrentUser user, UserProfilePatch patch) {
        var normalized = ProfileFieldValidator.normalize(patch);
        var current = read(user);
        Map<String, Object> changes = new LinkedHashMap<>();
        Map<String, List<String>> attributes = current.attributes() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(current.attributes());
        boolean attributesChanged = false;
        for (var entry : normalized.fields().entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (field.equals("firstName") || field.equals("lastName")) {
                String previous = field.equals("firstName") ? current.firstName() : current.lastName();
                if (!Objects.equals(previous, value)) {
                    changes.put(field, value == null ? "" : value);
                }
            } else {
                List<String> replacement = value == null ? List.of() : List.of(value);
                if (!Objects.equals(attributes.get(field), replacement)) {
                    attributes.put(field, replacement);
                    attributesChanged = true;
                }
            }
        }
        if (attributesChanged) {
            changes.put("attributes", attributes);
        }
        if (changes.isEmpty()) {
            return response(user, current);
        }
        try (RestResponse<Void> result = client.updateProfile(realm, user.id(), new KeycloakProfileUpdate(changes))) {
            switch (result.getStatus()) {
                case 204, 200 -> { }
                case 400 -> throw UserProfilePatch.invalid("profile", "Keycloak rejected the profile; check supplied fields and realm policy");
                case 404 -> throw new WebApplicationException("Current user not found", 404);
                case 409 -> throw new WebApplicationException("Profile update conflict", 409);
                default -> throw new KeycloakIdentityException("Keycloak profile update failed");
            }
        } catch (KeycloakIdentityException exception) {
            throw exception;
        } catch (WebApplicationException exception) {
            int status = exception.getResponse().getStatus();
            if (status == 400) {
                throw UserProfilePatch.invalid("profile", "Keycloak rejected the profile; check supplied fields and realm policy");
            }
            if (status == 404 || status == 409) {
                throw new WebApplicationException(status == 404 ? "Current user not found" : "Profile update conflict", status);
            }
            throw new KeycloakIdentityException("Keycloak profile update failed", exception);
        } catch (RuntimeException exception) {
            throw new KeycloakIdentityException("Keycloak profile update outcome uncertain", exception);
        }
        return getProfile(user);
    }

    private KeycloakProfileRepresentation read(CurrentUser user) {
        if (user.id() == null || user.id().isBlank()) {
            throw new WebApplicationException("Invalid subject", 401);
        }
        try (RestResponse<KeycloakProfileRepresentation> result = client.getProfile(realm, user.id())) {
            if (result.getStatus() == 404) {
                throw new WebApplicationException("Current user not found", 404);
            }
            if (result.getStatus() != 200) {
                throw new KeycloakIdentityException("Keycloak profile read failed");
            }
            var profile = result.getEntity();
            if (profile == null || !user.id().equals(profile.id()) || profile.username() == null) {
                throw new KeycloakIdentityException("Keycloak profile response invalid");
            }
            return profile;
        } catch (KeycloakIdentityException exception) {
            throw exception;
        } catch (WebApplicationException exception) {
            if (exception.getResponse().getStatus() == 404) {
                throw new WebApplicationException("Current user not found", 404);
            }
            throw new KeycloakIdentityException("Keycloak profile read failed", exception);
        } catch (RuntimeException exception) {
            throw new KeycloakIdentityException("Keycloak profile read failed", exception);
        }
    }

    private UserResponse response(CurrentUser user, KeycloakProfileRepresentation profile) {
        return new UserResponse(user.id(), profile.username(), user.roles(), attribute(profile, "country"),
                attribute(profile, "institution"), attribute(profile, "orcid"), profile.firstName(), profile.lastName());
    }

    private String attribute(KeycloakProfileRepresentation profile, String field) {
        var values = profile.attributes() == null ? null : profile.attributes().get(field);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}