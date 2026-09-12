package no.metatrack.server.invitation;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(InvitationDeliveryPostgresProfile.class)
@EnabledIfEnvironmentVariable(named = "INVITATION_TEST_JDBC_URL", matches = ".+")
class InvitationDeliveryPostgresTest {
    @Inject InvitationDeliveryTransactions delivery;
    @Inject ProjectInvitationTransactions invitations;
    @Inject ProjectService projects;
    @Inject EntityManager entityManager;
    @Inject InvitationEmailService email;
    @Inject MockMailbox mailbox;
    @Inject TransactionManager transactionManager;

    private UUID owner;
    private Long projectId;
    private ProjectInvitationResponse saved;

    @BeforeEach
    void setup() {
        mailbox.clear();
        owner = UUID.randomUUID();
        projectId = projects.createProject(UUID.randomUUID().toString(), "Delivery test", owner.toString()).id;
        saved = invitations.create(projectId, "recipient@example.org", null, owner, "Owner", ProjectRole.EDITOR);
    }

    @AfterAll
    static void cleanup() throws Exception {
        String schema = org.eclipse.microprofile.config.ConfigProvider.getConfig().getValue("quarkus.flyway.default-schema", String.class);
        assertTrue(schema.matches("invitation_delivery_[a-f0-9]{32}"));
        try (var connection = DriverManager.getConnection(System.getenv("INVITATION_TEST_JDBC_URL"),
                System.getenv("INVITATION_TEST_DB_USER"), System.getenv("INVITATION_TEST_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void commitsClaimBeforeMockSmtpAndEnforcesTransactionBoundary() throws Exception {
        assertEquals(DeliveryStatus.NOT_ATTEMPTED, saved.deliveryStatus());
        var attempt = delivery.claim(projectId, saved.id(), owner);
        assertEquals(Status.STATUS_NO_TRANSACTION, transactionManager.getStatus());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(DeliveryStatus.SENDING,
                entityManager.find(ProjectInvitation.class, saved.id()).deliveryStatus));
        var mail = email.render(attempt.envelope(), false);
        assertThrows(RuntimeException.class, () -> QuarkusTransaction.requiringNew().run(() -> email.send(mail)));
        assertEquals(0, mailbox.getTotalMessagesSent());
        email.send(mail);
        assertEquals(1, mailbox.getTotalMessagesSent());
        assertTrue(mailbox.getMailMessagesSentTo("recipient@example.org").getFirst().getHtml().contains("/register"));
        var sent = delivery.complete(saved.id(), attempt.id(), DeliveryStatus.SENT);
        assertEquals(DeliveryStatus.SENT, sent.deliveryStatus());
        assertEquals(saved.expiresOn(), sent.expiresOn());
        assertStatus(429, () -> delivery.claim(projectId, saved.id(), owner));
        assertStatus(429, () -> delivery.prepare(projectId, saved.id(), owner));
    }

    @Test
    void concurrentClaimsHaveOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> claim = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    delivery.claim(projectId, saved.id(), owner);
                    return 200;
                } catch (WebApplicationException exception) {
                    return exception.getResponse().getStatus();
                }
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            assertEquals(java.util.Set.of(200, 429), java.util.Set.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
        }
    }

    @Test
    void smtpFailureKeepsCommittedInvitationAndPersistsUncertainty() {
        var users = org.mockito.Mockito.mock(no.metatrack.server.auth.UserService.class);
        var roles = org.mockito.Mockito.mock(ProjectRoleCheck.class);
        var identities = org.mockito.Mockito.mock(no.metatrack.server.auth.keycloak.IdentityLookupService.class);
        org.mockito.Mockito.when(users.requireCurrentUser()).thenReturn(new no.metatrack.server.auth.CurrentUser(
                owner.toString(), "Owner", java.util.Set.of(), null, null, null));
        org.mockito.Mockito.when(roles.isAtLeast(projectId, ProjectRole.ADMIN)).thenReturn(true);
        org.mockito.Mockito.when(identities.findByEmail("another@example.org")).thenReturn(java.util.Optional.empty());
        var mailer = org.mockito.Mockito.mock(io.quarkus.mailer.Mailer.class);
        org.mockito.Mockito.doAnswer(call -> {
            assertEquals(Status.STATUS_NO_TRANSACTION, transactionManager.getStatus());
            QuarkusTransaction.requiringNew().run(() -> assertEquals(1L, entityManager.createQuery(
                    "select count(i) from ProjectInvitation i where i.project.id = :project and i.recipientEmail = :email and i.deliveryStatus = :status", Long.class)
                    .setParameter("project", projectId).setParameter("email", "another@example.org")
                    .setParameter("status", DeliveryStatus.SENDING).getSingleResult()));
            throw new IllegalStateException("Connection lost after SMTP DATA");
        }).when(mailer).send(org.mockito.ArgumentMatchers.<io.quarkus.mailer.Mail[]>any());
        var coordinator = new InvitationCoordinator(new ProjectInvitationService(users, roles, identities, invitations), delivery,
                new InvitationEmailService(mailer, "https://spa.example/login", "https://spa.example/register", true), identities, users);
        var result = coordinator.create(projectId, new CreateProjectInvitationRequest("another@example.org", ProjectRole.EDITOR));
        assertEquals(DeliveryStatus.UNKNOWN, result.deliveryStatus());
        QuarkusTransaction.requiringNew().run(() -> {
            var stored = entityManager.find(ProjectInvitation.class, result.id());
            assertEquals(InvitationStatus.PENDING, stored.status);
            assertEquals(DeliveryStatus.UNKNOWN, stored.deliveryStatus);
            assertEquals(result.expiresOn(), stored.expiresOn);
        });
    }

    @Test
    void staleAttemptBecomesUncertainAndLateCompletionCannotOverwriteNewAttempt() {
        Instant start = saved.createdOn();
        var first = claimAt(start);
        assertStatus(429, () -> claimAt(start.plusSeconds(299)));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(DeliveryStatus.UNKNOWN,
                ProjectInvitationResponse.from(entityManager.find(ProjectInvitation.class, saved.id()), start.plusSeconds(300)).deliveryStatus()));
        var second = claimAt(start.plusSeconds(300));
        assertNotEquals(first.id(), second.id());
        var late = completeAt(start.plusSeconds(301), first.id(), DeliveryStatus.SENT);
        assertEquals(DeliveryStatus.SENDING, late.deliveryStatus());
        var failed = completeAt(start.plusSeconds(302), second.id(), DeliveryStatus.UNKNOWN);
        assertEquals(DeliveryStatus.UNKNOWN, failed.deliveryStatus());
        assertStatus(429, () -> claimAt(start.plusSeconds(361)));
        var third = claimAt(start.plusSeconds(362));
        assertEquals(saved.expiresOn(), third.response().expiresOn());
        assertEquals(DeliveryStatus.FAILED, completeAt(start.plusSeconds(363), third.id(), DeliveryStatus.FAILED).deliveryStatus());
    }

    @Test
    void rechecksAuthorityScopeAndLifecycleBeforeClaim() {
        assertStatus(403, () -> delivery.prepare(projectId, saved.id(), UUID.randomUUID()));
        assertStatus(404, () -> delivery.claim(projectId, UUID.randomUUID(), owner));
        assertStatus(409, () -> claimAt(saved.expiresOn()));
        invitations.revoke(projectId, saved.id(), owner);
        assertStatus(409, () -> delivery.claim(projectId, saved.id(), owner));
    }

    @Test
    void authorityLossBetweenPreparationAndClaimPreventsSending() {
        UUID admin = UUID.randomUUID();
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        delivery.prepare(projectId, saved.id(), admin);
        projects.removeMember(projectId, admin);
        assertStatus(403, () -> delivery.claim(projectId, saved.id(), admin));
        QuarkusTransaction.requiringNew().run(() -> assertEquals(DeliveryStatus.NOT_ATTEMPTED,
                entityManager.find(ProjectInvitation.class, saved.id()).deliveryStatus));
    }

    private InvitationDeliveryTransactions.Attempt claimAt(Instant instant) {
        return QuarkusTransaction.requiringNew().call(() -> fixed(instant).claim(projectId, saved.id(), owner));
    }

    private ProjectInvitationResponse completeAt(Instant instant, UUID attempt, DeliveryStatus outcome) {
        return QuarkusTransaction.requiringNew().call(() -> fixed(instant).complete(saved.id(), attempt, outcome));
    }

    private InvitationDeliveryTransactions fixed(Instant instant) {
        return new InvitationDeliveryTransactions(entityManager, projects, Duration.ofSeconds(60), Duration.ofSeconds(300),
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(WebApplicationException.class, action::run).getResponse().getStatus());
    }
}