package no.metatrack.server.auth.keycloak;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KeycloakProfileUpdate {
    private final Map<String, Object> fields;

    public KeycloakProfileUpdate(Map<String, Object> fields) {
        if (!java.util.Set.of("username", "email", "firstName", "lastName", "attributes").containsAll(fields.keySet())) {
            throw new IllegalArgumentException("Unsupported profile update field");
        }
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @JsonAnyGetter
    public Map<String, Object> fields() {
        return fields;
    }
}