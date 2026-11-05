package no.metatrack.server.auth;

import no.metatrack.server.auth.keycloak.ProfileClientTestProfile;

import java.util.HashMap;
import java.util.Map;

public class UserSearchCacheTestProfile extends ProfileClientTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        var config = new HashMap<>(super.getConfigOverrides());
        config.put("quarkus.cache.caffeine.keycloak-user-search.expire-after-write", "300ms");
        config.put("quarkus.cache.caffeine.keycloak-user-search.maximum-size", "2");
        return config;
    }
}