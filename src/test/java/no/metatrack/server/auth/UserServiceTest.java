package no.metatrack.server.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    @Test
    void requiresBooleanVerificationAndValidEmailWithoutChangingCurrentUser() {
        UserService service = new UserService();
        service.identity = mock(SecurityIdentity.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(service.identity.getPrincipal()).thenReturn(jwt);
        UUID subject = UUID.randomUUID();
        when(jwt.getSubject()).thenReturn(subject.toString());
        when(jwt.getClaim("email")).thenReturn(" Invitee+Tag@Example.org ");
        when(jwt.getClaim("email_verified")).thenReturn(true);
        assertEquals(new VerifiedEmailIdentity(subject, "invitee+tag@example.org"), service.requireVerifiedEmailIdentity());
        for (Object verification : Arrays.asList(null, false, "true", 1)) {
            when(jwt.getClaim("email_verified")).thenReturn(verification);
            assertEquals(403, assertThrows(WebApplicationException.class, service::requireVerifiedEmailIdentity).getResponse().getStatus());
            assertNotNull(service.requireCurrentUser());
        }
        when(jwt.getClaim("email_verified")).thenReturn(true);
        for (Object email : Arrays.asList(null, "", "  ", "invalid", 42)) {
            when(jwt.getClaim("email")).thenReturn(email);
            assertEquals(403, assertThrows(WebApplicationException.class, service::requireVerifiedEmailIdentity).getResponse().getStatus());
        }
    }

    @Test
    void rejectsAnonymousAndNonJwtPrincipals() {
        UserService service = new UserService();
        service.identity = mock(SecurityIdentity.class);
        when(service.identity.isAnonymous()).thenReturn(true);
        assertEquals(401, assertThrows(WebApplicationException.class, service::requireVerifiedEmailIdentity).getResponse().getStatus());
        when(service.identity.isAnonymous()).thenReturn(false);
        when(service.identity.getPrincipal()).thenReturn(() -> "user");
        assertEquals(401, assertThrows(WebApplicationException.class, service::requireVerifiedEmailIdentity).getResponse().getStatus());
    }

    @Test
    void rejectsMissingMalformedAndNonCanonicalSubjects() {
        UserService service = new UserService();
        service.identity = mock(SecurityIdentity.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(service.identity.getPrincipal()).thenReturn(jwt);
        when(jwt.getClaim("email")).thenReturn("invitee@example.org");
        when(jwt.getClaim("email_verified")).thenReturn(true);
        for (String subject : Arrays.asList(null, "", "not-a-uuid", "1-1-1-1-1")) {
            when(jwt.getSubject()).thenReturn(subject);
            assertEquals(403, assertThrows(WebApplicationException.class, service::requireVerifiedEmailIdentity).getResponse().getStatus());
        }
    }

    @Test
    void normalizationIsLocaleIndependentAndPreservesAliases() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("i.name+tag@example.org", EmailAddress.normalize(" I.Name+Tag@Example.ORG "));
        } finally {
            Locale.setDefault(original);
        }
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.normalize("a b@example.org"));
    }
}