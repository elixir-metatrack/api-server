package no.metatrack.server.auth;

import java.util.Set;

public record UserResponse(String userId, String username, Set<String> roles) {}
