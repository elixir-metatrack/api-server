package no.metatrack.server.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.io.IOException;

@JsonDeserialize(using = UserProfileRequest.Deserializer.class)
@Schema(description = "Partial profile patch. Omit fields to leave them unchanged; use null or a blank string to clear them.")
public final class UserProfileRequest {
    @Schema(hidden = true)
    private final UserProfilePatch patch;
    @Schema(hidden = true)
    private final WebApplicationException failure;

    private UserProfileRequest(UserProfilePatch patch, WebApplicationException failure) {
        this.patch = patch;
        this.failure = failure;
    }

    public UserProfilePatch toPatch() {
        if (failure != null) {
            throw failure;
        }
        return patch;
    }

    @Schema(description = "Optional given name; null or blank clears it", nullable = true)
    public String getFirstName() {
        return value("firstName");
    }

    @Schema(description = "Optional family name; null or blank clears it", nullable = true)
    public String getLastName() {
        return value("lastName");
    }

    @Schema(description = "Optional uppercase ISO 3166-1 alpha-2 country code", nullable = true)
    public String getCountry() {
        return value("country");
    }

    @Schema(description = "Optional institution name", nullable = true)
    public String getInstitution() {
        return value("institution");
    }

    @Schema(description = "Optional checksum-valid ORCID identifier or canonical URL", nullable = true)
    public String getOrcid() {
        return value("orcid");
    }

    private String value(String field) {
        return patch == null ? null : patch.fields().get(field);
    }

    public static final class Deserializer extends JsonDeserializer<UserProfileRequest> {
        @Override
        public UserProfileRequest getNullValue(DeserializationContext context) {
            try {
                UserProfilePatch.parse(null);
            } catch (WebApplicationException exception) {
                return new UserProfileRequest(null, exception);
            }
            throw new IllegalStateException("Null profile patches must be rejected");
        }

        @Override
        public UserProfileRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode body = parser.getCodec().readTree(parser);
            try {
                return new UserProfileRequest(UserProfilePatch.parse(body), null);
            } catch (WebApplicationException exception) {
                return new UserProfileRequest(null, exception);
            }
        }
    }
}