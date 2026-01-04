package no.metatrack.server.auth;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api")
public class UserController {

    @Inject
    UserService userService;

    @GET
    @Path(("/whoami"))
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public UserResponse getUserInfo() {
        var user = userService.requireCurrentUser();
        return new UserResponse(user.id(), user.name(), user.roles());
    }
}
