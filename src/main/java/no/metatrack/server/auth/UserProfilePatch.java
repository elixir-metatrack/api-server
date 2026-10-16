package no.metatrack.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record UserProfilePatch(Map<String, String> fields) {
    static final Set<String> ALLOWED = Set.of("firstName", "lastName", "country", "institution", "orcid");

    public UserProfilePatch {
        if (fields == null || !ALLOWED.containsAll(fields.keySet())) {
            throw invalid("profile", "Only editable profile fields are accepted");
        }
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static UserProfilePatch parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("profile", "Expected a JSON object");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        body.properties().forEach(entry -> {
            if (!ALLOWED.contains(entry.getKey())) {
                throw invalid("profile", "Only editable profile fields are accepted");
            }
            var value = entry.getValue();
            if (!value.isNull() && !value.isTextual()) {
                throw invalid(entry.getKey(), "Expected a string or null");
            }
            fields.put(entry.getKey(), value.isNull() ? null : value.textValue());
        });
        return new UserProfilePatch(fields);
    }

    static WebApplicationException invalid(String field, String message) {
        return new WebApplicationException(Response.status(400)
                .entity(Map.of("error", "invalid_profile", "fields", Map.of(field, message))).build());
    }
}