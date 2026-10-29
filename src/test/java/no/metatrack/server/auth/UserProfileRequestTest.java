package no.metatrack.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserProfileRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesOmittedFieldsAndExplicitNulls() throws Exception {
        var patch = mapper.readValue("{\"firstName\":null,\"country\":\"NO\"}", UserProfileRequest.class).toPatch();

        assertTrue(patch.fields().containsKey("firstName"));
        assertNull(patch.fields().get("firstName"));
        assertEquals("NO", patch.fields().get("country"));
        assertFalse(patch.fields().containsKey("lastName"));
        assertTrue(mapper.readValue("{}", UserProfileRequest.class).toPatch().fields().isEmpty());
    }

    @Test
    void rejectsUnknownFieldsAndNonStringValues() throws Exception {
        for (String json : new String[]{"{\"userId\":\"other\"}", "{\"country\":42}", "{\"orcid\":true}", "[]", "null"}) {
            var request = mapper.readValue(json, UserProfileRequest.class);
            assertEquals(400, assertThrows(WebApplicationException.class, request::toPatch).getResponse().getStatus());
        }
    }

    @Test
    void exposesOnlyTheFiveEditableStringProperties() throws Exception {
        var request = mapper.readValue("{\"firstName\":\"Ada\"}", UserProfileRequest.class);

        assertEquals("Ada", request.getFirstName());
        assertNull(request.getLastName());
        assertNull(request.getCountry());
        assertNull(request.getInstitution());
        assertNull(request.getOrcid());
        assertEquals(Map.of("firstName", "Ada"), request.toPatch().fields());
    }
}