package no.metatrack.server.project;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INVITATION_TEST_JDBC_URL", matches = ".+")
class ProjectInvitationStorageTest {
    private static final String SCHEMA = "invitation_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID RECIPIENT = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws SQLException {
        Flyway.configure().dataSource(System.getenv("INVITATION_TEST_JDBC_URL"),
                        System.getenv("INVITATION_TEST_DB_USER"), System.getenv("INVITATION_TEST_DB_PASSWORD"))
                .defaultSchema(SCHEMA).schemas(SCHEMA).locations("classpath:db/migration").target("1.0.8").load().migrate();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO project (id, name, owner) VALUES (1, 'Existing project', '" + RECIPIENT + "')");
        }
        Flyway.configure().dataSource(System.getenv("INVITATION_TEST_JDBC_URL"),
                        System.getenv("INVITATION_TEST_DB_USER"), System.getenv("INVITATION_TEST_DB_PASSWORD"))
                .defaultSchema(SCHEMA).schemas(SCHEMA).locations("classpath:db/migration").load().migrate();
        try (var connection = connect(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT name FROM project WHERE id = 1")) {
            assertTrue(result.next());
            assertEquals("Existing project", result.getString(1));
        }
    }

    @AfterAll
    static void dropTestSchema() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @BeforeEach
    void createProject() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE project CASCADE");
            statement.execute("INSERT INTO project (id, name, owner) VALUES (1, 'Test project', '" + RECIPIENT + "')");
            statement.execute("INSERT INTO project (id, name, owner) VALUES (2, 'Other project', '" + RECIPIENT + "')");
        }
    }

    @Test
    void fullMigrationSupportsPendingUniquenessAndReplacementAfterExpiry() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            UUID id = insertInvitation(connection, "invitee@example.org", 1);
            assertEquals("23505", assertThrows(SQLException.class,
                    () -> insertInvitation(connection, "invitee@example.org", 1)).getSQLState());
            insertInvitation(connection, "invitee@example.org", 2);
            statement.execute("UPDATE project_invitation SET created_on = now() - interval '8 days', expires_on = now() - interval '1 day' WHERE id = '" + id + "'");
            assertEquals("23505", assertThrows(SQLException.class,
                    () -> insertInvitation(connection, "invitee@example.org", 1)).getSQLState());
            statement.execute("UPDATE project_invitation SET status = 'EXPIRED', responded_on = now(), version = version + 1 WHERE id = '" + id + "'");
            insertInvitation(connection, "invitee@example.org", 1);
            assertEquals(3, count(connection, "project_invitation"));
        }
    }

    @Test
    void rejectsInvalidEmailRoleLifecycleAndForeignKeys() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertInvitation(connection, "Invitee@Example.org", 1)).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertInvitation(connection, "invalid", 1)).getSQLState());
            assertEquals("23503", assertThrows(SQLException.class,
                    () -> insertInvitation(connection, "invitee@example.org", 999)).getSQLState());
            insertInvitation(connection, "invitee@example.org", 1);
            for (String assignment : new String[]{"role = 'OWNER'", "status = 'UNKNOWN'", "expires_on = created_on",
                    "status = 'ACCEPTED'", "status = 'ACCEPTED', responded_on = now()",
                    "responded_on = now()", "status = 'EXPIRED', responded_on = created_on - interval '1 second'",
                    "status = 'EXPIRED', responded_on = created_on", "status = 'REVOKED', responded_on = expires_on"}) {
                assertEquals("23514", assertThrows(SQLException.class,
                        () -> statement.execute("UPDATE project_invitation SET " + assignment)).getSQLState());
            }
        }
    }

    @Test
    void terminalInvitationsAllowANewPendingInvitation() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            for (String status : new String[]{"ACCEPTED", "DECLINED", "REVOKED"}) {
                UUID id = insertInvitation(connection, status.toLowerCase(java.util.Locale.ROOT) + "@example.org", 1);
                statement.execute("UPDATE project_invitation SET recipient_id = '" + RECIPIENT
                        + "', status = '" + status + "', responded_on = now() WHERE id = '" + id + "'");
                insertInvitation(connection, status.toLowerCase(java.util.Locale.ROOT) + "@example.org", 1);
            }
            assertEquals(6, count(connection, "project_invitation"));
        }
    }

    @Test
    void concurrentPendingInvitationsHaveExactlyOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> raceInsert(barrier));
            var second = executor.submit(() -> raceInsert(barrier));
            var results = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("created"::equals).count());
            assertEquals(1, results.stream().filter("23505"::equals).count());
        }
        try (var connection = connect()) {
            assertEquals(1, count(connection, "project_invitation"));
        }
    }

    @Test
    void notificationDeduplicationReadStateAndDeletionCascade() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            UUID invitation = insertInvitation(connection, "invitee@example.org", 1);
            insertNotification(connection, invitation);
            assertEquals("23505", assertThrows(SQLException.class,
                    () -> insertNotification(connection, invitation)).getSQLState());
            assertEquals("23503", assertThrows(SQLException.class,
                    () -> insertNotification(connection, UUID.randomUUID())).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> insertNotification(connection, null)).getSQLState());
            statement.execute("UPDATE notification SET read_on = now()");
            try (var result = statement.executeQuery("SELECT status FROM project_invitation")) {
                assertTrue(result.next());
                assertEquals("PENDING", result.getString(1));
            }
            statement.execute("UPDATE notification SET read_on = NULL");
            statement.execute("DELETE FROM project WHERE id = 1");
            assertEquals(0, count(connection, "notification"));
            assertEquals(0, count(connection, "project_invitation"));
        }
    }

    @Test
    void invitationAndNotificationCanRollbackAtomically() throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            UUID invitation = insertInvitation(connection, "invitee@example.org", 1);
            insertNotification(connection, invitation);
            connection.rollback();
            assertEquals(0, count(connection, "project_invitation"));
            assertEquals(0, count(connection, "notification"));
        }
    }

    @Test
    void versionedConditionalTransitionRejectsStaleWriter() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            UUID invitation = insertInvitation(connection, "invitee@example.org", 1);
            String update = "UPDATE project_invitation SET status = ?, responded_on = now(), version = version + 1 WHERE id = ? AND version = 0 AND status = 'PENDING'";
            try (var transition = connection.prepareStatement(update)) {
                transition.setString(1, "REVOKED");
                transition.setObject(2, invitation);
                assertEquals(1, transition.executeUpdate());
                transition.setString(1, "EXPIRED");
                assertEquals(0, transition.executeUpdate());
            }
            try (var result = statement.executeQuery("SELECT version FROM project_invitation")) {
                assertTrue(result.next());
                assertEquals(1, result.getLong(1));
            }
        }
    }

    private String raceInsert(CyclicBarrier barrier) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '10s'");
            barrier.await(5, TimeUnit.SECONDS);
            try {
                insertInvitation(connection, "race@example.org", 1);
                return "created";
            } catch (SQLException exception) {
                return exception.getSQLState();
            }
        }
    }

    private static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(System.getenv("INVITATION_TEST_JDBC_URL"),
                System.getenv("INVITATION_TEST_DB_USER"), System.getenv("INVITATION_TEST_DB_PASSWORD"));
        connection.setSchema(SCHEMA);
        return connection;
    }

    private UUID insertInvitation(Connection connection, String email, long projectId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("""
                INSERT INTO project_invitation (id, project_id, recipient_email, inviter_id, inviter_display_name,
                    role, status, created_on, expires_on)
                VALUES (?, ?, ?, ?, 'Inviter', 'VIEWER', 'PENDING', now(), now() + interval '7 days')
                """)) {
            statement.setObject(1, id);
            statement.setLong(2, projectId);
            statement.setString(3, email);
            statement.setObject(4, UUID.randomUUID());
            statement.executeUpdate();
        }
        return id;
    }

    private void insertNotification(Connection connection, UUID invitation) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO notification (id, recipient_id, type, created_on, title, content, invitation_id)
                VALUES (?, ?, 'PROJECT_INVITATION', now(), 'Invitation', 'Project invitation', ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, RECIPIENT);
            statement.setObject(3, invitation);
            statement.executeUpdate();
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}