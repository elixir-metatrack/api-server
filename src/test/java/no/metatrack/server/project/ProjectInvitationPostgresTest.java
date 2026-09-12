package no.metatrack.server.project;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(ProjectInvitationPostgresProfile.class)
@EnabledIfEnvironmentVariable(named = "INVITATION_TEST_JDBC_URL", matches = ".+")
class ProjectInvitationPostgresTest {
    @Inject
    ProjectInvitationTransactions invitations;
    @Inject
    ProjectService projects;
    @Inject
    EntityManager entityManager;
    @Inject
    JoinProjectService joinProjects;
    @io.quarkus.test.common.http.TestHTTPResource
    java.net.URI baseUri;

    private UUID owner;
    private UUID recipient;
    private Long projectId;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        recipient = UUID.randomUUID();
        projectId = projects.createProject(UUID.randomUUID().toString(), "Test", owner.toString()).id;
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("INVITATION_TEST_JDBC_URL"),
                System.getenv("INVITATION_TEST_DB_USER"), System.getenv("INVITATION_TEST_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            String schema = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getValue("quarkus.flyway.default-schema", String.class);
            assertTrue(schema.matches("invitation_service_[a-f0-9]{32}"));
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void rolesSelfMembersDuplicatesAndNewAccountBinding() {
        for (ProjectRole role : ProjectRole.values()) {
            UUID actor = UUID.randomUUID();
            projects.addMember(projectId, actor, role);
            if (role == ProjectRole.ADMIN || role == ProjectRole.OWNER) {
                for (ProjectRole offered : List.of(ProjectRole.VIEWER, ProjectRole.EDITOR, ProjectRole.ADMIN)) {
                    invitations.create(projectId, UUID.randomUUID() + "@example.org", null, actor, "Actor", offered);
                }
                assertStatus(400, () -> invitations.create(projectId, "owner@example.org", null, actor, "Actor", ProjectRole.OWNER));
            } else {
                assertStatus(403, () -> invitations.create(projectId, "denied@example.org", null, actor, "Actor", ProjectRole.VIEWER));
                assertStatus(403, () -> invitations.list(projectId, actor, 0, 20));
            }
        }
        assertStatus(409, () -> invitations.create(projectId, "self@example.org", owner, owner, "Owner", ProjectRole.VIEWER));
        projects.addMember(projectId, recipient, ProjectRole.VIEWER);
        assertStatus(409, () -> create(recipient));
        projects.removeMember(projectId, recipient);
        var invitation = create(null);
        assertStatus(409, () -> create(null));
        assertEquals(InvitationStatus.ACCEPTED, accept(invitation.id()).status());
        assertEquals(ProjectRole.EDITOR, memberRole());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(recipient,
                entityManager.find(ProjectInvitation.class, invitation.id()).recipientId));
    }

    @Test
    void recipientIsolationAndTerminalDecisions() {
        var invitation = create(recipient);
        assertStatus(404, () -> invitations.decide(invitation.id(), new VerifiedEmailIdentity(UUID.randomUUID(), "recipient@example.org"), InvitationStatus.ACCEPTED));
        assertStatus(404, () -> invitations.decide(invitation.id(), new VerifiedEmailIdentity(recipient, "other@example.org"), InvitationStatus.DECLINED));
        assertStatus(404, () -> accept(UUID.randomUUID()));
        var declined = invitations.decide(invitation.id(), identity(), InvitationStatus.DECLINED);
        assertEquals(declined, invitations.decide(invitation.id(), identity(), InvitationStatus.DECLINED));
        assertStatus(409, () -> accept(invitation.id()));
        assertNull(memberRole());
        var replacement = create(recipient);
        var revoked = invitations.revoke(projectId, replacement.id(), owner);
        assertEquals(revoked, invitations.revoke(projectId, replacement.id(), owner));
        assertStatus(409, () -> accept(replacement.id()));
        assertStatus(409, () -> invitations.decide(replacement.id(), identity(), InvitationStatus.DECLINED));
    }

    @Test
    void rechecksInviterAuthorityAndPreservesExistingMembershipOnRetry() {
        UUID admin = UUID.randomUUID();
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        var invitation = invitations.create(projectId, "recipient@example.org", recipient, admin, "Admin", ProjectRole.ADMIN);
        projects.updateMemberRole(projectId, admin, ProjectRole.EDITOR);
        assertStatus(403, () -> accept(invitation.id()));
        assertNull(memberRole());
        projects.removeMember(projectId, admin);
        assertStatus(403, () -> accept(invitation.id()));
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        projects.addMember(projectId, recipient, ProjectRole.VIEWER);
        var accepted = accept(invitation.id());
        assertEquals(ProjectRole.VIEWER, memberRole());
        projects.removeMember(projectId, admin);
        assertEquals(accepted, accept(invitation.id()));
        projects.removeMember(projectId, recipient);
        assertEquals(accepted, accept(invitation.id()));
        assertNull(memberRole());
    }

    @Test
    void fixedClockExpiryAndReplacementAndStablePagination() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var before = new ProjectInvitationTransactions(entityManager, projects, Duration.ofDays(7), Clock.fixed(now, ZoneOffset.UTC));
        var after = new ProjectInvitationTransactions(entityManager, projects, Duration.ofDays(7), Clock.fixed(now.plus(Duration.ofDays(7)), ZoneOffset.UTC));
        var invitation = QuarkusTransaction.requiringNew().call(() -> before.create(projectId, "recipient@example.org", recipient, owner, "Owner", ProjectRole.EDITOR));
        assertEquals(now.plus(Duration.ofDays(7)), invitation.expiresOn());
        assertStatus(409, () -> QuarkusTransaction.requiringNew().run(() -> after.decide(invitation.id(), identity(), InvitationStatus.ACCEPTED)));
        assertStatus(409, () -> QuarkusTransaction.requiringNew().run(() -> after.revoke(projectId, invitation.id(), owner)));
        assertEquals(InvitationStatus.EXPIRED, QuarkusTransaction.requiringNew().call(() -> after.list(projectId, owner, 0, 20)).getFirst().status());
        var replacement = QuarkusTransaction.requiringNew().call(() -> after.create(projectId, "recipient@example.org", recipient, owner, "Owner", ProjectRole.EDITOR));
        assertNotEquals(invitation.id(), replacement.id());
        var page0 = QuarkusTransaction.requiringNew().call(() -> after.list(projectId, owner, 0, 1));
        var page1 = QuarkusTransaction.requiringNew().call(() -> after.list(projectId, owner, 1, 1));
        assertEquals(replacement.id(), page0.getFirst().id());
        assertEquals(invitation.id(), page1.getFirst().id());
        assertNull(memberRole());
    }

    @Test
    void rollbackAndDeletionCleanupAndCrossProjectIsolation() {
        var invitation = create(recipient);
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            accept(invitation.id());
            throw new IllegalStateException("Rollback the whole acceptance");
        }));
        assertNull(memberRole());
        assertEquals(InvitationStatus.PENDING, invitations.list(projectId, owner, 0, 20).getFirst().status());
        Long other = projects.createProject(UUID.randomUUID().toString(), "Other", owner.toString()).id;
        assertStatus(404, () -> invitations.revoke(other, invitation.id(), owner));
        accept(invitation.id());
        projects.deleteProject(projectId);
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(entityManager.find(ProjectInvitation.class, invitation.id()));
            assertEquals(0, ProjectMember.count("project.id", projectId));
        });
        assertStatus(404, () -> accept(invitation.id()));
    }

    @Test
    void concurrentCreateAndRepeatedAcceptAreSerialized() throws Exception {
        var creates = race(() -> create(recipient), () -> create(recipient));
        assertEquals(1, creates.stream().filter(value -> value == 200).count());
        assertEquals(1, creates.stream().filter(value -> value == 409).count());
        var invitation = invitations.list(projectId, owner, 0, 20).getFirst();
        assertEquals(List.of(200, 200), race(() -> accept(invitation.id()), () -> accept(invitation.id())));
        assertEquals(ProjectRole.EDITOR, memberRole());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(1,
                ProjectMember.count("project.id = ?1 and memberId = ?2", projectId, recipient)));
    }

    @Test
    void competingDecisionsHaveOneWinner() throws Exception {
        var invitation = create(recipient);
        var results = race(() -> accept(invitation.id()), () -> invitations.decide(invitation.id(), identity(), InvitationStatus.DECLINED));
        assertTrue(results.containsAll(List.of(200, 409)));
        var status = invitations.list(projectId, owner, 0, 20).getFirst().status();
        assertEquals(status == InvitationStatus.ACCEPTED ? ProjectRole.EDITOR : null, memberRole());
        var second = invitations.create(projectId, "second@example.org", UUID.randomUUID(), owner, "Owner", ProjectRole.VIEWER);
        var secondIdentity = QuarkusTransaction.requiringNew().call(() -> new VerifiedEmailIdentity(
                entityManager.find(ProjectInvitation.class, second.id()).recipientId, "second@example.org"));
        assertTrue(race(() -> invitations.decide(second.id(), secondIdentity, InvitationStatus.ACCEPTED),
                () -> invitations.revoke(projectId, second.id(), owner)).containsAll(List.of(200, 409)));
    }

    @Test
    void concurrentDirectAddJoinApprovalAndRoleChangesRemainCompatible() throws Exception {
        var invitation = create(recipient);
        var results = race(() -> accept(invitation.id()), () -> {
            projects.addMember(projectId, recipient, ProjectRole.VIEWER);
            return null;
        });
        assertEquals(200, results.getFirst());
        assertTrue(results.getLast() == 200 || results.getLast() == 409);
        assertEquals(results.getLast() == 200 ? ProjectRole.VIEWER : ProjectRole.EDITOR, memberRole());
        projects.updateMemberRole(projectId, recipient, ProjectRole.VIEWER);
        assertEquals(ProjectRole.VIEWER, memberRole());
        projects.removeMember(projectId, recipient);
        assertNull(memberRole());
        var second = create(recipient);
        joinProjects.joinProject(projectId, recipient, ProjectRole.VIEWER);
        var approval = race(() -> accept(second.id()), () -> {
            joinProjects.approveJoinRequest(projectId, recipient);
            return null;
        });
        assertEquals(200, approval.getFirst());
        assertTrue(approval.getLast() == 200 || approval.getLast() == 409);
        assertEquals(approval.getLast() == 200 ? ProjectRole.VIEWER : ProjectRole.EDITOR, memberRole());
    }

    @Test
    void concurrentAuthorityRemovalAndProjectDeletionCannotGrantStaleAccess() throws Exception {
        UUID admin = UUID.randomUUID();
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        var invitation = invitations.create(projectId, "recipient@example.org", recipient, admin, "Admin", ProjectRole.EDITOR);
        var removal = race(() -> accept(invitation.id()), () -> {
            projects.removeMember(projectId, admin);
            return null;
        });
        assertEquals(200, removal.getLast());
        assertTrue(removal.getFirst() == 200 || removal.getFirst() == 403);
        assertEquals(removal.getFirst() == 200 ? ProjectRole.EDITOR : null, memberRole());
        var deletion = race(() -> accept(invitation.id()), () -> {
            projects.deleteProject(projectId);
            return null;
        });
        assertEquals(200, deletion.getLast());
        assertTrue(List.of(200, 403, 404).contains(deletion.getFirst()));
        assertNull(memberRole());
        assertStatus(404, () -> accept(invitation.id()));
    }

    @Test
    void onlyOneSubjectCanClaimAnUnboundInvitation() throws Exception {
        var invitation = create(null);
        UUID other = UUID.randomUUID();
        var results = race(() -> accept(invitation.id()), () -> invitations.decide(invitation.id(),
                new VerifiedEmailIdentity(other, "recipient@example.org"), InvitationStatus.ACCEPTED));
        assertTrue(results.containsAll(List.of(200, 404)));
        QuarkusTransaction.requiringNew().run(() -> {
            var stored = entityManager.find(ProjectInvitation.class, invitation.id());
            assertEquals(results.getFirst() == 200 ? recipient : other, stored.recipientId);
            assertEquals(1, ProjectMember.count("project.id = ?1 and memberId in ?2", projectId, List.of(recipient, other)));
        });
    }

    @Test
    void demotionCommittedWhileAcceptanceWaitsIsRechecked() throws Exception {
        UUID admin = UUID.randomUUID();
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        var invitation = invitations.create(projectId, "recipient@example.org", recipient, admin, "Admin", ProjectRole.EDITOR);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var demotion = executor.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                projects.lockProject(projectId);
                locked.countDown();
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                projects.updateMemberRole(projectId, admin, ProjectRole.VIEWER);
            }));
            var acceptance = executor.submit(() -> {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                started.countDown();
                assertStatus(403, () -> accept(invitation.id()));
                return null;
            });
            demotion.get(20, TimeUnit.SECONDS);
            acceptance.get(20, TimeUnit.SECONDS);
        }
        assertNull(memberRole());
        assertEquals(InvitationStatus.PENDING, invitations.list(projectId, owner, 0, 20).getFirst().status());
    }

    @Test
    void allInvitationHttpRoutesRequireAuthentication() throws Exception {
        UUID id = UUID.randomUUID();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            for (String[] route : new String[][]{
                    {"POST", "/api/projects/" + projectId + "/invitations"},
                    {"GET", "/api/projects/" + projectId + "/invitations"},
                    {"DELETE", "/api/projects/" + projectId + "/invitations/" + id},
                    {"POST", "/api/invitations/" + id + "/accept"},
                    {"POST", "/api/invitations/" + id + "/decline"}}) {
                var request = java.net.http.HttpRequest.newBuilder(baseUri.resolve(route[1]))
                        .header("Content-Type", "application/json")
                        .method(route[0], java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                assertEquals(401, client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode(), route[1]);
            }
        }
    }

    private List<Integer> race(Callable<?> first, Callable<?> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> raceAction(barrier, first));
            var b = executor.submit(() -> raceAction(barrier, second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private int raceAction(CyclicBarrier barrier, Callable<?> action) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            action.call();
            return 200;
        } catch (WebApplicationException exception) {
            return exception.getResponse().getStatus();
        }
    }

    private ProjectInvitationResponse create(UUID boundRecipient) {
        return invitations.create(projectId, "recipient@example.org", boundRecipient, owner, "Owner", ProjectRole.EDITOR);
    }

    private VerifiedEmailIdentity identity() {
        return new VerifiedEmailIdentity(recipient, "recipient@example.org");
    }

    private ProjectInvitationResponse accept(UUID id) {
        return invitations.decide(id, identity(), InvitationStatus.ACCEPTED);
    }

    private ProjectRole memberRole() {
        return QuarkusTransaction.requiringNew().call(() -> ProjectMember.findMemberInProjectOptional(recipient, projectId)
                .map(member -> member.role).orElse(null));
    }

    private void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(WebApplicationException.class, action::run).getResponse().getStatus());
    }
}