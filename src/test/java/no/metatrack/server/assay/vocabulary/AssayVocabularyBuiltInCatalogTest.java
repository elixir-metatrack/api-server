package no.metatrack.server.assay.vocabulary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssayVocabularyBuiltInCatalogTest {
    @Test
    void catalogContainsExactlyTheApprovedColumns() {
        assertEquals(List.of(
                new AssayVocabularyColumn("study_accession", "Study accession", false),
                new AssayVocabularyColumn("instrument_model", "Instrument model", false),
                new AssayVocabularyColumn("sequencing_platform", "Sequencing platform", false),
                new AssayVocabularyColumn("sequencing_laboratory", "Sequencing laboratory", false),
                new AssayVocabularyColumn("library_name", "Library name", false),
                new AssayVocabularyColumn("library_source", "Library source", false),
                new AssayVocabularyColumn("library_selection", "Library selection", false),
                new AssayVocabularyColumn("library_strategy", "Library strategy", false),
                new AssayVocabularyColumn("library_layout", "Library layout", false)),
                AssayVocabularyBuiltInCatalog.columns());

        for (AssayVocabularyColumn column : AssayVocabularyBuiltInCatalog.columns()) {
            assertEquals(column, AssayVocabularyBuiltInCatalog.find(column.key()).orElseThrow());
        }
    }

    @Test
    void unknownCustomAndExcludedKeysAreNotEligible() {
        for (String key : List.of("unknown", "custom:instrument_model", "custom_field", "name", "id",
                "project", "project_id", "samples", "files", "created_on", "modified_on", "insert_size",
                "instrumentModel", "INSTRUMENT_MODEL", " instrument_model", "")) {
            assertTrue(AssayVocabularyBuiltInCatalog.find(key).isEmpty(), key);
        }
    }

    @Test
    void catalogCannotBeModified() {
        assertThrows(UnsupportedOperationException.class,
                () -> AssayVocabularyBuiltInCatalog.columns().clear());
    }
}