package no.metatrack.server.assay.vocabulary;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class AssayVocabularyService {
    public List<AssayValidationViolation> validate(String assay, Map<String, String> values) {
        return validate(loadRules(), assay, values);
    }

    public AssayVocabularyRules loadRules() {
        Map<String, Set<String>> allowedTerms = GlobalAssayVocabulary.<GlobalAssayVocabulary>listAll().stream()
                .filter(vocabulary -> AssayVocabularyBuiltInCatalog.find(vocabulary.fieldKey).isPresent())
                .collect(Collectors.toMap(
                        vocabulary -> vocabulary.fieldKey,
                        vocabulary -> vocabulary.terms.stream().map(term -> term.value).collect(Collectors.toSet())));
        return new AssayVocabularyRules(allowedTerms);
    }

    public static List<AssayValidationViolation> validate(
            AssayVocabularyRules rules, String assay, Map<String, String> values) {
        List<AssayValidationViolation> violations = new ArrayList<>();
        values.forEach((fieldKey, value) -> {
            if (AssayVocabularyBuiltInCatalog.find(fieldKey).isEmpty()) return;
            Set<String> allowed = rules.allowedTerms().get(fieldKey);
            if (allowed == null || allowed.isEmpty() || value == null || value.trim().isEmpty()) return;
            if (!allowed.contains(value.trim())) {
                violations.add(new AssayValidationViolation(
                        assay, fieldKey, value, "Value is not in the configured vocabulary"));
            }
        });
        return List.copyOf(violations);
    }
}