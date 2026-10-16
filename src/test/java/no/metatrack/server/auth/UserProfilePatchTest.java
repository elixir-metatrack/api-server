package no.metatrack.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserProfilePatchTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void distinguishesOmissionAndNull() throws Exception {
        var patch = UserProfilePatch.parse(mapper.readTree("{\"firstName\":null}"));
        assertTrue(patch.fields().containsKey("firstName"));
        assertNull(patch.fields().get("firstName"));
        assertFalse(patch.fields().containsKey("lastName"));
        assertTrue(UserProfilePatch.parse(mapper.readTree("{}")).fields().isEmpty());
    }

    @Test
    void rejectsProtectedUnknownAndMalformedValues() throws Exception {
        for (String field : new String[]{"id", "userId", "roles", "email", "username", "attributes", "password", "unknown"}) {
            var body = mapper.readTree("{\"" + field + "\":null}");
            assertThrows(WebApplicationException.class, () -> UserProfilePatch.parse(body));
        }
        for (String json : new String[]{"null", "[]", "42", "{\"country\":true}", "{\"orcid\":{}}", "{\"firstName\":42}"}) {
            var body = mapper.readTree(json);
            assertEquals(400, assertThrows(WebApplicationException.class,
                    () -> UserProfilePatch.parse(body)).getResponse().getStatus());
        }
    }
}