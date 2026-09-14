package no.metatrack.server.auth.keycloak;

import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

@Path("/admin/realms/{realm}/users")
@RegisterRestClient(configKey = "keycloak-admin-api")
@OidcClientFilter("keycloak-admin")
public interface KeycloakAdminClient {

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