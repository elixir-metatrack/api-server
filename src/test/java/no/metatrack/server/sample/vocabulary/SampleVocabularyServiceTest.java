package no.metatrack.server.sample.vocabulary;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleVocabularyServiceTest {
    @Test
    void acceptsExactValuesAfterTrimmingAndLeavesUnconfiguredFieldsUnrestricted() {
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("host_sex", " female ");
        candidates.put("location", "anywhere");

        assertTrue(SampleVocabularyService.findViolations(
                "sample-1", candidates, Map.of("host_sex", Set.of("female"))).isEmpty());
    }

    @Test
    void acceptsEmptyValuesForControlledFields() {
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("host_sex", "");
        candidates.put("status", "   ");

        assertTrue(SampleVocabularyService.findViolations(
                "sample-1",
                candidates,
                Map.of("host_sex", Set.of("female"), "status", Set.of("known")))
                .isEmpty());
    }

    @Test
    void acceptsValuesWhenConfiguredVocabularyIsEmpty() {
        assertTrue(SampleVocabularyService.findViolations(
                "sample-1", Map.of("status", "initial value"), Map.of("status", Set.of())).isEmpty());
    }

    @Test
    void appliesEmptyAndPopulatedGlobalBuiltInVocabularies() {
        SampleVocabularyRules emptyGlobalRules = SampleVocabularyService.composeRules(
                Set.of(), Map.of("host_sex", Set.of()), Map.of());
        SampleVocabularyRules populatedGlobalRules = SampleVocabularyService.composeRules(
                Set.of(), Map.of("host_sex", Set.of("female")), Map.of());

        assertTrue(SampleVocabularyService.validate(
                emptyGlobalRules, "sample-1", Map.of("host_sex", "initial value"), Map.of()).isEmpty());
        assertEquals(1, SampleVocabularyService.validate(
                populatedGlobalRules, "sample-1", Map.of("host_sex", "male"), Map.of()).size());
    }

    @Test
    void reportsEveryCaseSensitiveMismatchWithContext() {
        Map<String, Object> candidates = new LinkedHashMap<>();
        candidates.put("host_sex", "Female");
        candidates.put("status", "unknown");

        var violations = SampleVocabularyService.findViolations(
                "sample-1",
                candidates,
                Map.of("host_sex", Set.of("female"), "status", Set.of("known")));

        assertEquals(2, violations.size());
        assertEquals(new SampleValidationViolation(
                "sample-1", "host_sex", "Female", "Value is not in the configured vocabulary"), violations.getFirst());
    }

    @Test
    void removingVocabularyRestoresUnrestrictedInput() {
        assertTrue(SampleVocabularyService.findViolations(
                "sample-1", Map.of("host_sex", "historical"), Map.of()).isEmpty());
    }

    @Test
    void composesGlobalBuiltInAndActiveProjectCustomRules() {
        SampleVocabularyRules rules = SampleVocabularyService.composeRules(
                Set.of("status"),
                Map.of("host_sex", Set.of("female")),
                Map.of("status", Set.of("known"), "archived", Set.of("legacy")));

        var violations = SampleVocabularyService.validate(
                rules,
                "sample-1",
                Map.of("host_sex", "unknown"),
                Map.of("status", "unknown", "archived", "anything"));

        assertEquals(2, violations.size());
        assertEquals(Set.of("host_sex", "status"), violations.stream()
                .map(SampleValidationViolation::fieldKey)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void projectRulesCannotOverrideGlobalBuiltInRules() {
        SampleVocabularyRules rules = SampleVocabularyService.composeRules(
                Set.of("host_sex"),
                Map.of("host_sex", Set.of("female")),
                Map.of("host_sex", Set.of("project-specific")));

        assertEquals(Set.of("female"), rules.allowedTerms().get("host_sex"));
    }
}