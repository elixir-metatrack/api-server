package no.metatrack.server.invitation;

import io.quarkus.runtime.Startup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.ProjectInvitation;
import no.metatrack.server.project.ProjectInvitationResponse;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Startup
@ApplicationScoped
public class InvitationDeliveryTransactions {
    private final EntityManager entityManager;
    private final ProjectService projects;
    private final Duration cooldown;
    private final Duration sendingTimeout;
    private final Clock clock;

    @Inject
    public InvitationDeliveryTransactions(EntityManager entityManager, ProjectService projects,
            @ConfigProperty(name = "metatrack.invitation.resend-cooldown", defaultValue = "PT60S") Duration cooldown,
            @ConfigProperty(name = "metatrack.invitation.sending-timeout", defaultValue = "PT5M") Duration sendingTimeout) {
        this(entityManager, projects, cooldown, sendingTimeout, Clock.systemUTC());
    }

    InvitationDeliveryTransactions(EntityManager entityManager, ProjectService projects, Duration cooldown,
                                   Duration sendingTimeout, Clock clock) {
        if (cooldown.isNegative() || cooldown.isZero() || sendingTimeout.compareTo(cooldown) < 0) {
            throw new IllegalArgumentException("Sending timeout must be at least the positive resend cooldown");
        }
        this.entityManager = entityManager;
        this.projects = projects;
        this.cooldown = cooldown;
        this.sendingTimeout = sendingTimeout;
        this.clock = clock;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Envelope prepare(Long projectId, UUID id, UUID caller) {
        var invitation = authorized(projectId, id, caller);
        requireCooldownElapsed(invitation, now());
        return envelope(invitation);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Attempt claim(Long projectId, UUID id, UUID caller) {
        var invitation = authorized(projectId, id, caller);
        Instant now = now();
        requireCooldownElapsed(invitation, now);
        invitation.deliveryStatus = DeliveryStatus.SENDING;
        invitation.deliveryAttemptId = UUID.randomUUID();
        invitation.deliveryAttemptedOn = now;
        invitation.deliveryCompletedOn = null;
        invitation.deliveryRetryAfter = now.plus(sendingTimeout);
        return new Attempt(invitation.deliveryAttemptId, envelope(invitation),
                ProjectInvitationResponse.from(invitation, now));
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public ProjectInvitationResponse complete(UUID id, UUID attemptId, DeliveryStatus outcome) {
        if (outcome != DeliveryStatus.SENT && outcome != DeliveryStatus.FAILED && outcome != DeliveryStatus.UNKNOWN) {
            throw new IllegalArgumentException("Invalid delivery outcome");
        }
        var invitation = entityManager.find(ProjectInvitation.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (invitation == null) {
            throw new NotFoundException();
        }
        Instant now = now();
        // A late SMTP completion must never overwrite a newer explicit attempt.
        if (attemptId.equals(invitation.deliveryAttemptId) && invitation.deliveryStatus == DeliveryStatus.SENDING) {
            invitation.deliveryStatus = outcome;
            invitation.deliveryCompletedOn = now;
            invitation.deliveryRetryAfter = now.plus(cooldown);
        }
        return ProjectInvitationResponse.from(invitation, now);
    }

    private ProjectInvitation authorized(Long projectId, UUID id, UUID caller) {
        projects.lockProject(projectId);
        ProjectRole role = entityManager.createQuery("select role from ProjectMember where project.id = :project and memberId = :caller", ProjectRole.class)
                .setParameter("project", projectId).setParameter("caller", caller)
                .getResultStream().findFirst().orElseThrow(ForbiddenException::new);
        if (role != ProjectRole.ADMIN && role != ProjectRole.OWNER) {
            throw new ForbiddenException();
        }
        var invitation = entityManager.createQuery("from ProjectInvitation where project.id = :project and id = :id", ProjectInvitation.class)
                .setParameter("project", projectId).setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElseThrow(NotFoundException::new);
        if (!invitation.isActionable(now())) {
            throw new WebApplicationException("Invitation is not pending or has expired", 409);
        }
        return invitation;
    }

    private Envelope envelope(ProjectInvitation invitation) {
        return new Envelope(invitation.recipientEmail, invitation.recipientId != null, invitation.inviterDisplayName,
                invitation.project.name, invitation.role, invitation.expiresOn);
    }

    private void requireCooldownElapsed(ProjectInvitation invitation, Instant now) {
        if (invitation.deliveryRetryAfter != null && now.isBefore(invitation.deliveryRetryAfter)) {
            throw new WebApplicationException("Invitation delivery cooldown is active", 429);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public record Envelope(String email, boolean registered, String inviter, String project, ProjectRole role, Instant expiresOn) {}
    public record Attempt(UUID id, Envelope envelope, ProjectInvitationResponse response) {}
}