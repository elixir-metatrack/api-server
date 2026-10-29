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
        // Freeze the catalog at V1.0.7: applied migrations must not follow later catalog additions.
        Set<String> catalogKeys = Set.of(
                "alias", "mlst", "project_title", "description", "isolate", "collected_by",
                "environmental_sample", "host_associated", "host_common_name", "host_subject_id",
                "collector_name", "collecting_institution", "host_sex", "influenza_test_method",
                "influenza_test_result", "other_pathogens_tested", "other_pathogens_test_result",
                "host_habitat", "isolation_source_host_associated", "host_behaviour",
                "isolation_source_non_host_associated", "influenza_virus_type", "influenza_sub_type",
                "serovar", "strain", "host_age", "county", "commune", "hospital_health_institution",
                "isolation_source", "location", "sequencing_lab", "institution", "host_health_state");

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