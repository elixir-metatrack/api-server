package no.metatrack.server.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import no.metatrack.server.project.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(NotificationPostgresProfile.class)
@EnabledIfEnvironmentVariable(named = "INVITATION_TEST_JDBC_URL", matches = ".+")
class NotificationPostgresTest {
    @Inject
    NotificationTransactions notifications;
    @Inject
    ProjectInvitationTransactions invitations;
    @Inject
    ProjectService projects;
    @Inject
    EntityManager entityManager;
    @Inject
    ObjectMapper mapper;
    @io.quarkus.test.common.http.TestHTTPResource
    URI baseUri;

    private UUID owner;
    private UUID recipient;
    private String email;
    private Long projectId;

    @BeforeEach
    void setup() {
        owner = UUID.randomUUID();
        recipient = UUID.randomUUID();
        email = UUID.randomUUID() + "@example.org";
        projectId = projects.createProject(UUID.randomUUID().toString(), "Test", owner.toString()).id;
    }

    @AfterAll
    static void cleanupSchema() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("INVITATION_TEST_JDBC_URL"),
                System.getenv("INVITATION_TEST_DB_USER"), System.getenv("INVITATION_TEST_DB_PASSWORD"));
             var statement = connection.createStatement()) {
            String schema = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getValue("quarkus.flyway.default-schema", String.class);
            assertTrue(schema.matches("notification_test_[a-f0-9]{32}"));
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void existingAccountAtomicCreationReadIsolationAndLifecycle() {
        var invitation = create(recipient);
        var item = inbox().items().getFirst();
        assertEquals(invitation.id(), item.invitation().id());
        assertTrue(item.invitation().actionable());
        assertEquals(1, inbox().unreadCount());
        assertEquals(0, notifications.synchronize(identity()).created());
        assertEquals(0, notifications.list(owner, 0, 20, null).total());
        assertThrows(NotFoundException.class, () -> notifications.updateRead(owner, item.id(), true));
        assertThrows(NotFoundException.class, () -> notifications.updateRead(recipient, UUID.randomUUID(), true));
        notifications.updateRead(recipient, item.id(), true);
        var readOn = inbox().items().getFirst().readOn();
        notifications.updateRead(recipient, item.id(), true);
        assertEquals(readOn, inbox().items().getFirst().readOn());
        assertEquals(0, inbox().unreadCount());
        assertEquals(0, notifications.list(recipient, 0, 20, true).total());
        assertEquals(1, notifications.list(recipient, 0, 20, false).total());
        assertTrue(inbox().items().getFirst().invitation().actionable());
        notifications.updateRead(recipient, item.id(), false);
        assertNull(inbox().items().getFirst().readOn());
        assertEquals(1, inbox().unreadCount());
        invitations.decide(invitation.id(), identity(), InvitationStatus.DECLINED);
        assertFalse(inbox().items().getFirst().invitation().actionable());
        assertEquals(InvitationStatus.DECLINED, inbox().items().getFirst().invitation().status());
        assertEquals(1, inbox().unreadCount());
        assertEquals(0, notifications.synchronize(identity()).created());
        var replacement = create(recipient);
        invitations.revoke(projectId, replacement.id(), owner);
        assertEquals(InvitationStatus.REVOKED, inbox().items().getFirst().invitation().status());
        assertFalse(inbox().items().getFirst().invitation().actionable());
    }

    @Test
    void rollbackOfCreationAndClaimAndProjectDeletion() {
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            create(recipient);
            throw new IllegalStateException("rollback creation");
        }));
        assertEquals(0, inbox().total());
        assertTrue(invitations.list(projectId, owner, 0, 20).isEmpty());
        var invitation = create(null);
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, notifications.synchronize(identity()).created());
            throw new IllegalStateException("rollback claim");
        }));
        assertEquals(0, inbox().total());
        QuarkusTransaction.requiringNew().run(() -> assertNull(entityManager.find(ProjectInvitation.class, invitation.id()).recipientId));
        assertEquals(1, notifications.synchronize(identity()).created());
        projects.deleteProject(projectId);
        assertEquals(0, inbox().total());
    }

    @Test
    void notificationInsertFailureRollsBackInvitation() {
        QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                "alter table {h-schema}notification add constraint notification_test_reject check (recipient_id <> '" + recipient + "') not valid").executeUpdate());
        try {
            assertThrows(RuntimeException.class, () -> create(recipient));
            assertTrue(invitations.list(projectId, owner, 0, 20).isEmpty());
            assertEquals(0, inbox().total());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> entityManager.createNativeQuery(
                    "alter table {h-schema}notification drop constraint notification_test_reject").executeUpdate());
        }
    }

    @Test
    void concurrentRepeatedSyncAndBoundSubjectProtection() throws Exception {
        var invitation = create(null);
        assertEquals(1, race(() -> notifications.synchronize(identity()).created(),
                () -> notifications.synchronize(identity()).created()).stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, inbox().total());
        assertEquals(0, notifications.synchronize(identity()).created());
        UUID other = UUID.randomUUID();
        assertEquals(0, notifications.synchronize(new VerifiedEmailIdentity(other, email)).created());
        assertEquals(0, notifications.list(other, 0, 20, null).total());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(recipient, entityManager.find(ProjectInvitation.class, invitation.id()).recipientId));
        assertEquals(0, notifications.synchronize(new VerifiedEmailIdentity(recipient, "wrong@example.org")).created());
    }

    @Test
    void competingSubjectsAndAcceptanceAreSerialized() throws Exception {
        var invitation = create(null);
        UUID other = UUID.randomUUID();
        var claims = race(() -> notifications.synchronize(identity()).created(),
                () -> notifications.synchronize(new VerifiedEmailIdentity(other, email)).created());
        assertEquals(1, claims.stream().mapToInt(Integer::intValue).sum());
        UUID winner = claims.getFirst() == 1 ? recipient : other;
        assertEquals(1, notifications.list(winner, 0, 20, null).total());
        QuarkusTransaction.requiringNew().run(() -> assertEquals(winner, entityManager.find(ProjectInvitation.class, invitation.id()).recipientId));
        var winnerIdentity = new VerifiedEmailIdentity(winner, email);
        race(() -> notifications.synchronize(winnerIdentity).created(), () -> {
            invitations.decide(invitation.id(), winnerIdentity, InvitationStatus.ACCEPTED);
            return 0;
        });
        assertFalse(notifications.list(winner, 0, 20, null).items().getFirst().invitation().actionable());
    }

    @Test
    void fixedClockExpirySkipsUnboundAndReportsExistingAsExpired() {
        var unbound = create(null);
        Instant boundary = unbound.expiresOn();
        var future = new NotificationTransactions(entityManager, Clock.fixed(boundary, ZoneOffset.UTC));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> future.synchronize(identity())).created());
        assertEquals(0, inbox().total());
        QuarkusTransaction.requiringNew().run(() -> assertNull(entityManager.find(ProjectInvitation.class, unbound.id()).recipientId));
        assertEquals(1, notifications.synchronize(identity()).created());
        var item = QuarkusTransaction.requiringNew().call(() -> future.list(recipient, 0, 20, null)).items().getFirst();
        assertEquals(InvitationStatus.EXPIRED, item.invitation().status());
        assertFalse(item.invitation().actionable());
        assertNull(item.readOn());
    }

    @Test
    void stablePaginationWithTimestampTiesAndGlobalUnreadCount() {
        create(recipient);
        invitations.create(projectId, "second-" + email, recipient, owner, "Owner", ProjectRole.VIEWER);
        QuarkusTransaction.requiringNew().run(() -> entityManager.createQuery("update Notification set createdOn = :now where recipientId = :recipient")
                .setParameter("now", Instant.parse("2026-01-01T00:00:00Z")).setParameter("recipient", recipient).executeUpdate());
        var first = notifications.list(recipient, 0, 1, null);
        var second = notifications.list(recipient, 1, 1, null);
        assertEquals(2, first.total());
        assertEquals(2, first.unreadCount());
        assertNotEquals(first.items().getFirst().id(), second.items().getFirst().id());
        assertEquals(first, notifications.list(recipient, 0, 1, null));
        notifications.updateRead(recipient, first.items().getFirst().id(), true);
        assertEquals(1, notifications.list(recipient, 0, 1, false).unreadCount());
        assertTrue(notifications.list(recipient, 2, 1, null).items().isEmpty());
    }

    @Test
    void httpVerifiedRegistrationSyncInboxAndAcceptance() throws Exception {
        var invitation = create(null);
        String token = token(recipient, email, true);
        assertEquals(0, json(request("GET", "/api/notifications", token, null)).get("total").asInt());
        assertEquals(1, json(request("POST", "/api/notifications/sync", token, null)).get("created").asInt());
        assertEquals(0, json(request("POST", "/api/notifications/sync", token, null)).get("created").asInt());
        QuarkusTransaction.requiringNew().run(() -> assertFalse(ProjectMember.isMember(recipient, projectId)));
        var item = json(request("GET", "/api/notifications?unread=true&size=1", token, null)).get("items").get(0);
        UUID id = UUID.fromString(item.get("id").asText());
        assertTrue(item.get("invitation").get("actionable").asBoolean());
        assertEquals(204, request("PATCH", "/api/notifications/" + id, token, "{\"read\":true}").statusCode());
        assertEquals(200, request("POST", "/api/invitations/" + invitation.id() + "/accept", token, null).statusCode());
        assertEquals(200, request("POST", "/api/invitations/" + invitation.id() + "/accept", token, null).statusCode());
        assertFalse(inbox().items().getFirst().invitation().actionable());
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, ProjectMember.count("project.id = ?1 and memberId = ?2", projectId, recipient));
            assertEquals(ProjectRole.EDITOR, entityManager.createQuery("select role from ProjectMember where project.id = :project and memberId = :recipient", ProjectRole.class)
                    .setParameter("project", projectId).setParameter("recipient", recipient).getSingleResult());
        });
    }

    @Test
    void httpAuthenticationVerificationPrivacyAndValidation() throws Exception {
        create(recipient);
        UUID id = inbox().items().getFirst().id();
        for (String[] route : new String[][]{{"GET", "/api/notifications"}, {"POST", "/api/notifications/sync"}, {"PATCH", "/api/notifications/" + id}}) {
            assertEquals(401, request(route[0], route[1], null, "{}").statusCode());
        }
        for (String invalid : List.of(token(recipient, email, false), token(recipient, null, true))) {
            assertEquals(403, request("POST", "/api/notifications/sync", invalid, null).statusCode());
            assertEquals(200, request("GET", "/api/notifications", invalid, null).statusCode());
        }
        String unrelated = token(UUID.randomUUID(), email, true);
        assertEquals(0, json(request("POST", "/api/notifications/sync", unrelated, null)).get("created").asInt());
        assertEquals(0, json(request("GET", "/api/notifications", unrelated, null)).get("total").asInt());
        assertEquals(404, request("PATCH", "/api/notifications/" + id, unrelated, "{\"read\":true}").statusCode());
        String token = token(recipient, email, true);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=2147483647&size=100")) {
            assertEquals(400, request("GET", "/api/notifications?" + query, token, null).statusCode());
        }
        for (String body : List.of("{}", "{\"read\":null}", "null")) {
            assertEquals(400, request("PATCH", "/api/notifications/" + id, token, body).statusCode());
        }
    }

    @Test
    void httpInvitationManagementAuthenticationAndPermissions() throws Exception {
        var invitation = create(recipient);
        String root = "/api/projects/" + projectId + "/invitations";
        String body = mapper.writeValueAsString(new CreateProjectInvitationRequest(email, ProjectRole.VIEWER));
        String[][] routes = {{"POST", root}, {"GET", root}, {"DELETE", root + "/" + invitation.id()},
                {"POST", root + "/" + invitation.id() + "/resend"}};
        for (String[] route : routes) {
            assertEquals(401, request(route[0], route[1], null, body).statusCode(), route[1]);
        }
        for (ProjectRole role : List.of(ProjectRole.VIEWER, ProjectRole.EDITOR)) {
            UUID member = UUID.randomUUID();
            projects.addMember(projectId, member, role);
            for (String[] route : routes) {
                assertEquals(403, request(route[0], route[1], token(member, email, true), body).statusCode(), role + " " + route[1]);
            }
        }
        for (String[] route : routes) {
            assertEquals(403, request(route[0], route[1], token(UUID.randomUUID(), email, true), body).statusCode(), route[1]);
        }
        String ownerToken = token(owner, "owner@example.org", true);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=2147483647&size=100")) {
            assertEquals(400, request("GET", root + "?" + query, ownerToken, null).statusCode());
        }
        for (String invalid : List.of("null", "{}", "{\"email\":\"invalid\",\"role\":\"VIEWER\"}",
                "{\"email\":\"a@example.org\",\"role\":\"OWNER\"}", "{\"email\":\"a@example.org\",\"role\":null}")) {
            assertEquals(400, request("POST", root, ownerToken, invalid).statusCode(), invalid);
        }
        UUID admin = UUID.randomUUID();
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        String adminToken = token(admin, "admin@example.org", true);
        assertEquals(invitation.id().toString(), json(request("GET", root, adminToken, null)).get(0).get("id").asText());
        assertEquals(0, json(request("GET", root + "?page=1&size=1", ownerToken, null)).size());
        Long otherProject = projects.createProject(UUID.randomUUID().toString(), "Other", owner.toString()).id;
        for (String suffix : List.of("", "/resend")) {
            String method = suffix.isEmpty() ? "DELETE" : "POST";
            assertEquals(404, request(method, "/api/projects/" + otherProject + "/invitations/" + invitation.id() + suffix, ownerToken, null).statusCode());
            assertEquals(404, request(method, root + "/" + UUID.randomUUID() + suffix, ownerToken, null).statusCode());
        }
        for (int i = 0; i < 2; i++) {
            assertEquals("REVOKED", json(request("DELETE", root + "/" + invitation.id(), adminToken, null)).get("status").asText());
        }
        assertEquals(409, request("POST", root + "/" + invitation.id() + "/resend", ownerToken, null).statusCode());
        assertEquals(409, request("POST", "/api/invitations/" + invitation.id() + "/accept", token(recipient, email, true), null).statusCode());
        QuarkusTransaction.requiringNew().run(() -> assertFalse(ProjectMember.isMember(recipient, projectId)));
    }

    @Test
    void httpInvitationCreateResendAndDeliveryContracts() throws Exception {
        var identities = org.mockito.Mockito.mock(no.metatrack.server.auth.keycloak.IdentityLookupService.class);
        org.mockito.Mockito.when(identities.findByEmail(email)).thenReturn(java.util.Optional.empty());
        io.quarkus.test.junit.QuarkusMock.installMockForType(identities, no.metatrack.server.auth.keycloak.IdentityLookupService.class);
        String root = "/api/projects/" + projectId + "/invitations";
        String ownerToken = token(owner, "owner@example.org", true);
        String body = mapper.writeValueAsString(new CreateProjectInvitationRequest(email, ProjectRole.ADMIN));
        var response = request("POST", root, ownerToken, body);
        assertEquals(201, response.statusCode(), response.body());
        var created = mapper.readTree(response.body());
        String id = created.get("id").asText();
        assertEquals("PENDING", created.get("status").asText());
        assertEquals("SENT", created.get("deliveryStatus").asText());
        assertEquals(email, created.get("recipientEmail").asText());
        assertEquals(projectId.longValue(), created.get("projectId").asLong());
        assertFalse(created.has("token"));
        assertEquals(409, request("POST", root, ownerToken, body).statusCode());
        assertEquals(429, request("POST", root + "/" + id + "/resend", ownerToken, null).statusCode());
        elapseDeliveryCooldown(UUID.fromString(id));
        var resent = json(request("POST", root + "/" + id + "/resend", ownerToken, null));
        assertEquals(id, resent.get("id").asText());
        assertEquals(created.get("expiresOn"), resent.get("expiresOn"));
        assertEquals("SENT", resent.get("deliveryStatus").asText());
        assertEquals(1, json(request("GET", root, ownerToken, null)).size());
        org.mockito.Mockito.when(identities.findByEmail(email)).thenThrow(new no.metatrack.server.auth.keycloak.KeycloakIdentityException("Upstream unavailable"));
        elapseDeliveryCooldown(UUID.fromString(id));
        assertEquals(502, request("POST", root + "/" + id + "/resend", ownerToken, null).statusCode());
        assertEquals(502, request("POST", root, ownerToken, body).statusCode());
        assertEquals(1, json(request("GET", root, ownerToken, null)).size());
    }

    @Test
    void httpInvitationDecisionPrivacyIdempotencyAndConflicts() throws Exception {
        var invitation = create(recipient);
        String root = "/api/invitations/" + invitation.id();
        String recipientToken = token(recipient, email, true);
        for (String decision : List.of("accept", "decline")) {
            String path = root + "/" + decision;
            assertEquals(401, request("POST", path, null, null).statusCode());
            for (String invalid : List.of(token(recipient, email, false), token(recipient, null, true))) {
                assertEquals(403, request("POST", path, invalid, null).statusCode());
            }
            for (String unrelated : List.of(token(UUID.randomUUID(), email, true), token(recipient, "other@example.org", true))) {
                assertEquals(404, request("POST", path, unrelated, null).statusCode());
            }
            assertEquals(404, request("POST", "/api/invitations/" + UUID.randomUUID() + "/" + decision, recipientToken, null).statusCode());
        }
        for (int i = 0; i < 2; i++) {
            assertEquals("DECLINED", json(request("POST", root + "/decline", recipientToken, null)).get("status").asText());
        }
        assertEquals(409, request("POST", root + "/accept", recipientToken, null).statusCode());
        assertEquals(409, request("DELETE", "/api/projects/" + projectId + "/invitations/" + invitation.id(), token(owner, email, true), null).statusCode());
        QuarkusTransaction.requiringNew().run(() -> assertFalse(ProjectMember.isMember(recipient, projectId)));
        var expired = create(recipient);
        QuarkusTransaction.requiringNew().run(() -> {
            var stored = entityManager.find(ProjectInvitation.class, expired.id());
            stored.createdOn = Instant.now().minusSeconds(120);
            stored.expiresOn = Instant.now().minusSeconds(60);
        });
        for (String decision : List.of("accept", "decline")) {
            assertEquals(409, request("POST", "/api/invitations/" + expired.id() + "/" + decision, recipientToken, null).statusCode());
        }
        var pending = create(recipient);
        projects.addMember(projectId, recipient, ProjectRole.ADMIN);
        assertEquals("ACCEPTED", json(request("POST", "/api/invitations/" + pending.id() + "/accept", recipientToken, null)).get("status").asText());
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, ProjectMember.count("project.id = ?1 and memberId = ?2", projectId, recipient));
            assertEquals(ProjectRole.ADMIN, entityManager.createQuery("select role from ProjectMember where project.id = :project and memberId = :recipient", ProjectRole.class)
                    .setParameter("project", projectId).setParameter("recipient", recipient).getSingleResult());
        });
    }

    @Test
    void httpAdminCreationAndLostInviterAuthority() throws Exception {
        UUID admin = UUID.randomUUID();
        projects.addMember(projectId, admin, ProjectRole.ADMIN);
        var identities = org.mockito.Mockito.mock(no.metatrack.server.auth.keycloak.IdentityLookupService.class);
        org.mockito.Mockito.when(identities.findByEmail(email)).thenReturn(java.util.Optional.of(
                new no.metatrack.server.auth.keycloak.KeycloakUserRepresentation(recipient.toString(), "Recipient", email, true)));
        io.quarkus.test.junit.QuarkusMock.installMockForType(identities, no.metatrack.server.auth.keycloak.IdentityLookupService.class);
        String body = mapper.writeValueAsString(new CreateProjectInvitationRequest(email, ProjectRole.VIEWER));
        String root = "/api/projects/" + projectId + "/invitations";
        var response = request("POST", root, token(admin, "admin@example.org", true), body);
        assertEquals(201, response.statusCode(), response.body());
        String id = mapper.readTree(response.body()).get("id").asText();
        assertEquals(1, inbox().total());
        projects.removeMember(projectId, admin);
        String recipientToken = token(recipient, email, true);
        assertEquals(403, request("POST", "/api/invitations/" + id + "/accept", recipientToken, null).statusCode());
        QuarkusTransaction.requiringNew().run(() -> assertFalse(ProjectMember.isMember(recipient, projectId)));
        assertEquals("DECLINED", json(request("POST", "/api/invitations/" + id + "/decline", recipientToken, null)).get("status").asText());
        projects.addMember(projectId, recipient, ProjectRole.VIEWER);
        assertEquals(409, request("POST", root, token(owner, "owner@example.org", true), body).statusCode());
    }

    @Test
    void httpLegacyJoinStillWorksWithoutVerifiedEmail() throws Exception {
        String root = "/api/projects/" + projectId;
        String recipientToken = token(recipient, null, false);
        assertEquals(204, request("POST", root + "/join/EDITOR", recipientToken, null).statusCode());
        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(JoinProject.findByUserIdAndProjectId(projectId, recipient));
            assertFalse(ProjectMember.isMember(recipient, projectId));
        });
        assertEquals(0, inbox().total());
        assertEquals(204, request("POST", root + "/joinrequests/" + recipient + "/approve", token(owner, null, false), null).statusCode());
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(JoinProject.findByUserIdAndProjectId(projectId, recipient));
            assertTrue(ProjectMember.isMember(recipient, projectId));
        });
        assertEquals(0, json(request("GET", root + "/invitations", token(owner, null, false), null)).size());
    }

    @Test
    void httpNotificationReadStateIsIdempotentAndIndependent() throws Exception {
        create(recipient);
        String token = token(recipient, email, true);
        String path = "/api/notifications/" + inbox().items().getFirst().id();
        assertEquals(404, request("PATCH", "/api/notifications/" + UUID.randomUUID(), token, "{\"read\":true}").statusCode());
        var updated = request("PATCH", path, token, "{\"read\":true}");
        assertEquals(204, updated.statusCode());
        assertEquals("", updated.body());
        var read = json(request("GET", "/api/notifications?unread=false", token, null));
        assertEquals(1, read.get("total").asInt());
        assertEquals(0, read.get("unreadCount").asInt());
        var readOn = read.get("items").get(0).get("readOn");
        assertEquals(204, request("PATCH", path, token, "{\"read\":true}").statusCode());
        assertEquals(readOn, json(request("GET", "/api/notifications", token, null)).get("items").get(0).get("readOn"));
        assertEquals(204, request("PATCH", path, token, "{\"read\":false}").statusCode());
        var unread = json(request("GET", "/api/notifications?unread=true", token, null));
        assertEquals(1, unread.get("unreadCount").asInt());
        assertTrue(unread.get("items").get(0).get("invitation").get("actionable").asBoolean());
        assertFalse(unread.get("items").get(0).get("invitation").has("recipientEmail"));
        QuarkusTransaction.requiringNew().run(() -> assertFalse(ProjectMember.isMember(recipient, projectId)));
    }

    @Test
    void openApiPublishesConcreteSchemasAndStatusContracts() throws Exception {
        var document = json(request("GET", "/openapi?format=json", null, null));
        var paths = document.get("paths");
        String root = "/api/projects/{projectId}/invitations";
        for (String[] contract : new String[][]{
                {root, "post", "201", "400", "401", "403", "409", "502"},
                {root, "get", "200", "400", "401", "403"},
                {root + "/{id}", "delete", "200", "401", "403", "404", "409"},
                {root + "/{id}/resend", "post", "200", "401", "403", "404", "409", "429", "502"},
                {"/api/invitations/{id}/accept", "post", "200", "401", "403", "404", "409"},
                {"/api/invitations/{id}/decline", "post", "200", "401", "403", "404", "409"},
                {"/api/notifications", "get", "200", "400", "401"},
                {"/api/notifications/sync", "post", "200", "401", "403"},
                {"/api/notifications/{id}", "patch", "204", "400", "401", "404"}}) {
            var operation = paths.path(contract[0]).path(contract[1]);
            assertFalse(operation.path("summary").asText().isBlank(), contract[0]);
            for (int i = 2; i < contract.length; i++) {
                assertTrue(operation.path("responses").has(contract[i]), contract[0] + " " + contract[i]);
            }
        }
        assertEquals("#/components/schemas/ProjectInvitationResponse", paths.path(root).path("post").path("responses").path("201")
                .path("content").path("application/json").path("schema").path("$ref").asText());
        var listSchema = paths.path(root).path("get").path("responses").path("200").path("content").path("application/json").path("schema");
        assertEquals("array", listSchema.path("type").asText());
        assertEquals("#/components/schemas/ProjectInvitationResponse", listSchema.path("items").path("$ref").asText());
        assertFalse(paths.path("/api/notifications/{id}").path("patch").path("responses").path("204").has("content"));
        var schemas = document.path("components").path("schemas");
        for (String name : List.of("CreateProjectInvitationRequest", "ProjectInvitationResponse", "NotificationResponse", "NotificationInvitation",
                "NotificationPage", "NotificationSyncResponse", "UpdateNotificationRequest")) {
            assertTrue(schemas.has(name), name);
            assertFalse(schemas.path(name).path("description").asText().isBlank(), name);
        }
        var invitationSchema = schemas.path("NotificationResponse").path("properties").path("invitation");
        assertEquals("#/components/schemas/NotificationInvitation", invitationSchema.path("anyOf").path(0).path("$ref").asText(), invitationSchema.toString());
        assertEquals("null", invitationSchema.path("anyOf").path(1).path("type").asText());
        assertFalse(schemas.path("NotificationInvitation").path("properties").has("recipientEmail"));
        assertFalse(schemas.path("ProjectInvitationResponse").path("properties").has("token"));
    }

    private VerifiedEmailIdentity identity() {
        return new VerifiedEmailIdentity(recipient, email);
    }

    private ProjectInvitationResponse create(UUID subject) {
        return invitations.create(projectId, email, subject, owner, "Owner", ProjectRole.EDITOR);
    }

    private void elapseDeliveryCooldown(UUID id) {
        QuarkusTransaction.requiringNew().run(() -> {
            var invitation = entityManager.find(ProjectInvitation.class, id);
            invitation.deliveryAttemptedOn = Instant.now().minusSeconds(120);
            invitation.deliveryCompletedOn = Instant.now().minusSeconds(90);
            invitation.deliveryRetryAfter = Instant.now().minusSeconds(30);
        });
    }

    private NotificationPage inbox() {
        return notifications.list(recipient, 0, 20, null);
    }

    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private String token(UUID subject, String address, boolean verified) throws Exception {
        var claims = new java.util.HashMap<String, Object>(Map.of("sub", subject.toString(), "email_verified", verified,
                "exp", Instant.now().plus(Duration.ofHours(1)).getEpochSecond(), "iat", Instant.now().getEpochSecond()));
        if (address != null) {
            claims.put("email", address);
        }
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(mapper.writeValueAsBytes(claims));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(NotificationPostgresProfile.KEYS.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encoder.encodeToString(signature.sign());
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder = HttpRequest.newBuilder(baseUri.resolve(path)).header("Content-Type", "application/json");
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }
}