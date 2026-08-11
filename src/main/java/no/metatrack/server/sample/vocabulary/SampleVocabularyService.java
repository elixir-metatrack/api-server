package no.metatrack.server.sample.vocabulary;

import jakarta.enterprise.context.ApplicationScoped;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class SampleVocabularyService {
    public List<SampleValidationViolation> validate(
            Long projectId,
            String sample,
            Map<String, String> builtInValues,
            Map<String, Object> customMetadata) {
        return validate(loadRules(projectId), sample, builtInValues, customMetadata);
    }

    public SampleVocabularyRules loadRules(Long projectId) {
        Set<String> activeTextKeys = SampleMetadataField.<SampleMetadataField>list(
                        "project.id = ?1 and archivedOn is null and type = ?2",
                        projectId,
                        SampleMetadataFieldType.TEXT)
                .stream()
                .map(field -> field.key)
                .collect(Collectors.toSet());
        Map<String, Set<String>> allowedTerms = SampleVocabulary.<SampleVocabulary>list("project.id", projectId).stream()
                .collect(Collectors.toMap(
                        vocabulary -> vocabulary.fieldKey,
                        vocabulary -> vocabulary.terms.stream().map(term -> term.value).collect(Collectors.toSet())));
        return new SampleVocabularyRules(activeTextKeys, allowedTerms);
    }

    public static List<SampleValidationViolation> validate(
            SampleVocabularyRules rules,
            String sample,
            Map<String, String> builtInValues,
            Map<String, Object> customMetadata) {
        Map<String, Object> candidates = new LinkedHashMap<>();
        builtInValues.forEach((key, value) -> {
            if (value != null) candidates.put(key, value);
        });
        if (customMetadata != null && !customMetadata.isEmpty()) {
            customMetadata.forEach((key, value) -> {
                if (value instanceof String && rules.activeTextKeys().contains(key)) candidates.put(key, value);
            });
        }
        return findViolations(sample, candidates, rules.allowedTerms());
    }

    static List<SampleValidationViolation> findViolations(
            String sample,
            Map<String, Object> candidates,
            Map<String, Set<String>> allowedTerms) {
        List<SampleValidationViolation> violations = new ArrayList<>();
        candidates.forEach((fieldKey, rejectedValue) -> {
            Set<String> allowed = allowedTerms.get(fieldKey);
            if (allowed == null || rejectedValue == null) return;
            String normalized = rejectedValue instanceof String text ? text.trim() : rejectedValue.toString();
            if (normalized.isEmpty()) return;
            if (!allowed.contains(normalized)) {
                violations.add(new SampleValidationViolation(
                        sample,
                        fieldKey,
                        rejectedValue,
                        "Value is not in the configured vocabulary"));
            }
        });
        return List.copyOf(violations);
    }
}