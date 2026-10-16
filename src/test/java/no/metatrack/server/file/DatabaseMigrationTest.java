package no.metatrack.server.file;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTest {
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

    @Test
    void freshInstallationMigratesToLatestAndEnforcesConstraints() throws Exception {
        Flyway migration = Flyway.configure().dataSource(config.get("quarkus.datasource.jdbc.url"),
                        config.get("quarkus.datasource.username"), config.get("quarkus.datasource.password"))
                .locations("classpath:db/migration").defaultSchema("fresh").target("latest").load();
        migration.migrate();
        migration.validate();
        assertNotNull(migration.info().current());
        assertEquals(0, migration.info().pending().length);
        assertEquals(0, migration.migrate().migrationsExecuted);

        try (var connection = DriverManager.getConnection(config.get("quarkus.datasource.jdbc.url"),
                config.get("quarkus.datasource.username"), config.get("quarkus.datasource.password"))) {
            connection.setSchema("fresh");
            try (var statement = connection.createStatement(); var columns = statement.executeQuery("""
                    select f.read_role, m.tooltip from file f cross join sample_metadata_field m where false
                    """)) {
                assertEquals(2, columns.getMetaData().getColumnCount());
            }
            execute(connection, """
                    insert into project (id, name, owner) values (1, 'root', gen_random_uuid());
                    insert into project (id, name, owner, parent_project_id) values (2, 'child', gen_random_uuid(), 1);
                    insert into sample (id, name, project_id) values (gen_random_uuid(), 'sample', 1);
                    insert into project_sample (project_id, sample_id) select 2, id from sample;
                    """);
            assertRejected(connection, "23505",
                    "insert into project_sample (project_id, sample_id) select 2, id from sample");
            assertRejected(connection, "23503",
                    "insert into project_sample (project_id, sample_id) values (2, gen_random_uuid())");
            assertRejected(connection, "23503",
                    "insert into project_sample (project_id, sample_id) select 999, id from sample");
            assertRejected(connection, "23503", "update project set parent_project_id = 999 where id = 2");
            execute(connection, "delete from project where id = 2");
            assertCount(connection, "project_sample", 0);
            assertCount(connection, "sample", 1);
            execute(connection, """
                    insert into project_sample (project_id, sample_id) select 1, id from sample;
                    delete from sample;
                    """);
            assertCount(connection, "project_sample", 0);
            assertCount(connection, "project", 1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertRejected(Connection connection, String sqlState, String sql) {
        SQLException failure = assertThrows(SQLException.class, () -> execute(connection, sql));
        assertEquals(sqlState, failure.getSQLState());
    }

    private void assertCount(Connection connection, String table, long expected) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(result.next());
            assertEquals(expected, result.getLong(1), table);
        }
    }
}