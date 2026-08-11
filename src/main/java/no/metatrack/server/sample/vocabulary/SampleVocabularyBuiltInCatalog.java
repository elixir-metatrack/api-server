package no.metatrack.server.sample.vocabulary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SampleVocabularyBuiltInCatalog {
    private static final List<SampleVocabularyColumn> COLUMNS = List.of(
            column("alias", "Alias"),
            column("mlst", "MLST"),
            column("project_title", "Project title"),
            column("description", "Description"),
            column("isolate", "Isolate"),
            column("collected_by", "Collected by"),
            column("environmental_sample", "Environmental sample"),
            column("host_associated", "Host associated"),
            column("host_common_name", "Host common name"),
            column("host_subject_id", "Host subject ID"),
            column("collector_name", "Collector name"),
            column("collecting_institution", "Collecting institution"),
            column("host_sex", "Host sex"),
            column("influenza_test_method", "Influenza test method"),
            column("influenza_test_result", "Influenza test result"),
            column("other_pathogens_tested", "Other pathogens tested"),
            column("other_pathogens_test_result", "Other pathogens test result"),
            column("host_habitat", "Host habitat"),
            column("isolation_source_host_associated", "Isolation source, host associated"),
            column("host_behaviour", "Host behaviour"),
            column("isolation_source_non_host_associated", "Isolation source, non-host associated"),
            column("influenza_virus_type", "Influenza virus type"),
            column("influenza_sub_type", "Influenza subtype"),
            column("serovar", "Serovar"),
            column("strain", "Strain"),
            column("host_age", "Host age"),
            column("county", "County"),
            column("commune", "Commune"),
            column("hospital_health_institution", "Hospital/health institution"),
            column("isolation_source", "Isolation source"),
            column("location", "Location"),
            column("sequencing_lab", "Sequencing lab"),
            column("institution", "Institution"),
            column("host_health_state", "Host health state"));
    private static final Map<String, SampleVocabularyColumn> BY_KEY = COLUMNS.stream()
            .collect(Collectors.toUnmodifiableMap(SampleVocabularyColumn::key, Function.identity()));

    private SampleVocabularyBuiltInCatalog() {}

    public static List<SampleVocabularyColumn> columns() {
        return COLUMNS;
    }

    public static Optional<SampleVocabularyColumn> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    private static SampleVocabularyColumn column(String key, String label) {
        return new SampleVocabularyColumn(key, label, false);
    }
}