package no.metatrack.server.spreadsheet;

import no.metatrack.server.project.ProjectInvitationPostgresProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpreadsheetPostgresProfile extends ProjectInvitationPostgresProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        var config = new HashMap<>(super.getConfigOverrides());
        String schema = "spreadsheet_test_" + UUID.randomUUID().toString().replace("-", "");
        config.put("quarkus.hibernate-orm.database.default-schema", schema);
        config.put("quarkus.flyway.schemas", schema);
        config.put("quarkus.flyway.default-schema", schema);
        config.put("quarkus.oidc.tenant-enabled", "false");
        config.put("quarkus.http.test-port", "0");
        config.put("quarkus.log.console.json.enabled", "false");
        return config;
    }
}
