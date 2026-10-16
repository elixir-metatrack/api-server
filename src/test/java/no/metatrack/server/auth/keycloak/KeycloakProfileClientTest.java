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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
                assertEquals(List.of("one", "two"), result.getEntity().attributes().get("unrelated"));
            }
            try (var result = client.getUser("realm", "subject")) {
                assertEquals("user@example.org", result.getEntity().email());
                assertEquals(true, result.getEntity().emailVerified());
            }
            try (var result = client.updateProfile("realm", "subject", new KeycloakProfileUpdate(Map.of("firstName", "", "attributes", Map.of("orcid", List.of()))))) {
                assertEquals(204, result.getStatus());
                assertEquals(mapper.readTree("{\"firstName\":\"\",\"attributes\":{\"orcid\":[]}}"), mapper.readTree(body.get()));
            }
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
}