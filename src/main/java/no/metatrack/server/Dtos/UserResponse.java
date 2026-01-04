package no.metatrack.server.Dtos;

import java.util.Set;

public record UserResponse(String userId, String name, Set<String> roles) {}
