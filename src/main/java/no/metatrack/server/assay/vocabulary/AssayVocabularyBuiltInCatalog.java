package no.metatrack.server.assay.vocabulary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AssayVocabularyBuiltInCatalog {
    private static final List<AssayVocabularyColumn> COLUMNS = List.of(
            column("study_accession", "Study accession"),
            column("instrument_model", "Instrument model"),
            column("sequencing_platform", "Sequencing platform"),
            column("sequencing_laboratory", "Sequencing laboratory"),
            column("library_name", "Library name"),
            column("library_source", "Library source"),
            column("library_selection", "Library selection"),
            column("library_strategy", "Library strategy"),
            column("library_layout", "Library layout"));
    private static final Map<String, AssayVocabularyColumn> BY_KEY = COLUMNS.stream()
            .collect(Collectors.toUnmodifiableMap(AssayVocabularyColumn::key, Function.identity()));

    private AssayVocabularyBuiltInCatalog() {}

    public static List<AssayVocabularyColumn> columns() {
        return COLUMNS;
    }

    public static Optional<AssayVocabularyColumn> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    private static AssayVocabularyColumn column(String key, String label) {
        return new AssayVocabularyColumn(key, label, false);
    }
}