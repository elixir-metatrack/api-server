package no.metatrack.server.upload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SftpGoEventResourceTest {
    private static final String SECRET = "local-test-hook-secret-with-32-chars";

    private SftpGoConfig config;
    private SftpGoUploadPolicy policy;
    private SftpGoEventResource resource;

    @BeforeEach
    void setUp() {
        config = mock(SftpGoConfig.class);
        policy = mock(SftpGoUploadPolicy.class);
        when(config.enabled()).thenReturn(true);
        when(config.hookSecret()).thenReturn(java.util.Optional.of(SECRET));
        resource = new SftpGoEventResource(config, policy);
    }

    @Test
    void authenticatesAndPassesDecodedRelativePathToPolicy() {
        when(policy.permits(any())).thenReturn(true);

        var response = resource.receive(basicAuthorization(SECRET),
                new SftpGoHookEvent("pre-upload", "batch-uuid", "/reads%2Fread%2B1.fastq", 0, 0));

        assertEquals(200, response.getStatus());
        var event = org.mockito.ArgumentCaptor.forClass(SftpGoHookEvent.class);
        verify(policy).permits(event.capture());
        assertEquals("reads/read+1.fastq", event.getValue().path());
        assertEquals("batch-uuid", event.getValue().username());
    }

    @Test
    void rejectsWrongSecretBeforeCallingPolicy() {
        var response = resource.receive(basicAuthorization("wrong-secret"),
                new SftpGoHookEvent("pre-upload", "batch-uuid", "/reads.fastq", 0, 0));

        assertEquals(401, response.getStatus());
        verify(policy, never()).permits(any());
    }

    @Test
    void rejectsTraversalAndMalformedEvents() {
        var traversal = resource.receive(basicAuthorization(SECRET),
                new SftpGoHookEvent("pre-upload", "batch-uuid", "/../outside.fastq", 0, 0));
        var malformed = resource.receive(basicAuthorization(SECRET),
                new SftpGoHookEvent("pre-delete", "batch-uuid", "/reads.fastq", 0, 0));

        assertEquals(400, traversal.getStatus());
        assertEquals(400, malformed.getStatus());
        verify(policy, never()).permits(any());
    }

    @Test
    void failsClosedWhenIntegrationOrPolicyIsUnavailable() {
        when(config.enabled()).thenReturn(false);
        var disabled = resource.receive(basicAuthorization(SECRET),
                new SftpGoHookEvent("pre-upload", "batch-uuid", "/reads.fastq", 0, 0));
        when(config.enabled()).thenReturn(true);
        when(policy.permits(any())).thenReturn(false);
        var denied = resource.receive(basicAuthorization(SECRET),
                new SftpGoHookEvent("pre-upload", "batch-uuid", "/reads.fastq", 0, 0));

        assertEquals(503, disabled.getStatus());
        assertEquals(403, denied.getStatus());
    }

    @Test
    void returnsServiceUnavailableWhenAdmissionDependenciesFail() {
        when(policy.permits(any())).thenThrow(new SftpGoIntegrationException("SFTPGo unavailable"));

        var response = resource.receive(basicAuthorization(SECRET),
                new SftpGoHookEvent("pre-upload", "batch-uuid", "/reads.fastq", 0, 0));

        assertEquals(503, response.getStatus());
    }

    private String basicAuthorization(String secret) {
        String credentials = "metatrack-sftpgo-hook:" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}