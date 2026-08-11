package no.metatrack.server.sample.vocabulary;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record SampleVocabularyRules(Set<String> activeTextKeys, Map<String, Set<String>> allowedTerms) {
    public SampleVocabularyRules {
        activeTextKeys = Set.copyOf(activeTextKeys);
        allowedTerms = allowedTerms.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }
}