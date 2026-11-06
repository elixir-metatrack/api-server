package no.metatrack.server.upload;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SftpGoToken(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_at") Instant expiresAt
) {
}