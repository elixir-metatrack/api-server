package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.auth.EmailAddress;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.keycloak.IdentityLookupService;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProjectInvitationService {
    private final UserService userService;
    private final ProjectRoleCheck roleCheck;
    private final IdentityLookupService identities;
    private final ProjectInvitationTransactions transactions;

    public ProjectInvitationService(UserService userService, ProjectRoleCheck roleCheck,
                                    IdentityLookupService identities, ProjectInvitationTransactions transactions) {
        this.userService = userService;
        this.roleCheck = roleCheck;
        this.identities = identities;
        this.transactions = transactions;
    }

    public ProjectInvitationResponse create(Long projectId, CreateProjectInvitationRequest request) {
        var inviter = userService.requireCurrentUser();
        requireAdmin(projectId);
        if (request == null || request.role() == null || request.role() == ProjectRole.OWNER) {
            throw new BadRequestException("Invitation role must be VIEWER, EDITOR or ADMIN");
        }
        String email;
        try {
            email = EmailAddress.normalize(request.email());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid email");
        }
        // Resolve outside the write transaction, only after project authorization and role validation.
        UUID recipient = identities.findByEmail(email).map(user -> UUID.fromString(user.id())).orElse(null);
        return transactions.create(projectId, email, recipient, UUID.fromString(inviter.id()), inviter.name(), request.role());
    }

    public List<ProjectInvitationResponse> list(Long projectId, int page, int size) {
        userService.requireCurrentUser();
        requireAdmin(projectId);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BadRequestException("Invalid pagination");
        }
        return transactions.list(projectId, UUID.fromString(userService.requireCurrentUser().id()), page, size);
    }

    public ProjectInvitationResponse revoke(Long projectId, UUID id) {
        var caller = userService.requireCurrentUser();
        requireAdmin(projectId);
        return transactions.revoke(projectId, id, UUID.fromString(caller.id()));
    }

    public ProjectInvitationResponse accept(UUID id) {
        return transactions.decide(id, userService.requireVerifiedEmailIdentity(), InvitationStatus.ACCEPTED);
    }

    public ProjectInvitationResponse decline(UUID id) {
        return transactions.decide(id, userService.requireVerifiedEmailIdentity(), InvitationStatus.DECLINED);
    }

    private void requireAdmin(Long projectId) {
        if (!roleCheck.isAtLeast(projectId, ProjectRole.ADMIN)) {
            throw new ForbiddenException();
        }
    }
}