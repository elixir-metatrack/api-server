package no.metatrack.server.auth;

import java.util.Locale;

public final class EmailAddress {
    private EmailAddress() {
    }

    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+")) {
            throw new IllegalArgumentException("Invalid email");
        }
        return normalized;
    }
}