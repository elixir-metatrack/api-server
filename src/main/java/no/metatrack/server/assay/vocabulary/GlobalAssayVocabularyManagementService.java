package no.metatrack.server.assay.vocabulary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class GlobalAssayVocabularyManagementService {
    public List<AssayVocabularyResponse> list() {
        Map<String, GlobalAssayVocabulary> vocabularies = GlobalAssayVocabulary.<GlobalAssayVocabulary>listAll().stream()
                .collect(Collectors.toMap(vocabulary -> vocabulary.fieldKey, Function.identity()));
        return AssayVocabularyBuiltInCatalog.columns().stream()
                .map(column -> Optional.ofNullable(vocabularies.get(column.key()))
                        .map(vocabulary -> AssayVocabularyResponse.configured(column, vocabulary))
                        .orElseGet(() -> AssayVocabularyResponse.eligible(column)))
                .toList();
    }

    public AssayVocabularyResponse get(String fieldKey) {
        AssayVocabularyColumn column = requireEligibleColumn(fieldKey);
        return AssayVocabularyResponse.configured(column, findVocabulary(column.key()));
    }

    @Transactional
    public AssayVocabularyResponse replace(String fieldKey, PutAssayVocabularyRequest request) {
        AssayVocabularyColumn column = requireEligibleColumn(fieldKey);
        List<String> values = validateTerms(request == null ? null : request.terms());
        GlobalAssayVocabulary vocabulary = GlobalAssayVocabulary.<GlobalAssayVocabulary>find("fieldKey", column.key())
                .firstResultOptional()
                .orElseGet(() -> createVocabulary(column.key()));

        reconcileTerms(vocabulary, values);
        vocabulary.modifiedOn = Instant.now();
        return AssayVocabularyResponse.configured(column, vocabulary);
    }

    @Transactional
    public void delete(String fieldKey) {
        AssayVocabularyColumn column = requireEligibleColumn(fieldKey);
        findVocabulary(column.key()).delete();
    }

    AssayVocabularyColumn requireEligibleColumn(String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) throw new BadRequestException("Assay field key is required");
        String key = fieldKey.trim();
        return AssayVocabularyBuiltInCatalog.find(key)
                .orElseThrow(() -> new BadRequestException("Assay field '" + key + "' is not a built-in vocabulary field"));
    }

    static List<String> validateTerms(List<String> rawTerms) {
        if (rawTerms == null || rawTerms.isEmpty()) throw new BadRequestException("At least one vocabulary term is required");
        List<String> terms = new ArrayList<>(rawTerms.size());
        Set<String> unique = new HashSet<>();
        for (String rawTerm : rawTerms) {
            if (rawTerm == null || rawTerm.isBlank()) throw new BadRequestException("Vocabulary terms must not be blank");
            String term = rawTerm.trim();
            if (term.isBlank()) throw new BadRequestException("Vocabulary terms must not be blank");
            if (!unique.add(term)) throw new BadRequestException("Duplicate vocabulary term '" + term + "'");
            terms.add(term);
        }
        return List.copyOf(terms);
    }

    static void reconcileTerms(GlobalAssayVocabulary vocabulary, List<String> values) {
        Set<String> replacements = new LinkedHashSet<>(values);
        vocabulary.terms.removeIf(term -> !replacements.contains(term.value));
        Set<String> retained = vocabulary.terms.stream().map(term -> term.value).collect(Collectors.toSet());
        replacements.stream().filter(value -> !retained.contains(value)).forEach(value -> {
            GlobalAssayVocabularyTerm term = new GlobalAssayVocabularyTerm();
            term.vocabulary = vocabulary;
            term.value = value;
            vocabulary.terms.add(term);
        });
    }

    private GlobalAssayVocabulary createVocabulary(String fieldKey) {
        Instant now = Instant.now();
        GlobalAssayVocabulary vocabulary = new GlobalAssayVocabulary();
        vocabulary.fieldKey = fieldKey;
        vocabulary.createdOn = now;
        vocabulary.modifiedOn = now;
        vocabulary.persist();
        return vocabulary;
    }

    private GlobalAssayVocabulary findVocabulary(String fieldKey) {
        return GlobalAssayVocabulary.<GlobalAssayVocabulary>find("fieldKey", fieldKey)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("Assay vocabulary not found"));
    }
}