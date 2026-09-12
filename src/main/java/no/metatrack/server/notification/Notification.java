package no.metatrack.server.notification;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.project.ProjectInvitation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification", uniqueConstraints = @UniqueConstraint(columnNames = {"recipient_id", "invitation_id"}))
public class Notification extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public NotificationType type;

    @Column(nullable = false)
    public Instant createdOn;

    public Instant readOn;

    @Column(nullable = false)
    public String title;

    @Column(nullable = false, columnDefinition = "text")
    public String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitation_id")
    public ProjectInvitation invitation;

    public static Notification forInvitation(ProjectInvitation invitation, Instant now) {
        Notification notification = new Notification();
        notification.recipientId = Objects.requireNonNull(invitation.recipientId);
        notification.type = NotificationType.PROJECT_INVITATION;
        notification.createdOn = now;
        notification.title = "Project invitation";
        notification.content = invitation.inviterDisplayName + " invited you to " + invitation.project.name
                + " as " + invitation.role + ".";
        notification.invitation = invitation;
        return notification;
    }
}