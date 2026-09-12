package no.metatrack.server.invitation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.keycloak.IdentityLookupService;
import no.metatrack.server.project.CreateProjectInvitationRequest;
import no.metatrack.server.project.ProjectInvitationResponse;
import no.metatrack.server.project.ProjectInvitationService;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.NEVER)
public class InvitationCoordinator {
    private static final Logger LOG = Logger.getLogger(InvitationCoordinator.class);
    private final ProjectInvitationService invitations;
    private final InvitationDeliveryTransactions delivery;
    private final InvitationEmailService email;
    private final IdentityLookupService identities;
    private final UserService users;

    public InvitationCoordinator(ProjectInvitationService invitations, InvitationDeliveryTransactions delivery,
                                 InvitationEmailService email, IdentityLookupService identities, UserService users) {
        this.invitations = invitations;
        this.delivery = delivery;
        this.email = email;
        this.identities = identities;
        this.users = users;
    }

    public ProjectInvitationResponse create(Long projectId, CreateProjectInvitationRequest request) {
        var saved = invitations.create(projectId, request);
        try {
            var attempt = delivery.claim(projectId, saved.id(), saved.inviterId());
            return send(attempt, attempt.envelope().registered());
        } catch (RuntimeException exception) {
            // Persistence has already committed. Never turn a delivery failure into a failed creation.
            LOG.warnf("Invitation %s persisted; delivery could not be started", saved.id());
            return saved.withDelivery(DeliveryStatus.UNKNOWN, saved.deliveryAttemptedOn(),
                    saved.deliveryCompletedOn(), saved.deliveryRetryAfter());
        }
    }

    public ProjectInvitationResponse resend(Long projectId, UUID id) {
        UUID caller = UUID.fromString(users.requireCurrentUser().id());
        var envelope = delivery.prepare(projectId, id, caller);
        boolean registered = identities.findByEmail(envelope.email()).isPresent();
        var attempt = delivery.claim(projectId, id, caller);
        return send(attempt, registered);
    }

    private ProjectInvitationResponse send(InvitationDeliveryTransactions.Attempt attempt, boolean registered) {
        DeliveryStatus outcome;
        io.quarkus.mailer.Mail mail;
        try {
            mail = email.render(attempt.envelope(), registered);
        } catch (RuntimeException exception) {
            return record(attempt, DeliveryStatus.FAILED);
        }
        try {
            email.send(mail);
            outcome = DeliveryStatus.SENT;
        } catch (RuntimeException exception) {
            // Once SMTP starts, an exception cannot prove the server did not accept the message.
            outcome = DeliveryStatus.UNKNOWN;
        }
        return record(attempt, outcome);
    }

    private ProjectInvitationResponse record(InvitationDeliveryTransactions.Attempt attempt, DeliveryStatus outcome) {
        try {
            return delivery.complete(attempt.response().id(), attempt.id(), outcome);
        } catch (RuntimeException exception) {
            LOG.warnf("Invitation %s delivery outcome could not be recorded", attempt.response().id());
            var response = attempt.response();
            return response.withDelivery(DeliveryStatus.UNKNOWN, response.deliveryAttemptedOn(), null, response.deliveryRetryAfter());
        }
    }
}