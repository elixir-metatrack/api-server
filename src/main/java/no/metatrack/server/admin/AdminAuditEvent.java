package no.metatrack.server.admin;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "system_admin_audit", indexes =
        @Index(name = "idx_system_admin_audit_occurred_at", columnList = "occurred_at"))
public class AdminAuditEvent extends PanacheEntity {
    @Column(name = "actor_id", nullable = false)
    public UUID actorId;

    @Column(nullable = false, length = 16)
    public String action;

    @Column(nullable = false, length = 512)
    public String target;

    @Column(name = "response_status", nullable = false)
    public int responseStatus;

    @Column(name = "occurred_at", nullable = false)
    public Instant occurredAt;
}