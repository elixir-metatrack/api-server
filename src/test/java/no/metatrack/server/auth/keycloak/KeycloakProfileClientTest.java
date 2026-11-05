package no.metatrack.server.auth.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ProfileClientTestProfile.class)
class KeycloakProfileClientTest {
    // Exercise the same HTTP methods without the deployment-only OIDC filter.
    @Path("/admin/realms/{realm}/users")
    public interface LocalClient extends KeycloakAdminClient {}

    @Test
    void serializesMinimalUpdatesAndDeserializesProfilesAndExistingLookups() throws Exception {
        var mapper = new ObjectMapper();
        var body = new AtomicReference<String>();
        var status = new AtomicInteger(200);
        var writeStatus = new AtomicInteger(204);
        var malformed = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/realms/realm/users/subject", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (exchange.getRequestMethod().equals("PUT")) {
                exchange.sendResponseHeaders(writeStatus.get(), -1);
            } else {
                byte[] json = (malformed.get() ? "{broken" : "{\"id\":\"subject\",\"username\":\"live\",\"firstName\":\"Ada\",\"lastName\":null,"
                        + "\"email\":\"user@example.org\",\"emailVerified\":true,\"enabled\":true,"
                        + "\"attributes\":{\"country\":[\"NO\"],\"unrelated\":[\"one\",\"two\"]}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), json.length);
                exchange.getResponseBody().write(json);
            }
            exchange.close();
        });
        server.start();
        try {
            var client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .property("microprofile.rest.client.disable.default.mapper", true)
                    .build(LocalClient.class);
            try (var result = client.getProfile("realm", "subject")) {
                assertEquals("Ada", result.getEntity().firstName());
                assertEquals("user@example.org", result.getEntity().email());
                assertEquals(List.of("one", "two"), result.getEntity().attributes().get("unrelated"));
            }
            try (var result = client.getUser("realm", "subject")) {
                assertEquals("user@example.org", result.getEntity().email());
                assertEquals(true, result.getEntity().emailVerified());
            }
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("username", "live");
            update.put("email", null);
            update.put("firstName", "");
            update.put("lastName", "Name");
            update.put("attributes", Map.of("orcid", List.of()));
            try (var result = client.updateProfile("realm", "subject", new KeycloakProfileUpdate(update))) {
                assertEquals(204, result.getStatus());
                assertEquals(mapper.readTree("{\"username\":\"live\",\"email\":null,\"firstName\":\"\",\"lastName\":\"Name\",\"attributes\":{\"orcid\":[]}}"), mapper.readTree(body.get()));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new KeycloakProfileUpdate(Map.of("roles", List.of("system-admin"))));
            for (int code : new int[]{400, 401, 403, 404, 409, 500}) {
                status.set(code);
                try (var result = client.getProfile("realm", "subject")) {
                    assertEquals(code, result.getStatus());
                }
                writeStatus.set(code);
                try (var result = client.updateProfile("realm", "subject", new KeycloakProfileUpdate(Map.of("lastName", "Name")))) {
                    assertEquals(code, result.getStatus());
                }
            }
            status.set(200);
            malformed.set(true);
            assertThrows(RuntimeException.class, () -> {
                try (var result = client.getProfile("realm", "subject")) {
                    result.getEntity();
                }
            });
            ((AutoCloseable) client).close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generalSearchEncodesQueryAndUsesBoundedParameters() throws Exception {
        var requestQuery = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/realms/realm/users", exchange -> {
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] json = ("[{\"id\":\"" + UUID.randomUUID()
                    + "\",\"username\":\"ada@example.org\",\"email\":\"ada@example.org\","
                    + "\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();
        try {
            var client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .property("microprofile.rest.client.disable.default.mapper", true)
                    .build(LocalClient.class);
            try (var response = client.searchUsers("realm", "ada team+タグ@example.org", false, 0, 21)) {
                assertEquals(200, response.getStatus());
                assertEquals("Ada", response.getEntity().getFirst().firstName());
            } finally {
                ((AutoCloseable) client).close();
            }

            Map<String, String> parameters = new LinkedHashMap<>();
            for (String entry : requestQuery.get().split("&")) {
                String[] pair = entry.split("=", 2);
                parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertEquals("ada team+タグ@example.org", parameters.get("search"));
            assertEquals("false", parameters.get("exact"));
            assertEquals("0", parameters.get("first"));
            assertEquals("21", parameters.get("max"));
        } finally {
            server.stop(0);
        }
    }
}