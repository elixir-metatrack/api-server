package no.metatrack.server.file;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubProjectMigrationTest {
    private static final SubProjectDatabase database = new SubProjectDatabase();
    private static Map<String, String> config;

    @BeforeAll
    static void start() {
        config = database.start();
    }

    @AfterAll
    static void stop() {
        database.stop();
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(config.get("quarkus.datasource.jdbc.url"),
                        config.get("quarkus.datasource.username"), config.get("quarkus.datasource.password"))
                .locations("classpath:db/migration").defaultSchema(schema).target(target).load();
    }

    private Connection connect(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(config.get("quarkus.datasource.jdbc.url"),
                config.get("quarkus.datasource.username"), config.get("quarkus.datasource.password"));
        connection.setSchema(schema);
        return connection;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private void script(Connection connection, String action) throws Exception {
        try (var input = getClass().getResourceAsStream("/db/rollback/V1.0.13__" + action + ".sql")) {
            assertNotNull(input);
            execute(connection, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (SQLException failure) {
            execute(connection, "rollback");
            throw failure;
        }
    }

    @Test
    void freshInstallationHasUniqueOrderedVersionsAndConstraints() throws Exception {
        Flyway migration = flyway("fresh", "latest");
        assertEquals(14, migration.migrate().migrationsExecuted);
        migration.validate();
        assertEquals("1.0.13", migration.info().current().getVersion().toString());
        try (var connection = connect("fresh")) {
            assertEquals(14, count(connection, "select count(distinct version) from flyway_schema_history where version is not null"));
            assertEquals(3, count(connection, "select count(*) from pg_constraint where conrelid = 'project_sample'::regclass"));
            assertEquals(1, count(connection, "select count(*) from information_schema.columns where table_schema = 'fresh' and table_name = 'file' and column_name = 'read_role'"));
            execute(connection, "insert into project values (1, now(), null, now(), 'root', gen_random_uuid(), null)");
            assertThrows(SQLException.class, () -> execute(connection, "insert into project_sample values (1, gen_random_uuid())"));
            assertThrows(SQLException.class, () -> execute(connection, "update project set parent_project_id = 999 where id = 1"));
        }
    }

    @Test
    void upgradeRollbackAndReapplicationPreserveMainData() throws Exception {
        flyway("lifecycle", "1.0.12").migrate();
        try (var connection = connect("lifecycle")) {
            execute(connection, """
                    insert into project (id, name, owner) values (1, 'root', gen_random_uuid());
                    insert into sample (id, name, project_id) values (gen_random_uuid(), 'sample', 1);
                    insert into assay (id, name, project_id) values (gen_random_uuid(), 'assay', 1);
                    insert into assay_sample select a.id, s.id from assay a cross join sample s;
                    insert into file (id, file_name, object_key, uuid, virtual_path, sample_id, assay_id, read_role)
                        select 1, 'reads', '1/reads', gen_random_uuid(), '1/reads', s.id, a.id, 'R1'
                        from sample s cross join assay a;
                    """);
            seedDependencies(connection, 1);
        }
        Flyway migration = flyway("lifecycle", "latest");
        assertEquals(1, migration.migrate().migrationsExecuted);
        migration.validate();
        try (var connection = connect("lifecycle")) {
            execute(connection, """
                    insert into project (id, name, owner, parent_project_id) values (2, 'child', gen_random_uuid(), 1);
                    insert into project_sample select 2, id from sample;
                    """);
            seedDependencies(connection, 2);
            assertThrows(SQLException.class, () -> script(connection, "reconcile_sub_projects_history"));
            execute(connection, "update flyway_schema_history set script = 'unexpected.sql' where version = '1.0.13'");
            assertThrows(SQLException.class, () -> script(connection, "rollback_sub_projects"));
            execute(connection, "update flyway_schema_history set script = 'V1.0.13__add_sub_projects.sql' where version = '1.0.13'");
            execute(connection, """
                    insert into flyway_schema_history (installed_rank, version, description, type, script, installed_by, execution_time, success)
                        values (100, '1.0.14', 'later', 'SQL', 'later.sql', current_user, 0, true);
                    """);
            assertThrows(SQLException.class, () -> script(connection, "rollback_sub_projects"));
            execute(connection, "delete from flyway_schema_history where installed_rank = 100");
            // Simulate a late failure, after links and members have already been removed.
            execute(connection, """
                    create function reject_delete() returns trigger language plpgsql as $$
                    begin raise exception 'injected failure'; end $$;
                    create trigger reject_delete before delete on project for each row execute function reject_delete();
                    """);
            assertThrows(SQLException.class, () -> script(connection, "rollback_sub_projects"));
            assertEquals(1, count(connection, "select count(*) from project_sample"));
            assertEquals(2, count(connection, "select count(*) from project_member"));
            execute(connection, "drop trigger reject_delete on project");
            execute(connection, "update sample set project_id = 2");
            assertThrows(SQLException.class, () -> script(connection, "rollback_sub_projects"));
            execute(connection, "update sample set project_id = 1; update assay set project_id = 2");
            assertThrows(SQLException.class, () -> script(connection, "rollback_sub_projects"));
            execute(connection, "update assay set project_id = 1");
            script(connection, "rollback_sub_projects");
            assertMainData(connection);
            assertEquals(1, count(connection, "select count(*) from flyway_schema_history where version = '1.0.13'"));
            assertEquals(0, count(connection, "select count(*) from information_schema.columns where table_schema = 'lifecycle' and table_name = 'project' and column_name = 'parent_project_id'"));
            assertEquals(1, count(connection, "select case when to_regclass('project_sample') is null then 1 else 0 end"));
            execute(connection, "update flyway_schema_history set script = 'unexpected.sql' where version = '1.0.13'");
            assertThrows(SQLException.class, () -> script(connection, "reconcile_sub_projects_history"));
            execute(connection, "update flyway_schema_history set script = 'V1.0.13__add_sub_projects.sql' where version = '1.0.13'");
            script(connection, "reconcile_sub_projects_history");
            assertThrows(SQLException.class, () -> script(connection, "reconcile_sub_projects_history"));
            assertEquals(1, count(connection, "select count(*) from flyway_schema_history where version = '1.0.12' and success"));
        }
        flyway("lifecycle", "1.0.12").validate();
        assertEquals(1, migration.migrate().migrationsExecuted);
        migration.validate();
        try (var connection = connect("lifecycle")) {
            assertMainData(connection);
            assertEquals(0, count(connection, "select count(*) from project_sample"));
        }
    }

    private void assertMainData(Connection connection) throws SQLException {
        for (String table : new String[]{"project", "sample", "assay", "assay_sample", "file", "project_member",
                "join_project", "project_invitation", "notification", "sample_metadata_field", "sample_metadata_value",
                "sample_vocabulary", "sample_vocabulary_term"}) {
            assertEquals(1, count(connection, "select count(*) from " + table), table);
        }
        assertEquals(1, count(connection, "select count(*) from file where id = 1 and read_role = 'R1' and object_key = '1/reads'"));
        assertEquals(1, count(connection, "select count(*) from project where id = 1 and name = 'root'"));
        assertEquals(1, count(connection, "select count(*) from sample where project_id = 1 and name = 'sample'"));
        assertEquals(1, count(connection, "select count(*) from assay where project_id = 1 and name = 'assay'"));
    }

    private void seedDependencies(Connection connection, int project) throws SQLException {
        execute(connection, """
                insert into project_member (id, member_id, role, project_id) values (%1$d, gen_random_uuid(), 'OWNER', %1$d);
                insert into join_project (id, project_id) values (%1$d, %1$d);
                insert into project_invitation (id, project_id, recipient_email, inviter_id, inviter_display_name, role, status, created_on, expires_on)
                    values (gen_random_uuid(), %1$d, 'test@example.org', gen_random_uuid(), 'test', 'VIEWER', 'PENDING', now(), now() + interval '1 day');
                insert into notification (id, recipient_id, type, created_on, title, content, invitation_id)
                    select gen_random_uuid(), gen_random_uuid(), 'PROJECT_INVITATION', now(), 'title', 'content', id
                    from project_invitation where project_id = %1$d;
                insert into sample_metadata_field (id, project_id, field_key, label, type, created_on, modified_on)
                    values (gen_random_uuid(), %1$d, 'custom', 'Custom', 'TEXT', now(), now());
                insert into sample_metadata_value (id, sample_id, field_id, text_value)
                    select gen_random_uuid(), s.id, f.id, 'value' from sample s cross join sample_metadata_field f where f.project_id = %1$d;
                insert into sample_vocabulary (id, project_id, field_key, created_on, modified_on)
                    values (gen_random_uuid(), %1$d, 'custom', now(), now());
                insert into sample_vocabulary_term (id, vocabulary_id, value)
                    select gen_random_uuid(), id, 'value' from sample_vocabulary where project_id = %1$d;
                """.formatted(project));
    }
}