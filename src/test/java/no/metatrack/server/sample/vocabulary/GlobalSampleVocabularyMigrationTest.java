package no.metatrack.server.sample.vocabulary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSampleVocabularyMigrationTest {
    private static final String MIGRATION = "/db/migration/V1.0.7__add_global_sample_vocabularies.sql";

    @Test
    void migrationRemovesExactlyCataloguedBuiltInProjectVocabularies() throws IOException {
        String sql = migrationSql();
        Matcher delete = Pattern.compile("DELETE FROM sample_vocabulary\\s+WHERE field_key IN \\((.*?)\\);", Pattern.DOTALL)
                .matcher(sql);
        assertTrue(delete.find());

        Set<String> deletedKeys = Pattern.compile("'([^']+)'[\\s,]*")
                .matcher(delete.group(1))
                .results()
                .map(result -> result.group(1))
                .collect(Collectors.toSet());
        Set<String> catalogKeys = SampleVocabularyBuiltInCatalog.columns().stream()
                .map(SampleVocabularyColumn::key)
                .collect(Collectors.toSet());

        assertEquals(catalogKeys, deletedKeys);
    }

    @Test
    void migrationCreatesGlobalConstraintsWithoutPromotingProjectTerms() throws IOException {
        String sql = migrationSql();

        assertTrue(sql.contains("UNIQUE (field_key)"));
        assertTrue(sql.contains("UNIQUE (vocabulary_id, value)"));
        assertTrue(sql.contains("REFERENCES global_sample_vocabulary ON DELETE CASCADE"));
        assertTrue(sql.contains("CHECK (btrim(value) <> '')"));
        assertTrue(!sql.contains("INSERT INTO global_sample_vocabulary"));
        assertTrue(!sql.contains("INSERT INTO global_sample_vocabulary_term"));
    }

    private String migrationSql() throws IOException {
        try (var stream = getClass().getResourceAsStream(MIGRATION)) {
            if (stream == null) throw new IOException("Missing migration " + MIGRATION);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}