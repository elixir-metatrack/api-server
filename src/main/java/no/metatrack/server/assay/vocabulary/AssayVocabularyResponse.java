package no.metatrack.server.assay.vocabulary;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record AssayVocabularyResponse(
        UUID id,
        String fieldKey,
        String label,
        boolean custom,
        List<String> terms,
        Instant createdOn,
        Instant modifiedOn) {
    static AssayVocabularyResponse eligible(AssayVocabularyColumn column) {
        return new AssayVocabularyResponse(null, column.key(), column.label(), false, List.of(), null, null);
    }

    static AssayVocabularyResponse configured(AssayVocabularyColumn column, GlobalAssayVocabulary vocabulary) {
        List<String> values = vocabulary.terms.stream()
                .map(term -> term.value)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new AssayVocabularyResponse(
                vocabulary.id,
                column.key(),
                column.label(),
                false,
                values,
                vocabulary.createdOn,
                vocabulary.modifiedOn);
    }
}