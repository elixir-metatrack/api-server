package no.metatrack.server.auth;

import java.util.Objects;

public record UserSearchResult(String userId, String firstName, String lastName, String displayName, String email) {
    public UserSearchResult {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(displayName);
    }
}