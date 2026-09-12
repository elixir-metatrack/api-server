package no.metatrack.server.auth;

import java.util.Objects;
import java.util.UUID;

public record VerifiedEmailIdentity(UUID subject, String email) {
    public VerifiedEmailIdentity {
        Objects.requireNonNull(subject, "Subject is required");
        email = EmailAddress.normalize(email);
    }
}