package no.metatrack.server.assay.vocabulary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class GlobalAssayVocabularyMigrationTest {
    private static final String MIGRATION = "/db/migration/V1.0.11__add_global_assay_vocabularies.sql";

    @Test
    void migrationOnlyCreatesIndependentAssayVocabularyTables() throws IOException {
        String sql = migrationSql();
        List<String> statements = Pattern.compile(";").splitAsStream(sql)
                .map(String::trim).filter(statement -> !statement.isEmpty()).toList();

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TABLE global_assay_vocabulary ("));
        assertTrue(statements.get(1).startsWith("CREATE TABLE global_assay_vocabulary_term ("));
        assertFalse(Pattern.compile("\\b(INSERT|UPDATE|DELETE FROM|ALTER|DROP|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(sql).find());
        assertFalse(sql.contains("sample"));
    }

    @Test
    void migrationDefinesRequiredUniquenessNonblankAndCascadeConstraints() throws IOException {
        String sql = migrationSql();

        assertTrue(sql.contains("field_key varchar(64) NOT NULL"));
        assertTrue(sql.contains("created_on timestamp(6) with time zone NOT NULL"));
        assertTrue(sql.contains("modified_on timestamp(6) with time zone NOT NULL"));
        assertEquals(2, Pattern.compile("(?m)^    id uuid NOT NULL,").matcher(sql).results().count());
        assertEquals(2, Pattern.compile("PRIMARY KEY \\(id\\)").matcher(sql).results().count());
        assertTrue(sql.contains("UNIQUE (field_key)"));
        assertTrue(sql.contains("vocabulary_id uuid NOT NULL"));
        assertTrue(sql.contains("value text NOT NULL CHECK (btrim(value) <> '')"));
        assertTrue(sql.contains("UNIQUE (vocabulary_id, value)"));
        assertTrue(sql.contains("FOREIGN KEY (vocabulary_id)"));
        assertTrue(sql.contains("REFERENCES global_assay_vocabulary ON DELETE CASCADE"));
    }

    private String migrationSql() throws IOException {
        try (var stream = getClass().getResourceAsStream(MIGRATION)) {
            if (stream == null) throw new IOException("Missing migration " + MIGRATION);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}