package no.metatrack.server.auth.keycloak;

import no.metatrack.server.file.SubProjectTestProfile;
import java.util.HashMap;
import java.util.Map;

public class ProfileClientTestProfile extends SubProjectTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        var config = new HashMap<>(super.getConfigOverrides());
        config.put("quarkus.datasource.active", "true");
        config.put("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:1/unused");
        config.put("quarkus.datasource.username", "unused");
        config.put("quarkus.datasource.password", "unused");
        config.put("quarkus.hibernate-orm.database.start-offline", "true");
        config.put("quarkus.hibernate-orm.schema-management.strategy", "none");
        config.put("quarkus.flyway.migrate-at-start", "false");
        return config;
    }
}