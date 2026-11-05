package no.metatrack.server.auth;

import java.util.List;

public record UserSearchResponse(List<UserSearchResult> users, boolean hasMore) {
    public UserSearchResponse {
        users = List.copyOf(users);
    }
}