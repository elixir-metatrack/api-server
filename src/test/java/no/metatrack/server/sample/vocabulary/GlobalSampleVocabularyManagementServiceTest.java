package no.metatrack.server.sample.vocabulary;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GlobalSampleVocabularyManagementServiceTest {
    @Test
    void acceptsOnlyBuiltInVocabularyFields() {
        GlobalSampleVocabularyManagementService service = new GlobalSampleVocabularyManagementService();

        SampleVocabularyColumn column = service.requireEligibleColumn(" host_sex ");

        assertEquals("host_sex", column.key());
        assertFalse(column.custom());
        assertThrows(BadRequestException.class, () -> service.requireEligibleColumn("custom_field"));
        assertThrows(BadRequestException.class, () -> service.requireEligibleColumn(" "));
    }

    @Test
    void reconcilesGlobalTermsWithoutReplacingOverlappingEntities() {
        GlobalSampleVocabulary vocabulary = new GlobalSampleVocabulary();
        GlobalSampleVocabularyTerm retained = term(vocabulary, "female");
        vocabulary.terms = new LinkedHashSet<>(Set.of(retained, term(vocabulary, "male")));

        GlobalSampleVocabularyManagementService.reconcileTerms(vocabulary, List.of("female", "unknown"));

        assertEquals(
                Set.of("female", "unknown"),
                vocabulary.terms.stream().map(term -> term.value).collect(Collectors.toSet()));
        assertTrue(vocabulary.terms.contains(retained));
    }

    private static GlobalSampleVocabularyTerm term(GlobalSampleVocabulary vocabulary, String value) {
        GlobalSampleVocabularyTerm term = new GlobalSampleVocabularyTerm();
        term.vocabulary = vocabulary;
        term.value = value;
        return term;
    }
}