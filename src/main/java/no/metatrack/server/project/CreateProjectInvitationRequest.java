package no.metatrack.server.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Invitation recipient and offered project role")
public record CreateProjectInvitationRequest(
        @Schema(description = "Recipient email, trimmed and normalized to lowercase", format = "email", required = true, maxLength = 254)
        @NotBlank @Size(max = 254) String email,
        @Schema(description = "Role granted on acceptance; OWNER is forbidden", enumeration = {"VIEWER", "EDITOR", "ADMIN"}, required = true)
        @NotNull ProjectRole role) {}