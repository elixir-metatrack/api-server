package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SftpGoManagementService {
    private static final String UPLOAD_ACTION_NAME = "metatrack-batch-upload-admission";
    private static final String UPLOAD_RULE_NAME = "metatrack-batch-upload-admission";
    private static final String UPLOAD_COMPLETION_RULE_NAME = "metatrack-batch-upload-completion";
    private static final String HOOK_USERNAME = "metatrack-sftpgo-hook";
    private static final String HOOK_BODY = "{\"event\":\"{{.Event}}\",\"username\":\"{{.Name}}\","
            + "\"path\":\"{{.EscapedVirtualPath}}\",\"status\":{{.Status}},"
            + "\"sizeBytes\":{{.FileSize}}}";
    private static final int RESOURCE_PAGE_SIZE = 500;
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 30;

    private final SftpGoAdminClient client;
    private final SftpGoConfig config;
    private final ObjectMapper objectMapper;
    private volatile CachedToken cachedToken;

    public SftpGoManagementService(@RestClient SftpGoAdminClient client,
                                   SftpGoConfig config,
                                   ObjectMapper objectMapper) {
        this.client = client;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public void ensureUploadEventIntegration() {
        if (!config.enabled()) {
            return;
        }
        validateConfiguration();
        ensureUploadEventAction();
        ensureUploadEventRule();
    }

    public Optional<JsonNode> findUser(String username) {
        try (RestResponse<JsonNode> response = client.getUser(bearerToken(), username)) {
            if (response.getStatus() == 404) {
                return Optional.empty();
            }
            if (response.getStatus() != 200 || response.getEntity() == null) {
                throw upstreamFailure("read user", response.getStatus());
            }
            return Optional.of(response.getEntity());
        }
    }

    public void createUser(JsonNode user) {
        try (RestResponse<JsonNode> response = client.createUser(bearerToken(), user)) {
            if (response.getStatus() != 201) {
                throw upstreamFailure("create user", response.getStatus());
            }
        }
    }

    public void ensureUser(JsonNode user) {
        String username = user.path("username").asText();
        if (username.isBlank()) {
            throw new SftpGoIntegrationException("SFTPGo user payload has no username");
        }
        JsonNode existing = findUser(username).orElse(null);
        if (existing != null) {
            if (!matchesUserConfiguration(existing, user)) {
                throw new SftpGoIntegrationException("SFTPGo batch user exists with a different configuration");
            }
            return;
        }
        createUser(user);
    }

    public void updateUser(String username, JsonNode user) {
        try (RestResponse<JsonNode> response = client.updateUser(bearerToken(), username, user)) {
            if (response.getStatus() != 200) {
                throw upstreamFailure("update user", response.getStatus());
            }
        }
    }

    public void deleteUser(String username) {
        try (RestResponse<JsonNode> response = client.deleteUser(bearerToken(), username)) {
            if (response.getStatus() != 200 && response.getStatus() != 204 && response.getStatus() != 404) {
                throw upstreamFailure("delete user", response.getStatus());
            }
        }
    }

    public List<SftpGoTransfer> activeTransfers(String username) {
        return activeConnections().stream()
                .filter(connection -> username.equals(connection.username()))
                .flatMap(connection -> connection.activeTransfers() == null
                        ? java.util.stream.Stream.empty()
                        : connection.activeTransfers().stream())
                .toList();
    }

    public List<SftpGoConnection> activeConnections() {
        try (RestResponse<List<SftpGoConnection>> response = client.connections(bearerToken())) {
            if (response.getStatus() != 200 || response.getEntity() == null) {
                throw upstreamFailure("read active connections", response.getStatus());
            }
            return List.copyOf(response.getEntity());
        }
    }

    private void ensureUploadEventAction() {
        JsonNode existing = findNamedResource(true, UPLOAD_ACTION_NAME);
        if (existing == null) {
            try (RestResponse<JsonNode> response = client.createEventAction(bearerToken(), uploadEventAction())) {
                if (response.getStatus() == 201 || response.getStatus() == 200) {
                    return;
                }
                if (response.getStatus() != 409) {
                    throw upstreamFailure("create upload admission action", response.getStatus());
                }
            }
            existing = findNamedResource(true, UPLOAD_ACTION_NAME);
        }
        if (existing == null) {
            throw new SftpGoIntegrationException("SFTPGo upload admission action is missing");
        }
        try (RestResponse<JsonNode> response = client.updateEventAction(
                bearerToken(), UPLOAD_ACTION_NAME, uploadEventAction())) {
            if (response.getStatus() != 200) {
                throw upstreamFailure("update upload admission action", response.getStatus());
            }
        }
    }

    private void ensureUploadEventRule() {
        ensureUploadEventRule(
                UPLOAD_RULE_NAME, "pre-upload", true);
        ensureUploadEventRule(
                UPLOAD_COMPLETION_RULE_NAME, "upload", false);
    }

    private void ensureUploadEventRule(String name, String event, boolean executeSync) {
        JsonNode existing = findNamedResource(false, name);
        if (existing == null) {
            try (RestResponse<JsonNode> response = client.createEventRule(
                    bearerToken(), uploadEventRule(name, event, executeSync))) {
                if (response.getStatus() == 201 || response.getStatus() == 200) {
                    return;
                }
                if (response.getStatus() != 409) {
                    throw upstreamFailure("create upload event rule", response.getStatus());
                }
            }
            existing = findNamedResource(false, name);
        }
        if (existing == null || !isExpectedUploadRule(existing, name, event, executeSync)) {
            throw new SftpGoIntegrationException("SFTPGo upload event rule is missing or misconfigured: " + name);
        }
    }

    private JsonNode findNamedResource(boolean action, String name) {
        int offset = 0;
        while (true) {
            JsonNode page;
            if (action) {
                try (RestResponse<JsonNode> response = client.eventActions(bearerToken(), offset, RESOURCE_PAGE_SIZE)) {
                    if (response.getStatus() != 200 || response.getEntity() == null) {
                        throw upstreamFailure("list event actions", response.getStatus());
                    }
                    page = response.getEntity();
                }
            } else {
                try (RestResponse<JsonNode> response = client.eventRules(bearerToken(), offset, RESOURCE_PAGE_SIZE)) {
                    if (response.getStatus() != 200 || response.getEntity() == null) {
                        throw upstreamFailure("list event rules", response.getStatus());
                    }
                    page = response.getEntity();
                }
            }
            if (!page.isArray()) {
                throw new SftpGoIntegrationException("SFTPGo returned an invalid event resource list");
            }
            for (JsonNode resource : page) {
                if (name.equals(resource.path("name").asText())) {
                    return resource;
                }
            }
            if (page.size() < RESOURCE_PAGE_SIZE) {
                return null;
            }
            offset += page.size();
        }
    }

    private JsonNode uploadEventAction() {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("name", UPLOAD_ACTION_NAME);
        action.put("description", "Metatrack batch upload admission hook");
        action.put("type", 1);
        ObjectNode options = action.putObject("options");
        ObjectNode http = options.putObject("http_config");
        http.put("endpoint", config.hookUrl().orElseThrow());
        http.put("username", HOOK_USERNAME);
        http.putObject("password").put("status", "Plain").put("payload", config.hookSecret().orElseThrow());
        http.put("method", "POST");
        http.put("timeout", 10);
        http.put("body", HOOK_BODY);
        ArrayNode headers = http.putArray("headers");
        headers.addObject().put("key", "Content-Type").put("value", "application/json");
        return action;
    }

    private JsonNode uploadEventRule(String name, String eventName, boolean executeSync) {
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("name", name);
        rule.put("description", "SFTPGo batch upload event handling");
        rule.put("status", 1);
        rule.put("trigger", 1);
        ArrayNode events = rule.putObject("conditions").putArray("fs_events");
        events.add(eventName);
        ObjectNode action = rule.putArray("actions").addObject();
        action.put("name", UPLOAD_ACTION_NAME);
        action.put("order", 1);
        action.putObject("relation_options")
                .put("execute_sync", executeSync)
                .put("stop_on_failure", true);
        return rule;
    }


    private boolean isExpectedUploadRule(JsonNode rule, String name, String eventName, boolean executeSync) {
        if (rule.path("status").asInt() != 1 || rule.path("trigger").asInt() != 1) {
            return false;
        }
        JsonNode events = rule.path("conditions").path("fs_events");
        if (!name.equals(rule.path("name").asText())
                || !events.isArray() || events.size() != 1 || !contains(events, eventName)) {
            return false;
        }
        JsonNode actions = rule.path("actions");
        if (!actions.isArray()) {
            return false;
        }
        for (JsonNode action : actions) {
            JsonNode relationOptions = action.path("relation_options");
            if (UPLOAD_ACTION_NAME.equals(action.path("name").asText())
                    && relationOptions.path("execute_sync").asBoolean() == executeSync
                    && relationOptions.path("stop_on_failure").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesUserConfiguration(JsonNode existing, JsonNode expected) {
        JsonNode existingS3 = existing.path("filesystem").path("s3config");
        JsonNode expectedS3 = expected.path("filesystem").path("s3config");
        if (existing.path("status").asInt() != 1
                || existing.path("filesystem").path("provider").asInt() != 1
                || existing.path("quota_size").asLong(-1) != expected.path("quota_size").asLong()
                || existing.path("quota_files").asInt(-1) != expected.path("quota_files").asInt()
                || existing.path("max_sessions").asInt(-1) != expected.path("max_sessions").asInt()
                || !expected.path("home_dir").asText().equals(existing.path("home_dir").asText())
                || !expectedS3.path("bucket").asText().equals(existingS3.path("bucket").asText())
                || !expectedS3.path("region").asText().equals(existingS3.path("region").asText())
                || !expectedS3.path("endpoint").asText().equals(existingS3.path("endpoint").asText())
                || !expectedS3.path("access_key").asText().equals(existingS3.path("access_key").asText())
                || !expectedS3.path("key_prefix").asText().equals(existingS3.path("key_prefix").asText())
                || expectedS3.path("force_path_style").asBoolean()
                        != existingS3.path("force_path_style").asBoolean()
                || !sameValues(expected.path("permissions").path("/"), existing.path("permissions").path("/"))) {
            return false;
        }
        JsonNode password = existing.path("password");
        if (!password.isMissingNode() && !password.asText().isBlank()) return false;
        String publicKey = expected.path("public_keys").path(0).asText();
        if (publicKey.isBlank() || !contains(existing.path("public_keys"), publicKey)) {
            return false;
        }
        JsonNode expectedProtocols = expected.path("filters").path("allowed_protocols");
        JsonNode actualProtocols = existing.path("filters").path("allowed_protocols");
        if (!expectedProtocols.isArray() || !actualProtocols.isArray()
                || expectedProtocols.size() != actualProtocols.size()) {
            return false;
        }
        for (JsonNode protocol : expectedProtocols) {
            if (!contains(actualProtocols, protocol.asText())) return false;
        }
        return true;
    }

    private boolean sameValues(JsonNode expected, JsonNode actual) {
        if (!expected.isArray() || !actual.isArray() || expected.size() != actual.size()) return false;
        for (JsonNode value : expected) {
            if (!contains(actual, value.asText())) return false;
        }
        return true;
    }

    private boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private String bearerToken() {
        Instant now = Instant.now();
        CachedToken current = cachedToken;
        if (current != null && current.expiresAt().isAfter(now.plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
            return current.authorization();
        }
        synchronized (this) {
            current = cachedToken;
            now = Instant.now();
            if (current != null && current.expiresAt().isAfter(now.plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
                return current.authorization();
            }
            validateCredentials();
            String credentials = config.adminUsername().orElseThrow() + ":" + config.adminPassword().orElseThrow();
            String basic = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            try (RestResponse<SftpGoToken> response = client.token(basic)) {
                if (response.getStatus() != 200 || response.getEntity() == null
                        || response.getEntity().accessToken() == null
                        || response.getEntity().accessToken().isBlank()
                        || response.getEntity().expiresAt() == null) {
                    throw upstreamFailure("authenticate", response.getStatus());
                }
                SftpGoToken token = response.getEntity();
                cachedToken = new CachedToken("Bearer " + token.accessToken(), token.expiresAt());
                if (!token.expiresAt().isAfter(now)) {
                    cachedToken = null;
                    throw new SftpGoIntegrationException("SFTPGo returned an expired API token");
                }
                return cachedToken.authorization();
            }
        }
    }

    private void validateConfiguration() {
        if (config.hookUrl().filter(value -> !value.isBlank()).isEmpty()
                || config.hookSecret().filter(value -> value.length() >= 32).isEmpty()) {
            throw new SftpGoIntegrationException("SFTPGo hook URL and a 32-character hook secret are required");
        }
        validateCredentials();
    }

    private void validateCredentials() {
        if (config.adminUsername().filter(value -> !value.isBlank()).isEmpty()
                || config.adminPassword().filter(value -> !value.isBlank()).isEmpty()) {
            throw new SftpGoIntegrationException("SFTPGo administrator credentials are required");
        }
    }

    private SftpGoIntegrationException upstreamFailure(String operation, int status) {
        return new SftpGoIntegrationException("SFTPGo " + operation + " failed (HTTP " + status + ")");
    }

    private record CachedToken(String authorization, Instant expiresAt) {
    }
}