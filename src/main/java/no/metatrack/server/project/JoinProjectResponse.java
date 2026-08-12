package no.metatrack.server.project;

import java.util.UUID;

public record JoinProjectResponse(long projectId, UUID userId, String username, ProjectRole role) {
}
