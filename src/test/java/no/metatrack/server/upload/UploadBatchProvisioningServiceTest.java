package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadBatchProvisioningServiceTest {
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final String USERNAME = "mtb-" + BATCH_ID.toString().replace("-", "");
    private static final String PUBLIC_KEY = "ssh-ed25519 YWJjZGVmZ2hpamtsbW5vcA== batch-test";
    private static final String S3_SECRET = "test-s3-secret";

    private UploadBatchProvisioningStateService stateService;
    private SftpGoManagementService managementService;
    private SftpGoConfig sftpGoConfig;
    private UploadBatchProvisioningService provisioningService;

    @BeforeEach
    void setUp() {
        stateService = mock(UploadBatchProvisioningStateService.class);
        managementService = mock(SftpGoManagementService.class);
        sftpGoConfig = mock(SftpGoConfig.class);
        UploadBatchConfig batchConfig = mock(UploadBatchConfig.class);
        when(batchConfig.maxTransfersPerBatch()).thenReturn(2);
        when(sftpGoConfig.s3BucketName()).thenReturn(Optional.of("test-bucket"));
        when(sftpGoConfig.s3Region()).thenReturn(Optional.of("us-east-1"));
        when(sftpGoConfig.s3Endpoint()).thenReturn(Optional.of("http://silo:9000"));
        when(sftpGoConfig.s3AccessKey()).thenReturn(Optional.of("test-key"));
        when(sftpGoConfig.s3SecretKey()).thenReturn(Optional.of(S3_SECRET));
        when(sftpGoConfig.s3PathStyleAccess()).thenReturn(true);
        provisioningService = new UploadBatchProvisioningService();
        provisioningService.stateService = stateService;
        provisioningService.sftpGoManagementService = managementService;
        provisioningService.sftpGoConfig = sftpGoConfig;
        provisioningService.batchConfig = batchConfig;
        provisioningService.objectMapper = new ObjectMapper();
    }

    @Test
    void provisionsSftpOnlyPublicKeyUserWithBatchPrefixAndQuotas() {
        Instant expiresAt = Instant.now().plusSeconds(86_400);
        UploadBatchProvisioningClaim claim = new UploadBatchProvisioningClaim(
                BATCH_ID, USERNAME, PUBLIC_KEY, expiresAt, 1, 900, 2);
        when(stateService.claim(BATCH_ID)).thenReturn(Optional.of(claim));
        when(stateService.markOpen(BATCH_ID)).thenReturn(true);

        assertTrue(provisioningService.provision(BATCH_ID));

        var user = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(managementService).ensureUser(user.capture());
        JsonNode payload = user.getValue();
        assertEquals(USERNAME, payload.path("username").asText());
        assertEquals(expiresAt.toEpochMilli(), payload.path("expiration_date").asLong());
        assertEquals(2, payload.path("max_sessions").asInt());
        assertEquals(900, payload.path("quota_size").asLong());
        assertEquals(2, payload.path("quota_files").asInt());
        assertTrue(payload.path("public_keys").toString().contains(PUBLIC_KEY));
        assertFalse(payload.has("password"));
        assertEquals(List.of("SFTP"),
                new ObjectMapper().convertValue(payload.path("filters").path("allowed_protocols"),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        assertEquals("staging/" + BATCH_ID + "/",
                payload.path("filesystem").path("s3config").path("key_prefix").asText());
        assertEquals(S3_SECRET,
                payload.path("filesystem").path("s3config").path("access_secret").path("payload").asText());
        assertFalse(payload.path("permissions").path("/").toString().contains("download"));
        verify(stateService).markOpen(BATCH_ID);
    }

    @Test
    void deletesProvisionedUserIfBatchExpiresBeforeItCanOpen() {
        when(stateService.claim(BATCH_ID)).thenReturn(Optional.of(new UploadBatchProvisioningClaim(
                BATCH_ID, USERNAME, PUBLIC_KEY, Instant.now().plusSeconds(60), 1, 10, 1)));
        when(stateService.markOpen(BATCH_ID)).thenReturn(false);

        assertFalse(provisioningService.provision(BATCH_ID));

        verify(managementService).deleteUser(USERNAME);
    }

    @Test
    void persistsProvisioningFailureForRetry() {
        when(stateService.claim(BATCH_ID)).thenReturn(Optional.of(new UploadBatchProvisioningClaim(
                BATCH_ID, USERNAME, PUBLIC_KEY, Instant.now().plusSeconds(60), 1, 10, 1)));
        org.mockito.Mockito.doThrow(new SftpGoIntegrationException("SFTPGo create user failed (HTTP 503)"))
                .when(managementService).ensureUser(any());

        assertFalse(provisioningService.provision(BATCH_ID));

        verify(stateService).recordFailure(BATCH_ID, "SFTPGo create user failed (HTTP 503)");
        verify(stateService, never()).markOpen(BATCH_ID);
    }
}