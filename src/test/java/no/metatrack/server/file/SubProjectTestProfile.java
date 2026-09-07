package no.metatrack.server.file;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Isolates tests from the developer's database, identity provider, and object storage. */
public class SubProjectTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("quarkus.datasource.devservices.enabled", "false"),
                Map.entry("quarkus.hibernate-orm.schema-management.strategy", "none"),
                Map.entry("quarkus.flyway.migrate-at-start", "true"),
                Map.entry("quarkus.oidc.tenant-enabled", "false"),
                Map.entry("quarkus.oidc.auth-server-url", "http://localhost:1/realms/test"),
                Map.entry("quarkus.keycloak.devservices.enabled", "false"),
                Map.entry("quarkus.oidc-client.client-enabled", "false"),
                Map.entry("quarkus.oidc-client.keycloak-admin.client-enabled", "false"),
                Map.entry("quarkus.oidc-client.keycloak-admin.auth-server-url", "http://localhost:1/realms/test"),
                Map.entry("quarkus.oidc-client.keycloak-admin.client-id", "test"),
                Map.entry("quarkus.oidc-client.keycloak-admin.credentials.secret", "test"),
                Map.entry("quarkus.scheduler.enabled", "false"),
                Map.entry("quarkus.http.test-port", "0"),
                Map.entry("quarkus.log.console.json.enabled", "false"),
                Map.entry("metatrack.s3.endpoint", "http://localhost:1"),
                Map.entry("metatrack.s3.region", "us-east-1"),
                Map.entry("metatrack.s3.access-key", "test"),
                Map.entry("metatrack.s3.secret-key", "test"),
                Map.entry("keycloak-admin-api/mp-rest/url", "http://localhost:1"),
                Map.entry("keycloak.realm", "test"));
    }
}
