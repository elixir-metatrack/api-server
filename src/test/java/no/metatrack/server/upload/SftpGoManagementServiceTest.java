package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SftpGoManagementServiceTest {
    private static final String HOOK_SECRET = "local-test-hook-secret-with-32-chars";

    @Test
    void createsAndValidatesSynchronousUploadHooksIdempotently() {
        var objectMapper = new ObjectMapper();
        var actions = new AtomicReference<JsonNode>(objectMapper.createArrayNode());
        var rules = new AtomicReference<JsonNode>(objectMapper.createArrayNode());
        var action = new AtomicReference<JsonNode>();
        var rule = new AtomicReference<JsonNode>();
        var actionCreates = new AtomicInteger();
        var actionUpdates = new AtomicInteger();
        var ruleCreates = new AtomicInteger();
        var client = mock(SftpGoAdminClient.class);
        var tokenResponse = restResponse(200,
                new SftpGoToken("token-value", Instant.now().plusSeconds(3600)));
        when(client.token(anyString())).thenReturn(tokenResponse);
        when(client.eventActions(anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> restResponse(200, actions.get()));
        when(client.eventRules(anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> restResponse(200, rules.get()));
        when(client.createEventAction(anyString(), any(JsonNode.class))).thenAnswer(invocation -> {
            actionCreates.incrementAndGet();
            action.set(invocation.getArgument(1));
            actions.set(objectMapper.createArrayNode().add(action.get()));
            return restResponse(201, objectMapper.createObjectNode());
        });
        when(client.updateEventAction(anyString(), anyString(), any(JsonNode.class))).thenAnswer(invocation -> {
            actionUpdates.incrementAndGet();
            action.set(invocation.getArgument(2));
            actions.set(objectMapper.createArrayNode().add(action.get()));
            return restResponse(200, objectMapper.createObjectNode());
        });
        when(client.createEventRule(anyString(), any(JsonNode.class))).thenAnswer(invocation -> {
            ruleCreates.incrementAndGet();
            rule.set(invocation.getArgument(1));
            ((com.fasterxml.jackson.databind.node.ArrayNode) rules.get()).add(rule.get());
            return restResponse(201, objectMapper.createObjectNode());
        });

        var service = new SftpGoManagementService(client, config(), objectMapper);
        service.ensureUploadEventIntegration();
        service.ensureUploadEventIntegration();

        assertEquals(1, actionCreates.get());
        assertEquals(1, actionUpdates.get());
        assertEquals(2, ruleCreates.get());
        verify(client, times(1)).token(eq("Basic YWRtaW46cGFzc3dvcmQ="));
        assertEquals("POST", action.get().path("options").path("http_config").path("method").asText());
        assertTrue(action.get().path("options").path("http_config").path("body").asText().contains("EscapedVirtualPath"));
        assertEquals("metatrack-sftpgo-hook", action.get().path("options").path("http_config").path("username").asText());
        assertEquals("Plain", action.get().path("options").path("http_config").path("password").path("status").asText());
        assertEquals(HOOK_SECRET, action.get().path("options").path("http_config").path("password").path("payload").asText());
        JsonNode admissionRule = rules.get().get(0);
        JsonNode admissionOptions = admissionRule.path("actions").get(0).path("relation_options");
        assertEquals("pre-upload", admissionRule.path("conditions").path("fs_events").get(0).asText());
        assertTrue(admissionOptions.path("execute_sync").asBoolean());
        assertTrue(admissionOptions.path("stop_on_failure").asBoolean());
        JsonNode completionRule = rules.get().get(1);
        JsonNode completionOptions = completionRule.path("actions").get(0).path("relation_options");
        assertEquals("upload", completionRule.path("conditions").path("fs_events").get(0).asText());
        assertFalse(completionOptions.path("execute_sync").asBoolean());
    }

    @Test
    void readsOnlyTheRequestedUsersActiveTransfers() {
        var client = mock(SftpGoAdminClient.class);
        var tokenResponse = restResponse(200,
                new SftpGoToken("token-value", Instant.now().plusSeconds(3600)));
        var connectionResponse = restResponse(200, List.of(
                new SftpGoConnection("batch-a", "conn-a",
                        List.of(new SftpGoTransfer("upload", "/reads.fastq", 99))),
                new SftpGoConnection("batch-b", "conn-b", List.of())
        ));
        when(client.token(anyString())).thenReturn(tokenResponse);
        when(client.connections(anyString())).thenReturn(connectionResponse);

        var transfers = new SftpGoManagementService(client, config(), new ObjectMapper())
                .activeTransfers("batch-a");

        assertEquals(1, transfers.size());
        assertEquals("upload", transfers.getFirst().operationType());
        assertEquals("/reads.fastq", transfers.getFirst().path());
        assertEquals(99, transfers.getFirst().size());
    }

    @Test
    void createsMissingBatchUserAndReusesMatchingUserAfterRetry() {
        var objectMapper = new ObjectMapper();
        var client = mock(SftpGoAdminClient.class);
        var tokenResponse = restResponse(200,
                new SftpGoToken("token-value", Instant.now().plusSeconds(3600)));
        RestResponse<JsonNode> missingUser = restResponse(404, (JsonNode) null);
        RestResponse<JsonNode> createdUser = restResponse(201, (JsonNode) objectMapper.createObjectNode());
        JsonNode user = batchUser(objectMapper);
        RestResponse<JsonNode> existingUser = restResponse(200, (JsonNode) user.deepCopy());
        when(client.token(anyString())).thenReturn(tokenResponse);
        when(client.getUser(anyString(), eq("mtb-batch-uuid")))
                .thenReturn(missingUser, existingUser);
        when(client.createUser(anyString(), any(JsonNode.class))).thenReturn(createdUser);
        var service = new SftpGoManagementService(client, config(), objectMapper);

        service.ensureUser(user);
        service.ensureUser(user);

        verify(client, times(1)).createUser(anyString(), any(JsonNode.class));
        verify(client, times(1)).token(anyString());
    }

    private JsonNode batchUser(ObjectMapper objectMapper) {
        ObjectNode user = objectMapper.createObjectNode();
        user.put("username", "mtb-batch-uuid");
        user.put("status", 1);
        user.put("home_dir", "/srv/sftpgo/data/mtb-batch-uuid");
        user.put("max_sessions", 2);
        user.put("quota_size", 800);
        user.put("quota_files", 2);
        user.putArray("public_keys").add("ssh-ed25519 YWJjZGVmZ2hpamtsbW5vcA== batch-test");
        user.putObject("permissions").putArray("/")
                .add("list").add("upload").add("overwrite").add("create_dirs");
        user.putObject("filters").putArray("allowed_protocols").add("SFTP");
        ObjectNode s3 = user.putObject("filesystem").put("provider", 1).putObject("s3config");
        s3.put("bucket", "test-bucket");
        s3.put("region", "us-east-1");
        s3.put("endpoint", "http://silo:9000");
        s3.put("access_key", "test-key");
        s3.put("key_prefix", "staging/" + UUID.randomUUID() + "/");
        s3.put("force_path_style", true);
        return user;
    }

    @SuppressWarnings("unchecked")
    private <T> RestResponse<T> restResponse(int status, T entity) {
        var response = (RestResponse<T>) mock(RestResponse.class);
        when(response.getStatus()).thenReturn(status);
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    private SftpGoConfig config() {
        var config = mock(SftpGoConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.adminUsername()).thenReturn(java.util.Optional.of("admin"));
        when(config.adminPassword()).thenReturn(java.util.Optional.of("password"));
        when(config.hookUrl()).thenReturn(java.util.Optional.of("http://localhost:1234/api/internal/sftpgo/events"));
        when(config.hookSecret()).thenReturn(java.util.Optional.of(HOOK_SECRET));
        return config;
    }

}