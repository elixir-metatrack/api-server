package no.metatrack.server.auth;

import java.util.Set;

public record CurrentUser(String id, String name, Set<String> roles) {}
