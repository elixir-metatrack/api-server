package no.metatrack.server.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@RequestScoped
public class UserService {

    @Inject
    SecurityIdentity identity;

    public VerifiedEmailIdentity requireVerifiedEmailIdentity() {
        requireCurrentUser();
        JsonWebToken jwt = (JsonWebToken) identity.getPrincipal();
        Object email = jwt.getClaim("email");
        if (!Boolean.TRUE.equals(jwt.getClaim("email_verified")) || !(email instanceof String address)) {
            throw new WebApplicationException("Verified email required", 403);
        }
        try {
            UUID subject = UUID.fromString(jwt.getSubject());
            if (!subject.toString().equalsIgnoreCase(jwt.getSubject())) {
                throw new IllegalArgumentException("Invalid subject");
            }
            return new VerifiedEmailIdentity(subject, address);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new WebApplicationException("Invalid email identity", 403);
        }
    }

    public CurrentUser requireCurrentUser() {
        if (identity.isAnonymous()) {
            throw new WebApplicationException("User not authenticated", 401);
        }

        var principal = identity.getPrincipal();
        if (!(principal instanceof JsonWebToken jwt)) {
            throw new WebApplicationException("Unsupported principal", 401);
        }

        return new CurrentUser(
                jwt.getSubject(),
                principal.getName(),
                identity.getRoles(),
                jwt.getClaim("country"),
                jwt.getClaim("institution"),
                jwt.getClaim("orcid"));
    }
}
