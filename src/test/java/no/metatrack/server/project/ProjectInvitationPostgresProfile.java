package no.metatrack.server.project;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;
import java.util.UUID;

public class ProjectInvitationPostgresProfile implements QuarkusTestProfile {
    static final String SCHEMA = "invitation_service_" + UUID.randomUUID().toString().replace("-", "");

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("quarkus.datasource.jdbc.url", System.getenv().getOrDefault("INVITATION_TEST_JDBC_URL", "jdbc:postgresql://localhost:1/unused")),
                Map.entry("quarkus.datasource.username", System.getenv().getOrDefault("INVITATION_TEST_DB_USER", "")),
                Map.entry("quarkus.datasource.password", System.getenv().getOrDefault("INVITATION_TEST_DB_PASSWORD", "")),
                Map.entry("quarkus.hibernate-orm.database.default-schema", SCHEMA),
                Map.entry("quarkus.flyway.schemas", SCHEMA),
                Map.entry("quarkus.flyway.default-schema", SCHEMA),
                Map.entry("quarkus.oidc.auth-server-url", ""),
                Map.entry("quarkus.oidc.public-key", publicKey()),
                Map.entry("quarkus.oidc-client.keycloak-admin.client-enabled", "false"),
                Map.entry("quarkus.scheduler.enabled", "false"),
                Map.entry("quarkus.datasource.devservices.enabled", "false"),
                Map.entry("quarkus.oidc.devservices.enabled", "false"),
                Map.entry("keycloak-admin-api/mp-rest/url", "http://localhost:1"),
                Map.entry("keycloak.realm", "test"),
                Map.entry("metatrack.s3.endpoint", "http://localhost:1"),
                Map.entry("metatrack.s3.region", "us-east-1"),
                Map.entry("metatrack.s3.access-key", "test"),
                Map.entry("metatrack.s3.secret-key", "test"));
    }

    private String publicKey() {
        try {
            var generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}