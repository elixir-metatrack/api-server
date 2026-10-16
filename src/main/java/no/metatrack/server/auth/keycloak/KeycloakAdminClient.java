package no.metatrack.server.auth.keycloak;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

@Path("/admin/realms/{realm}/users")
@RegisterRestClient(configKey = "keycloak-admin-api")
@OidcClientFilter("keycloak-admin")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface KeycloakAdminClient {

    @PUT
    @Path("/{userId}")
    RestResponse<Void> updateProfile(@PathParam("realm") String realm,
                                     @PathParam("userId") String userId, KeycloakProfileUpdate update);

    @GET
    @Path("/{userId}")
    RestResponse<KeycloakProfileRepresentation> getProfile(
            @PathParam("realm") String realm,
            @PathParam("userId") String userId);

    @GET
    RestResponse<List<KeycloakUserRepresentation>> searchByEmail(
            @PathParam("realm") String realm,
            @QueryParam("email") String email,
            @QueryParam("exact") boolean exact
    );

    @GET
    @Path("/{userId}")
    RestResponse<KeycloakUserRepresentation> getUser(
            @PathParam("realm") String realm,
            @PathParam("userId") String userId
    );
}