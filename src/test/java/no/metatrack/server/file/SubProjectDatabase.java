package no.metatrack.server.file;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

public class SubProjectDatabase implements QuarkusTestResourceLifecycleManager {
    private final PostgreSQLContainer database = new PostgreSQLContainer("postgres:16");

    @Override
    public Map<String, String> start() {
        database.start();
        return Map.of(
                "quarkus.datasource.jdbc.url", database.getJdbcUrl(),
                "quarkus.datasource.username", database.getUsername(),
                "quarkus.datasource.password", database.getPassword());
    }

    @Override
    public void stop() {
        database.stop();
    }
}
