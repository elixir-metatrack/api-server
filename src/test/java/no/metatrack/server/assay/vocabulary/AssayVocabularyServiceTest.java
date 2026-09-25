package no.metatrack.server.assay.vocabulary;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class AssayVocabularyServiceTest {
    @Test
    void validatesAllEligibleFieldsCaseSensitivelyAndPreservesRejectedValues() {
        Map<String, Set<String>> terms = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        AssayVocabularyBuiltInCatalog.columns().forEach(column -> {
            terms.put(column.key(), Set.of("allowed"));
            values.put(column.key(), " Allowed ");
        });
        var violations = AssayVocabularyService.validate(new AssayVocabularyRules(terms), "assay-1", values);

        assertEquals(9, violations.size());
        assertEquals(new AssayValidationViolation("assay-1", "study_accession", " Allowed ",
                "Value is not in the configured vocabulary"), violations.getFirst());
        assertEquals(values.keySet(), violations.stream().map(AssayValidationViolation::field)
                .collect(java.util.stream.Collectors.toSet()));
        assertThrows(UnsupportedOperationException.class, () -> violations.clear());
    }

    @Test
    void allowsTrimmedMatchesNullBlankAndUnconfiguredValuesWithoutChangingInput() {
        var rules = new AssayVocabularyRules(Map.of("library_layout", Set.of("PAIRED")));
        for (String value : new String[]{null, "", " \t ", " PAIRED "}) {
            Map<String, String> values = new HashMap<>();
            values.put("library_layout", value);
            values.put("library_name", "unrestricted");
            assertTrue(AssayVocabularyService.validate(rules, "assay-1", values).isEmpty());
            assertEquals(value, values.get("library_layout"));
        }
        assertTrue(AssayVocabularyService.validate(rules, "assay-1", Map.of()).isEmpty());
        assertTrue(AssayVocabularyService.validate(new AssayVocabularyRules(Map.of()), "assay-1",
                Map.of("library_layout", "legacy")).isEmpty());
    }

    @Test
    void rulesAreDeeplyImmutableSnapshots() {
        Set<String> terms = new HashSet<>(Set.of("PAIRED"));
        Map<String, Set<String>> source = new HashMap<>(Map.of("library_layout", terms));
        var rules = new AssayVocabularyRules(source);
        terms.clear();
        source.clear();
        assertEquals(Set.of("PAIRED"), rules.allowedTerms().get("library_layout"));
        assertThrows(UnsupportedOperationException.class, () -> rules.allowedTerms().clear());
        assertThrows(UnsupportedOperationException.class, () -> rules.allowedTerms().get("library_layout").clear());
    }

    @Test
    void loadsOnlyGlobalEligibleRulesAndValidatesWithLoadedRules() {
        GlobalAssayVocabulary vocabulary = new GlobalAssayVocabulary();
        vocabulary.fieldKey = "sequencing_platform";
        GlobalAssayVocabularyTerm term = new GlobalAssayVocabularyTerm();
        term.value = "ILLUMINA";
        vocabulary.terms.add(term);
        GlobalAssayVocabulary emptyVocabulary = new GlobalAssayVocabulary();
        emptyVocabulary.fieldKey = "library_layout";
        GlobalAssayVocabulary excluded = new GlobalAssayVocabulary();
        excluded.fieldKey = "name";
        try (MockedStatic<PanacheEntityBase> store = mockStatic(PanacheEntityBase.class)) {
            store.when(GlobalAssayVocabulary::listAll).thenReturn(List.of(vocabulary, emptyVocabulary, excluded));
            AssayVocabularyService service = new AssayVocabularyService();
            assertEquals(Map.of("sequencing_platform", Set.of("ILLUMINA"), "library_layout", Set.of()),
                    service.loadRules().allowedTerms());
            assertTrue(service.validate("assay-1", Map.of("library_layout", "initial value")).isEmpty());
            assertEquals(1, service.validate("assay-1", Map.of("sequencing_platform", "invalid")).size());
        }
    }

    @Test
    void mapperReturnsStructuredBadRequestAndExceptionCopiesViolations() throws Exception {
        var violation = new AssayValidationViolation("assay-1", "library_layout", "invalid", "message");
        var violations = new ArrayList<>(List.of(violation));
        var exception = new AssayVocabularyValidationException(violations);
        violations.clear();
        assertThrows(UnsupportedOperationException.class, () -> exception.violations().clear());
        try (var response = new AssayVocabularyValidationExceptionMapper().toResponse(exception)) {
            assertEquals(400, response.getStatus());
            assertEquals(List.of(violation), response.getEntity());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.readTree(mapper.writeValueAsString(response.getEntity()));
            assertEquals("assay-1", json.get(0).get("assay").asText());
            assertEquals("library_layout", json.get(0).get("field").asText());
            assertEquals("invalid", json.get(0).get("rejectedValue").asText());
            assertEquals("message", json.get(0).get("message").asText());
        }
    }
}