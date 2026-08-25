package no.metatrack.server.project;

import java.util.UUID;

public record ProjectMemberResponse(UUID memberId, String username, String email, ProjectRole role) {
}
