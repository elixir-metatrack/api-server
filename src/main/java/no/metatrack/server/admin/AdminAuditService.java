package no.metatrack.server.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AdminAuditService {
    private final EntityManager entityManager;

    public AdminAuditService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public void record(UUID actorId, String action, String target, int responseStatus) {
        AdminAuditEvent event = new AdminAuditEvent();
        event.actorId = actorId;
        event.action = action;
        event.target = target.length() > 512 ? target.substring(0, 512) : target;
        event.responseStatus = responseStatus;
        event.occurredAt = Instant.now();
        entityManager.persist(event);
    }

    @Transactional
    public AdminAuditPageResponse list(int page, int size) {
        List<AdminAuditResponse> events = entityManager.createQuery(
                        "from AdminAuditEvent order by occurredAt desc, id desc", AdminAuditEvent.class)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList().stream()
                .map(event -> new AdminAuditResponse(event.id, event.actorId, event.action, event.target,
                        event.responseStatus, event.occurredAt))
                .toList();
        long total = entityManager.createQuery("select count(e) from AdminAuditEvent e", Long.class)
                .getSingleResult();
        return new AdminAuditPageResponse(events, page, size, total);
    }
}