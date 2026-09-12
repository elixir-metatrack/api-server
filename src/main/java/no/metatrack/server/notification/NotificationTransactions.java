package no.metatrack.server.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import no.metatrack.server.project.InvitationStatus;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectInvitation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@ApplicationScoped
public class NotificationTransactions {
    private final EntityManager entityManager;
    private final Clock clock;

    @Inject
    public NotificationTransactions(EntityManager entityManager) {
        this(entityManager, Clock.systemUTC());
    }

    NotificationTransactions(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public NotificationSyncResponse synchronize(VerifiedEmailIdentity identity) {
        var projectIds = entityManager.createQuery("select distinct project.id from ProjectInvitation where recipientEmail = :email and status = :status and (recipientId is null or recipientId = :subject) order by project.id", Long.class)
                .setParameter("email", identity.email()).setParameter("status", InvitationStatus.PENDING)
                .setParameter("subject", identity.subject()).getResultList();
        int created = 0;
        for (Long projectId : projectIds) {
            // Lock projects in ascending order, and never hydrate invitations before waiting.
            if (entityManager.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE) == null) {
                continue;
            }
            Instant now = now();
            var invitations = entityManager.createQuery("from ProjectInvitation where project.id = :project and recipientEmail = :email and status = :status and (recipientId is null or recipientId = :subject)", ProjectInvitation.class)
                    .setParameter("project", projectId).setParameter("email", identity.email())
                    .setParameter("status", InvitationStatus.PENDING).setParameter("subject", identity.subject())
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
            for (var invitation : invitations) {
                if (!invitation.isActionable(now) || !invitation.matchesRecipient(identity)) {
                    continue;
                }
                invitation.bindRecipient(identity, now);
                long count = entityManager.createQuery("select count(n) from Notification n where recipientId = :subject and invitation.id = :invitation", Long.class)
                        .setParameter("subject", identity.subject()).setParameter("invitation", invitation.id).getSingleResult();
                if (count == 0) {
                    entityManager.persist(Notification.forInvitation(invitation, now));
                    created++;
                }
            }
        }
        entityManager.flush();
        return new NotificationSyncResponse(created);
    }

    @Transactional
    public NotificationPage list(UUID recipient, int page, int size, Boolean unread) {
        String filter = "recipientId = :recipient" + (unread == null ? "" : unread ? " and readOn is null" : " and readOn is not null");
        Instant now = now();
        var items = entityManager.createQuery("from Notification where " + filter + " order by createdOn desc, id desc", Notification.class)
                .setParameter("recipient", recipient).setFirstResult(page * size).setMaxResults(size)
                .getResultList().stream().map(notification -> NotificationResponse.from(notification, now)).toList();
        long total = entityManager.createQuery("select count(n) from Notification n where " + filter, Long.class)
                .setParameter("recipient", recipient).getSingleResult();
        long unreadCount = entityManager.createQuery("select count(n) from Notification n where recipientId = :recipient and readOn is null", Long.class)
                .setParameter("recipient", recipient).getSingleResult();
        return new NotificationPage(items, page, size, total, unreadCount);
    }

    @Transactional
    public void updateRead(UUID recipient, UUID id, boolean read) {
        var query = entityManager.createQuery("update Notification set readOn = " + (read ? "coalesce(readOn, :now)" : "null")
                        + " where id = :id and recipientId = :recipient")
                .setParameter("id", id).setParameter("recipient", recipient);
        if (read) {
            query.setParameter("now", now());
        }
        if (query.executeUpdate() == 0) {
            throw new NotFoundException();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}