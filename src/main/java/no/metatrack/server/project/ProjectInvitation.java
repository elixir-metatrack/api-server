package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.auth.EmailAddress;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import no.metatrack.server.invitation.DeliveryStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "project_invitation")
public class ProjectInvitation extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    public Project project;

    @Column(nullable = false, length = 254)
    public String recipientEmail;

    public UUID recipientId;

    @Column(nullable = false)
    public UUID inviterId;

    @Column(nullable = false)
    public String inviterDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ProjectRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public InvitationStatus status = InvitationStatus.PENDING;

    @Column(nullable = false)
    public Instant createdOn;

    @Column(nullable = false)
    public Instant expiresOn;

    public Instant respondedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DeliveryStatus deliveryStatus = DeliveryStatus.NOT_ATTEMPTED;

    public UUID deliveryAttemptId;
    public Instant deliveryAttemptedOn;
    public Instant deliveryCompletedOn;
    public Instant deliveryRetryAfter;

    @Version
    @Column(nullable = false)
    public long version;

    public boolean isActionable(Instant now) {
        Objects.requireNonNull(now, "Time is required");
        return status == InvitationStatus.PENDING && now.isBefore(expiresOn);
    }

    public boolean expire(Instant now) {
        Objects.requireNonNull(now, "Time is required");
        if (status != InvitationStatus.PENDING || now.isBefore(expiresOn)) {
            return false;
        }
        status = InvitationStatus.EXPIRED;
        respondedOn = now;
        return true;
    }

    public boolean matchesRecipient(VerifiedEmailIdentity identity) {
        return recipientEmail.equals(identity.email())
                && (recipientId == null || recipientId.equals(identity.subject()));
    }

    public boolean bindRecipient(VerifiedEmailIdentity identity, Instant now) {
        if (!matchesRecipient(identity)) {
            throw new IllegalArgumentException("Invitation recipient does not match");
        }
        if (!isActionable(now)) {
            throw new IllegalStateException("Invitation is not pending or has expired");
        }
        if (recipientId != null) {
            return false;
        }
        recipientId = identity.subject();
        return true;
    }

    public boolean transitionTo(InvitationStatus decision, Instant now) {
        Objects.requireNonNull(now, "Time is required");
        if (decision == null || decision == InvitationStatus.PENDING || decision == InvitationStatus.EXPIRED) {
            throw new IllegalArgumentException("Invalid invitation decision");
        }
        if (status == decision) {
            return false;
        }
        if (!isActionable(now) || now.isBefore(createdOn)) {
            throw new IllegalStateException("Invitation is not pending or has expired");
        }
        if ((decision == InvitationStatus.ACCEPTED || decision == InvitationStatus.DECLINED) && recipientId == null) {
            throw new IllegalStateException("Invitation recipient must be bound before responding");
        }
        status = decision;
        respondedOn = now;
        return true;
    }

    @PrePersist
    @PreUpdate
    void validateStorage() {
        recipientEmail = EmailAddress.normalize(recipientEmail);
        if (role == null || role == ProjectRole.OWNER) {
            throw new IllegalArgumentException("Invitation cannot grant this role");
        }
        if (createdOn == null || expiresOn == null || !expiresOn.isAfter(createdOn)) {
            throw new IllegalArgumentException("Invitation expiry must follow creation");
        }
        if (status == null || (status == InvitationStatus.PENDING) != (respondedOn == null)
                || (respondedOn != null && respondedOn.isBefore(createdOn))
                || (status == InvitationStatus.EXPIRED && respondedOn.isBefore(expiresOn))
                || (status != InvitationStatus.PENDING && status != InvitationStatus.EXPIRED
                    && !respondedOn.isBefore(expiresOn))
                || ((status == InvitationStatus.ACCEPTED || status == InvitationStatus.DECLINED) && recipientId == null)) {
            throw new IllegalArgumentException("Invalid invitation lifecycle state");
        }
    }
}