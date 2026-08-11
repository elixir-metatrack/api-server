package no.metatrack.server.sample.vocabulary;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record SampleVocabularyResponse(
        UUID id,
        String fieldKey,
        String label,
        boolean custom,
        List<String> terms,
        Instant createdOn,
        Instant modifiedOn) {
    static SampleVocabularyResponse eligible(SampleVocabularyColumn column) {
        return new SampleVocabularyResponse(null, column.key(), column.label(), column.custom(), List.of(), null, null);
    }

    static SampleVocabularyResponse configured(SampleVocabularyColumn column, SampleVocabulary vocabulary) {
        List<String> values = vocabulary.terms.stream()
                .map(term -> term.value)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new SampleVocabularyResponse(
                vocabulary.id,
                column.key(),
                column.label(),
                column.custom(),
                values,
                vocabulary.createdOn,
                vocabulary.modifiedOn);
    }
}