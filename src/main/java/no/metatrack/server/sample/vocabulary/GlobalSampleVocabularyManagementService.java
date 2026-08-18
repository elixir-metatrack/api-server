package no.metatrack.server.sample.vocabulary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class GlobalSampleVocabularyManagementService {
    public List<SampleVocabularyResponse> list() {
        Map<String, GlobalSampleVocabulary> vocabularies = GlobalSampleVocabulary.<GlobalSampleVocabulary>listAll().stream()
                .collect(Collectors.toMap(vocabulary -> vocabulary.fieldKey, Function.identity()));
        return SampleVocabularyBuiltInCatalog.columns().stream()
                .map(column -> Optional.ofNullable(vocabularies.get(column.key()))
                        .map(vocabulary -> SampleVocabularyResponse.configured(column, vocabulary))
                        .orElseGet(() -> SampleVocabularyResponse.eligible(column)))
                .toList();
    }

    public SampleVocabularyResponse get(String fieldKey) {
        SampleVocabularyColumn column = requireEligibleColumn(fieldKey);
        return SampleVocabularyResponse.configured(column, findVocabulary(column.key()));
    }

    @Transactional
    public SampleVocabularyResponse replace(String fieldKey, PutSampleVocabularyRequest request) {
        SampleVocabularyColumn column = requireEligibleColumn(fieldKey);
        List<String> values = SampleVocabularyManagementService.validateTerms(request.terms());
        GlobalSampleVocabulary vocabulary = GlobalSampleVocabulary.<GlobalSampleVocabulary>find("fieldKey", column.key())
                .firstResultOptional()
                .orElseGet(() -> createVocabulary(column.key()));

        reconcileTerms(vocabulary, values);
        vocabulary.modifiedOn = Instant.now();
        return SampleVocabularyResponse.configured(column, vocabulary);
    }

    @Transactional
    public void delete(String fieldKey) {
        SampleVocabularyColumn column = requireEligibleColumn(fieldKey);
        findVocabulary(column.key()).delete();
    }

    SampleVocabularyColumn requireEligibleColumn(String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) throw new BadRequestException("Sample field key is required");
        String key = fieldKey.trim();
        return SampleVocabularyBuiltInCatalog.find(key)
                .orElseThrow(() -> new BadRequestException("Sample field '" + key + "' is not a built-in vocabulary field"));
    }

    static void reconcileTerms(GlobalSampleVocabulary vocabulary, List<String> values) {
        Set<String> replacements = new LinkedHashSet<>(values);
        vocabulary.terms.removeIf(term -> !replacements.contains(term.value));
        Set<String> retained = vocabulary.terms.stream().map(term -> term.value).collect(Collectors.toSet());
        replacements.stream().filter(value -> !retained.contains(value)).forEach(value -> {
            GlobalSampleVocabularyTerm term = new GlobalSampleVocabularyTerm();
            term.vocabulary = vocabulary;
            term.value = value;
            vocabulary.terms.add(term);
        });
    }

    private GlobalSampleVocabulary createVocabulary(String fieldKey) {
        Instant now = Instant.now();
        GlobalSampleVocabulary vocabulary = new GlobalSampleVocabulary();
        vocabulary.fieldKey = fieldKey;
        vocabulary.createdOn = now;
        vocabulary.modifiedOn = now;
        vocabulary.persist();
        return vocabulary;
    }

    private GlobalSampleVocabulary findVocabulary(String fieldKey) {
        return GlobalSampleVocabulary.<GlobalSampleVocabulary>find("fieldKey", fieldKey)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("Sample vocabulary not found"));
    }
}