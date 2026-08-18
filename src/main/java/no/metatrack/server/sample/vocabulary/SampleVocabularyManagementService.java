package no.metatrack.server.sample.vocabulary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class SampleVocabularyManagementService {
    public List<SampleVocabularyResponse> list(Long projectId) {
        requireProject(projectId);
        Map<String, SampleVocabulary> vocabularies = SampleVocabulary.<SampleVocabulary>list("project.id", projectId).stream()
                .collect(Collectors.toMap(vocabulary -> vocabulary.fieldKey, Function.identity()));
        return eligibleColumns(projectId).stream()
                .map(column -> Optional.ofNullable(vocabularies.get(column.key()))
                        .map(vocabulary -> SampleVocabularyResponse.configured(column, vocabulary))
                        .orElseGet(() -> SampleVocabularyResponse.eligible(column)))
                .toList();
    }

    public SampleVocabularyResponse get(Long projectId, String fieldKey) {
        SampleVocabularyColumn column = requireEligibleColumn(projectId, fieldKey);
        SampleVocabulary vocabulary = findVocabulary(projectId, column.key());
        return SampleVocabularyResponse.configured(column, vocabulary);
    }

    @Transactional
    public SampleVocabularyResponse replace(Long projectId, String fieldKey, PutSampleVocabularyRequest request) {
        Project project = requireProject(projectId);
        SampleVocabularyColumn column = requireEligibleColumn(projectId, fieldKey);
        List<String> values = validateTerms(request.terms());
        SampleVocabulary vocabulary = SampleVocabulary.<SampleVocabulary>find(
                        "project.id = ?1 and fieldKey = ?2", projectId, column.key())
                .firstResultOptional()
                .orElseGet(() -> createVocabulary(project, column.key()));

        reconcileTerms(vocabulary, values);
        vocabulary.modifiedOn = Instant.now();
        return SampleVocabularyResponse.configured(column, vocabulary);
    }

    @Transactional
    public void delete(Long projectId, String fieldKey) {
        requireProject(projectId);
        SampleVocabularyColumn column = requireEligibleColumn(projectId, fieldKey);
        findVocabulary(projectId, column.key()).delete();
    }

    List<SampleVocabularyColumn> eligibleColumns(Long projectId) {
        return SampleMetadataField.<SampleMetadataField>list(
                        "project.id = ?1 and archivedOn is null and type = ?2 order by key",
                        projectId,
                        SampleMetadataFieldType.TEXT)
                .stream()
                .map(field -> new SampleVocabularyColumn(field.key, field.label, true))
                .toList();
    }

    SampleVocabularyColumn requireEligibleColumn(Long projectId, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) throw new BadRequestException("Sample field key is required");
        String key = fieldKey.trim();
        if (SampleVocabularyBuiltInCatalog.find(key).isPresent()) {
            throw new BadRequestException("Built-in sample fields use global vocabularies");
        }

        return SampleMetadataField.<SampleMetadataField>find(
                        "project.id = ?1 and key = ?2 and archivedOn is null and type = ?3",
                        projectId,
                        key,
                        SampleMetadataFieldType.TEXT)
                .firstResultOptional()
                .map(field -> new SampleVocabularyColumn(field.key, field.label, true))
                .orElseThrow(() -> new BadRequestException("Sample field '" + key + "' is not eligible for a vocabulary"));
    }

    static List<String> validateTerms(List<String> rawTerms) {
        if (rawTerms == null || rawTerms.isEmpty()) throw new BadRequestException("At least one vocabulary term is required");
        List<String> terms = new ArrayList<>(rawTerms.size());
        Set<String> unique = new HashSet<>();
        for (String rawTerm : rawTerms) {
            if (rawTerm == null || rawTerm.isBlank()) throw new BadRequestException("Vocabulary terms must not be blank");
            String term = rawTerm.trim();
            if (!unique.add(term)) throw new BadRequestException("Duplicate vocabulary term '" + term + "'");
            terms.add(term);
        }
        return List.copyOf(terms);
    }

    static void reconcileTerms(SampleVocabulary vocabulary, List<String> values) {
        Set<String> replacements = new LinkedHashSet<>(values);
        vocabulary.terms.removeIf(term -> !replacements.contains(term.value));
        Set<String> retained = vocabulary.terms.stream().map(term -> term.value).collect(Collectors.toSet());
        replacements.stream().filter(value -> !retained.contains(value)).forEach(value -> {
            SampleVocabularyTerm term = new SampleVocabularyTerm();
            term.vocabulary = vocabulary;
            term.value = value;
            vocabulary.terms.add(term);
        });
    }

    private SampleVocabulary createVocabulary(Project project, String fieldKey) {
        Instant now = Instant.now();
        SampleVocabulary vocabulary = new SampleVocabulary();
        vocabulary.project = project;
        vocabulary.fieldKey = fieldKey;
        vocabulary.createdOn = now;
        vocabulary.modifiedOn = now;
        vocabulary.persist();
        return vocabulary;
    }

    private SampleVocabulary findVocabulary(Long projectId, String fieldKey) {
        return SampleVocabulary.<SampleVocabulary>find(
                        "project.id = ?1 and fieldKey = ?2", projectId, fieldKey)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("Sample vocabulary not found"));
    }


    private Project requireProject(Long projectId) {
        return Project.<Project>findByIdOptional(projectId).orElseThrow(() -> new NotFoundException("Project not found"));
    }
}