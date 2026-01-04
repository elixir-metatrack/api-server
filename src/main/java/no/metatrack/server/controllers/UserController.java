package no.metatrack.server.controllers;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import no.metatrack.server.Dtos.UserResponse;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.security.Principal;
import java.util.Set;

@Path("/api")
public class UserController {
    private static final Logger log = Logger.getLogger(UserController.class);

    @Inject
    SecurityIdentity identity;

    @GET
    @Path(("/whoami"))
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public UserResponse getUser() {
        String name = identity.getPrincipal().getName();
        String userId = getUserId();
        Set<String> roles = identity.getRoles();

        log.info("/api/whoami");
        return new UserResponse(userId, name, roles);
    }

    private String getUserId() {
        Principal p = identity.getPrincipal();
        if (p instanceof JsonWebToken jwt) {
            return jwt.getSubject();
        }
        return null;
    }
}
