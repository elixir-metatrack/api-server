package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestResponse;

import java.util.List;

@Path("/api/v2")
@RegisterRestClient(configKey = "sftpgo-api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SftpGoAdminClient {

    @GET
    @Path("/token")
    RestResponse<SftpGoToken> token(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

    @GET
    @Path("/users/{username}")
    RestResponse<JsonNode> getUser(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                   @PathParam("username") String username);

    @POST
    @Path("/users")
    RestResponse<JsonNode> createUser(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                      JsonNode user);

    @PUT
    @Path("/users/{username}")
    RestResponse<JsonNode> updateUser(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                      @PathParam("username") String username,
                                      JsonNode user);

    @DELETE
    @Path("/users/{username}")
    RestResponse<JsonNode> deleteUser(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                      @PathParam("username") String username);

    @GET
    @Path("/connections")
    RestResponse<List<SftpGoConnection>> connections(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

    @GET
    @Path("/eventactions")
    RestResponse<JsonNode> eventActions(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                        @QueryParam("offset") int offset,
                                        @QueryParam("limit") int limit);

    @POST
    @Path("/eventactions")
    RestResponse<JsonNode> createEventAction(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                             JsonNode action);

    @PUT
    @Path("/eventactions/{name}")
    RestResponse<JsonNode> updateEventAction(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                             @PathParam("name") String name,
                                             JsonNode action);

    @GET
    @Path("/eventrules")
    RestResponse<JsonNode> eventRules(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                      @QueryParam("offset") int offset,
                                      @QueryParam("limit") int limit);

    @POST
    @Path("/eventrules")
    RestResponse<JsonNode> createEventRule(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                                           JsonNode rule);
}