package no.metatrack.server.auth;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ProfileFieldValidatorTest {
    String normalize(String field, String value) {
        return ProfileFieldValidator.normalize(new UserProfilePatch(Collections.singletonMap(field, value))).fields().get(field);
    }

    @Test
    void trimsClearsAndNormalizesOrcid() {
        for (String field : UserProfilePatch.ALLOWED) {
            assertNull(normalize(field, null));
            assertNull(normalize(field, " \t "));
            assertNull(normalize(field, ""));
        }
        assertEquals("Ada", normalize("firstName", " Ada "));
        assertEquals("NO", normalize("country", " NO "));
        assertEquals("0000-0002-1825-0097", normalize("orcid", " https://orcid.org/0000-0002-1825-0097 "));
        assertEquals("0000-0002-1694-233X", normalize("orcid", "0000-0002-1694-233X"));
    }

    @Test
    void enforcesLengthsCountryCodesAndChecksums() {
        for (String field : new String[]{"firstName", "lastName", "institution"}) {
            int limit = field.equals("institution") ? 512 : 255;
            assertEquals(limit, normalize(field, "a".repeat(limit)).length());
            assertThrows(WebApplicationException.class, () -> normalize(field, "a".repeat(limit + 1)));
        }
        for (String country : new String[]{"no", "ZZ", "NOR", "UK"}) {
            assertThrows(WebApplicationException.class, () -> normalize("country", country));
        }
        for (String orcid : new String[]{"0000-0002-1825-0098", "https://evil.test/0000-0002-1825-0097", "0000000218250097"}) {
            assertThrows(WebApplicationException.class, () -> normalize("orcid", orcid));
        }
    }
}