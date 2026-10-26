package no.metatrack.server.auth;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

@Path("/api")
@APIResponses({
        @APIResponse(responseCode = "401", description = "Authentication required"),
        @APIResponse(responseCode = "404", description = "Current subject no longer exists in Keycloak"),
        @APIResponse(responseCode = "502", description = "Keycloak unavailable, unauthorized service account, or invalid upstream response; no stale-token fallback")
})
public class UserController {

    @Inject
    UserService userService;

    @Inject
    UserProfileService profileService;

    @PATCH
    @Path("/whoami")
    @Authenticated
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Edit your own profile", description = "Only firstName, lastName, country, institution and orcid are editable. "
            + "Omitted fields remain unchanged; null or blank strings clear fields where realm policy permits. "
            + "Names are limited to 255 characters and institution to 512; text is trimmed. Country must be an uppercase ISO 3166-1 alpha-2 code. "
            + "ORCID must be a checksum-valid hyphenated identifier or canonical https://orcid.org/ URL and is stored as an identifier. "
            + "Unknown fields and non-string/non-null values are rejected. An empty object performs a live read without writing. "
            + "Returns a post-write live read; roles remain token-derived. Re-read before retrying an uncertain timeout or failed post-write read; writes are not retried automatically.")
    @RequestBody(required = true, description = "Partial profile object; no target ID or protected account fields are accepted",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = UserProfileRequest.class),
                    examples = @ExampleObject(value = "{\"firstName\":\"Ada\",\"country\":\"NO\",\"orcid\":null}")))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Saved live profile", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @APIResponse(responseCode = "400", description = "Malformed patch, invalid field, or Keycloak realm profile-validation rejection; sanitized feedback"),
            @APIResponse(responseCode = "409", description = "Keycloak edit conflict")
    })
    public UserResponse updateProfile(UserProfileRequest body) {
        var user = userService.requireCurrentUser();
        return profileService.updateProfile(user, body == null ? UserProfilePatch.parse(null) : body.toPatch());
    }

    @GET
    @Path(("/whoami"))
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read your live profile", description = "Reads username, firstName, lastName, country, institution and orcid "
            + "uncached from Keycloak. Roles remain token-derived. Profile fields may be null. Requires Keycloak availability; "
            + "does not rewrite access tokens or change project authorization identity.")
    @APIResponse(responseCode = "200", description = "Current live profile", content = @Content(schema = @Schema(implementation = UserResponse.class)))
    public UserResponse getUserInfo() {
        var user = userService.requireCurrentUser();
        return profileService.getProfile(user);
    }
}
