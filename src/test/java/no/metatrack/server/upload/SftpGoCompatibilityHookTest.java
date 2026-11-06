package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpGoCompatibilityHookTest {
    private static final String SECRET = "local-test-hook-secret-with-at-least-32-characters";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private SftpGoCompatibilityHook hook;

    @BeforeEach
    void startHook() throws Exception {
        hook = new SftpGoCompatibilityHook(SECRET, "127.0.0.1", 0);
        hook.start();
    }

    @AfterEach
    void stopHook() {
        hook.close();
    }

    @Test
    void requiresAuthenticationAndRejectsOversizedRequests() throws Exception {
        var unauthorized = postEvent("wrong-secret", "pre-upload", "batch-a", "/reads.fastq");
        assertEquals(401, unauthorized.statusCode());

        var health = get("/healthz");
        assertEquals(200, health.statusCode());
        assertEquals(0, objectMapper.readTree(health.body()).path("active_transfers").asInt());

        String largePath = "a".repeat(65_537);
        ObjectNode event = objectMapper.createObjectNode();
        event.put("event", "pre-upload");
        event.put("username", "batch-a");
        event.put("path", largePath);
        var oversized = postJson("/api/internal/sftpgo/events", authorization(SECRET), event.toString());
        assertEquals(400, oversized.statusCode());
    }

    @Test
    void enforcesPerBatchAndGlobalAdmissionLimits() throws Exception {
        assertEquals(200, postEvent(SECRET, "pre-upload", "batch-a", "/one.fastq").statusCode());
        assertEquals(200, postEvent(SECRET, "pre-upload", "batch-a", "/two.fastq").statusCode());
        assertEquals(403, postEvent(SECRET, "pre-upload", "batch-a", "/three.fastq").statusCode());

        for (int user = 0; user < 3; user++) {
            for (int transfer = 0; transfer < 2; transfer++) {
                assertEquals(200, postEvent(SECRET, "pre-upload", "batch-" + user,
                        "/file-" + transfer + ".fastq").statusCode());
            }
        }
        assertEquals(8, snapshot().path("active_transfers").asInt());
        assertEquals(403, postEvent(SECRET, "pre-upload", "batch-overflow", "/last.fastq").statusCode());
    }

    @Test
    void decodesVirtualPathsAndReleasesReservationsOnCompletionOrTestControl() throws Exception {
        assertEquals(200, postEvent(SECRET, "pre-upload", "batch-a", "/reads%2Fread%2B1.fastq").statusCode());
        assertEquals(1, snapshot().path("active_transfers").asInt());
        assertEquals(200, postEvent(SECRET, "upload", "batch-a", "/reads%2Fread%2B1.fastq").statusCode());
        assertEquals(0, snapshot().path("active_transfers").asInt());

        assertEquals(200, postEvent(SECRET, "pre-upload", "batch-a", "/resumable.fastq").statusCode());
        ObjectNode release = objectMapper.createObjectNode();
        release.put("username", "batch-a");
        release.put("path", "resumable.fastq");
        var released = postJson("/test/release-reservation", authorization(SECRET), release.toString());
        assertEquals(200, released.statusCode());
        assertEquals("released", objectMapper.readTree(released.body()).path("status").asText());
        assertEquals(0, snapshot().path("active_transfers").asInt());
    }

    @Test
    void rejectsTraversalAndUnsupportedEvents() throws Exception {
        assertEquals(400, postEvent(SECRET, "pre-upload", "batch-a", "/../outside.fastq").statusCode());
        assertEquals(400, postEvent(SECRET, "pre-delete", "batch-a", "/reads.fastq").statusCode());
        assertTrue(snapshot().path("users").isArray());
        assertEquals(0, snapshot().path("active_transfers").asInt());
    }

    private HttpResponse<String> postEvent(String secret, String eventName, String username, String path)
            throws Exception {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("event", eventName);
        event.put("username", username);
        event.put("path", path);
        return postJson("/api/internal/sftpgo/events", authorization(secret), event.toString());
    }

    private HttpResponse<String> postJson(String path, String authorization, String json) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(hook.localUrl(path)))
                        .header("Authorization", authorization)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(hook.localUrl(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode snapshot() throws Exception {
        var response = get("/healthz");
        assertEquals(200, response.statusCode());
        return objectMapper.readTree(response.body());
    }

    private String authorization(String secret) {
        String credentials = SftpGoCompatibilityHook.USERNAME + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}