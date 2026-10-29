package no.metatrack.server.auth;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

@Path("/api/users")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User search", description = "Authenticated realm-wide user search for user pickers")
public class UserSearchController {

    @Inject
    UserSearchService userSearchService;

    @GET
    @Path("/search")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Search realm users", description = "Searches Keycloak names, usernames, and email. "
            + "Matching and ordering follow Keycloak; results are limited to 20 with a hasMore indicator.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Matching users", content = @Content(schema = @Schema(implementation = UserSearchResponse.class))),
            @APIResponse(responseCode = "400", description = "Missing, malformed, or out-of-range search query"),
            @APIResponse(responseCode = "401", description = "Authentication required"),
            @APIResponse(responseCode = "502", description = "Keycloak unavailable, unauthorized service account, or invalid upstream response")
    })
    public UserSearchResponse search(
            @Parameter(description = "Trimmed search term; 2 to 256 characters", required = true)
            @QueryParam("q") String query
    ) {
        return userSearchService.search(query);
    }

    @ServerResponseFilter
    public void preventDownstreamCaching(ContainerResponseContext response) {
        response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}