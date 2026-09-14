package no.metatrack.server.notification;

import no.metatrack.server.project.ProjectInvitationPostgresProfile;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NotificationPostgresProfile extends ProjectInvitationPostgresProfile {
    static final String SCHEMA = "notification_test_" + UUID.randomUUID().toString().replace("-", "");
    static final KeyPair KEYS = keys();

    @Override
    public Map<String, String> getConfigOverrides() {
        var config = new HashMap<>(super.getConfigOverrides());
        config.put("quarkus.hibernate-orm.database.default-schema", SCHEMA);
        config.put("quarkus.flyway.schemas", SCHEMA);
        config.put("quarkus.flyway.default-schema", SCHEMA);
        config.put("quarkus.oidc.public-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        config.put("quarkus.keycloak.devservices.enabled", "false");
        return config;
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            // Test-only key must be identical across Quarkus profile and test class loaders.
            var random = java.security.SecureRandom.getInstance("SHA1PRNG");
            random.setSeed("notification-http-tests".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            generator.initialize(2048, random);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}