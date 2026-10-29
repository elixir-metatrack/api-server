package no.metatrack.server.file;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Temporary object-storage URL and its expiry information.")
public record PresignResponse(
        @Schema(description = "Temporary signed object-storage URL", format = "uri") String url,
        @Schema(description = "Object-storage key addressed by the URL") String objectKey,
        @Schema(description = "URL lifetime in seconds") int expiresIn,
        @Schema(description = "Time at which the URL expires", format = "date-time") Instant expiresAt) {}
