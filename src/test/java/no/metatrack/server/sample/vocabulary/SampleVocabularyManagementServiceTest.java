package no.metatrack.server.sample.vocabulary;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SampleVocabularyManagementServiceTest {
    @Test
    void trimsTermsWhileKeepingCaseSensitiveDistinctValues() {
        assertEquals(List.of("Positive", "positive"),
                SampleVocabularyManagementService.validateTerms(List.of(" Positive ", "positive")));
    }

    @Test
    void rejectsEmptyBlankAndDuplicateTermsAfterTrimming() {
        assertThrows(BadRequestException.class, () -> SampleVocabularyManagementService.validateTerms(List.of()));
        assertThrows(BadRequestException.class, () -> SampleVocabularyManagementService.validateTerms(List.of("  ")));
        assertThrows(BadRequestException.class,
                () -> SampleVocabularyManagementService.validateTerms(List.of("Positive", " Positive ")));
    }

    @Test
    void catalogIncludesTextFieldsAndExcludesIdentifiersAndTypedFields() {
        assertTrue(SampleVocabularyBuiltInCatalog.find("host_sex").isPresent());
        assertTrue(SampleVocabularyBuiltInCatalog.find("isolation_source").isPresent());
        assertTrue(SampleVocabularyBuiltInCatalog.find("name").isEmpty());
        assertTrue(SampleVocabularyBuiltInCatalog.find("tax_id").isEmpty());
        assertTrue(SampleVocabularyBuiltInCatalog.find("collection_date").isEmpty());
    }

    @Test
    void projectManagementRejectsBuiltInFields() {
        SampleVocabularyManagementService service = new SampleVocabularyManagementService();

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service.requireEligibleColumn(1L, "host_sex"));

        assertEquals("Built-in sample fields use global vocabularies", exception.getMessage());
    }

    @Test
    void reconcilesTermsWithoutReplacingOverlappingEntities() {
        SampleVocabulary vocabulary = new SampleVocabulary();
        SampleVocabularyTerm retained = term(vocabulary, "female");
        vocabulary.terms = new LinkedHashSet<>(Set.of(retained, term(vocabulary, "male")));

        SampleVocabularyManagementService.reconcileTerms(vocabulary, List.of("female", "unknown"));

        assertEquals(
                Set.of("female", "unknown"),
                vocabulary.terms.stream().map(term -> term.value).collect(java.util.stream.Collectors.toSet()));
        assertTrue(vocabulary.terms.contains(retained));
    }

    private static SampleVocabularyTerm term(SampleVocabulary vocabulary, String value) {
        SampleVocabularyTerm term = new SampleVocabularyTerm();
        term.vocabulary = vocabulary;
        term.value = value;
        return term;
    }
}