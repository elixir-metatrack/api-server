package no.metatrack.server.file;

import java.time.Instant;

public record PresignResponse(String url, String objectKey, int expiresIn, Instant expiresAt) {}
