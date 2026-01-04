package no.metatrack.server.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;

@RequestScoped
public class UserService {

    @Inject
    SecurityIdentity identity;

    public CurrentUser requireCurrentUser() {
        if (identity.isAnonymous()) {
            throw new WebApplicationException("User not authenticated", 401);
        }

        var principal = identity.getPrincipal();
        if (!(principal instanceof JsonWebToken jwt)) {
            throw new WebApplicationException("Unsupported principal", 401);
        }

        return new CurrentUser(jwt.getSubject(), principal.getName(), identity.getRoles());
    }
}
