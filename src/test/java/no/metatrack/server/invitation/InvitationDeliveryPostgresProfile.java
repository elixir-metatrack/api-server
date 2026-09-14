package no.metatrack.server.invitation;

import no.metatrack.server.project.ProjectInvitationPostgresProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InvitationDeliveryPostgresProfile extends ProjectInvitationPostgresProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        var config = new HashMap<>(super.getConfigOverrides());
        String schema = "invitation_delivery_" + UUID.randomUUID().toString().replace("-", "");
        config.put("quarkus.hibernate-orm.database.default-schema", schema);
        config.put("quarkus.flyway.schemas", schema);
        config.put("quarkus.flyway.default-schema", schema);
        return config;
    }
}