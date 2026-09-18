package no.metatrack.server.assay.vocabulary;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record AssayVocabularyRules(Map<String, Set<String>> allowedTerms) {
    public AssayVocabularyRules {
        allowedTerms = allowedTerms.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }
}