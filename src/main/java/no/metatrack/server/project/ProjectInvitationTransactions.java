package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import no.metatrack.server.notification.Notification;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ProjectInvitationTransactions {
    private final EntityManager entityManager;
    private final ProjectService projectService;
    private final Duration expiry;
    private final Clock clock;

    @Inject
    public ProjectInvitationTransactions(EntityManager entityManager, ProjectService projectService,
            @ConfigProperty(name = "metatrack.invitation.expiry", defaultValue = "P7D") Duration expiry) {
        this(entityManager, projectService, expiry, Clock.systemUTC());
    }

    ProjectInvitationTransactions(EntityManager entityManager, ProjectService projectService, Duration expiry, Clock clock) {
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("Invitation expiry must be positive");
        }
        this.entityManager = entityManager;
        this.projectService = projectService;
        this.expiry = expiry;
        this.clock = clock;
    }

    @Transactional
    public ProjectInvitationResponse create(Long projectId, String email, UUID recipient, UUID inviter,
                                             String displayName, ProjectRole role) {
        Project project = projectService.lockProject(projectId);
        requireAuthority(projectId, inviter, role);
        if (inviter.equals(recipient) || (recipient != null && isMember(projectId, recipient))) {
            throw conflict("Recipient is already a project member");
        }
        Instant now = now();
        var pending = entityManager.createQuery("from ProjectInvitation where project.id = :project and recipientEmail = :email and status = :status", ProjectInvitation.class)
                .setParameter("project", projectId).setParameter("email", email)
                .setParameter("status", InvitationStatus.PENDING).getResultList();
        for (var invitation : pending) {
            if (!invitation.expire(now)) {
                throw conflict("An active invitation already exists");
            }
        }
        entityManager.flush();
        ProjectInvitation invitation = new ProjectInvitation();
        invitation.project = project;
        invitation.recipientEmail = email;
        invitation.recipientId = recipient;
        invitation.inviterId = inviter;
        invitation.inviterDisplayName = displayName == null ? inviter.toString() : displayName;
        invitation.role = role;
        invitation.createdOn = now;
        invitation.expiresOn = now.plus(expiry).truncatedTo(ChronoUnit.MICROS);
        entityManager.persist(invitation);
        if (recipient != null) {
            entityManager.persist(Notification.forInvitation(invitation, now));
        }
        entityManager.flush();
        return ProjectInvitationResponse.from(invitation, now);
    }

    @Transactional
    public List<ProjectInvitationResponse> list(Long projectId, UUID caller, int page, int size) {
        projectService.lockProject(projectId);
        requireAuthority(projectId, caller, ProjectRole.VIEWER);
        Instant now = now();
        return entityManager.createQuery("from ProjectInvitation where project.id = :project order by createdOn desc, id desc", ProjectInvitation.class)
                .setParameter("project", projectId).setFirstResult(page * size).setMaxResults(size)
                .getResultList().stream().map(invitation -> ProjectInvitationResponse.from(invitation, now)).toList();
    }

    @Transactional
    public ProjectInvitationResponse revoke(Long projectId, UUID id, UUID caller) {
        projectService.lockProject(projectId);
        requireAuthority(projectId, caller, ProjectRole.VIEWER);
        ProjectInvitation invitation = findLocked(id, projectId);
        Instant now = now();
        transition(invitation, InvitationStatus.REVOKED, now);
        return ProjectInvitationResponse.from(invitation, now);
    }

    @Transactional
    public ProjectInvitationResponse decide(UUID id, VerifiedEmailIdentity identity, InvitationStatus decision) {
        if (decision != InvitationStatus.ACCEPTED && decision != InvitationStatus.DECLINED) {
            throw new IllegalArgumentException("Invalid recipient decision");
        }
        // Read only the project ID before locking; never hydrate a stale invitation before waiting.
        Long projectId = entityManager.createQuery("select project.id from ProjectInvitation where id = :id", Long.class)
                .setParameter("id", id).getResultStream().findFirst().orElseThrow(NotFoundException::new);
        projectService.lockProject(projectId);
        ProjectInvitation invitation = findLocked(id, projectId);
        if (!invitation.matchesRecipient(identity)) {
            throw new NotFoundException();
        }
        Instant now = now();
        if (invitation.status == decision) {
            return ProjectInvitationResponse.from(invitation, now);
        }
        if (!invitation.isActionable(now)) {
            throw conflict("Invitation is not pending or has expired");
        }
        if (decision == InvitationStatus.ACCEPTED) {
            requireAuthority(projectId, invitation.inviterId, invitation.role);
            if (!isMember(projectId, identity.subject())) {
                projectService.addMember(projectId, identity.subject(), invitation.role);
            }
        }
        invitation.bindRecipient(identity, now);
        transition(invitation, decision, now);
        entityManager.flush();
        return ProjectInvitationResponse.from(invitation, now);
    }

    private ProjectInvitation findLocked(UUID id, Long projectId) {
        return entityManager.createQuery("from ProjectInvitation where id = :id and project.id = :project", ProjectInvitation.class)
                .setParameter("id", id).setParameter("project", projectId).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElseThrow(NotFoundException::new);
    }

    private boolean isMember(Long projectId, UUID memberId) {
        return entityManager.createQuery("select count(m) from ProjectMember m where project.id = :project and memberId = :member", Long.class)
                .setParameter("project", projectId).setParameter("member", memberId).getSingleResult() > 0;
    }

    private void requireAuthority(Long projectId, UUID memberId, ProjectRole offered) {
        if (offered == null || offered == ProjectRole.OWNER) {
            throw new jakarta.ws.rs.BadRequestException("Invalid invitation role");
        }
        // Scalar query avoids a membership entity cached by an earlier controller authorization check.
        ProjectRole role = entityManager.createQuery("select role from ProjectMember where project.id = :project and memberId = :member", ProjectRole.class)
                .setParameter("project", projectId).setParameter("member", memberId)
                .getResultStream().findFirst().orElseThrow(ForbiddenException::new);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new ForbiddenException("Inviter no longer has sufficient authority");
        }
    }

    private void transition(ProjectInvitation invitation, InvitationStatus decision, Instant now) {
        try {
            invitation.transitionTo(decision, now);
        } catch (IllegalStateException exception) {
            throw conflict("Invitation is not pending or has expired");
        }
    }

    private WebApplicationException conflict(String message) {
        return new WebApplicationException(message, 409);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}