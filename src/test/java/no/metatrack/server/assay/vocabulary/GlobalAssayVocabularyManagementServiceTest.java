package no.metatrack.server.assay.vocabulary;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalAssayVocabularyManagementServiceTest {
    private final GlobalAssayVocabularyManagementService service = new GlobalAssayVocabularyManagementService();

    @Test
    void acceptsAllNineBuiltInFieldsAndTrimsKeys() {
        List<String> keys = List.of("study_accession", "instrument_model", "sequencing_platform",
                "sequencing_laboratory", "library_name", "library_source", "library_selection",
                "library_strategy", "library_layout");
        for (String key : keys) {
            AssayVocabularyColumn column = service.requireEligibleColumn(" " + key + " ");
            assertEquals(key, column.key());
            assertFalse(column.custom());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "custom_field", "unknown", "name", "id", "project", "samples",
            "created_on", "modified_on", "insert_size", "libraryLayout", "LIBRARY_LAYOUT"})
    void rejectsIneligibleKeysBeforeAccessingPersistence(String key) {
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            assertEquals(400, assertThrows(BadRequestException.class, () -> service.get(key)).getResponse().getStatus());
            assertEquals(400, assertThrows(BadRequestException.class,
                    () -> service.replace(key, new PutAssayVocabularyRequest(List.of("value")))).getResponse().getStatus());
            assertEquals(400, assertThrows(BadRequestException.class, () -> service.delete(key)).getResponse().getStatus());
            persistence.verifyNoInteractions();
        }
    }

    @Test
    void listsConfiguredVocabularyAndAllUnconfiguredPlaceholders() {
        GlobalAssayVocabulary vocabulary = vocabulary("library_layout", "single", "paired");
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            persistence.when(() -> GlobalAssayVocabulary.listAll()).thenReturn(List.of(vocabulary));

            List<AssayVocabularyResponse> responses = service.list();

            assertEquals(AssayVocabularyBuiltInCatalog.columns().stream().map(AssayVocabularyColumn::key).toList(),
                    responses.stream().map(AssayVocabularyResponse::fieldKey).toList());
            assertEquals(9, responses.size());
            for (AssayVocabularyResponse response : responses) {
                assertFalse(response.custom());
                assertEquals(AssayVocabularyBuiltInCatalog.find(response.fieldKey()).orElseThrow().label(), response.label());
                if (response.fieldKey().equals("library_layout")) {
                    assertEquals(vocabulary.id, response.id());
                    assertEquals(List.of("paired", "single"), response.terms());
                    assertEquals(vocabulary.createdOn, response.createdOn());
                    assertEquals(vocabulary.modifiedOn, response.modifiedOn());
                } else {
                    assertNull(response.id());
                    assertEquals(List.of(), response.terms());
                    assertNull(response.createdOn());
                    assertNull(response.modifiedOn());
                }
            }
        }
    }

    @Test
    void readsConfiguredVocabularyWithSortedCaseSensitiveTerms() {
        GlobalAssayVocabulary vocabulary = vocabulary("library_layout", "single", "paired", "Paired");
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            stubVocabulary(persistence, vocabulary.fieldKey, Optional.of(vocabulary));

            AssayVocabularyResponse response = service.get(" library_layout ");

            assertEquals(vocabulary.id, response.id());
            assertEquals("library_layout", response.fieldKey());
            assertEquals("Library layout", response.label());
            assertFalse(response.custom());
            assertEquals(List.of("Paired", "paired", "single"), response.terms());
            assertEquals(vocabulary.createdOn, response.createdOn());
            assertEquals(vocabulary.modifiedOn, response.modifiedOn());
        }
    }

    @Test
    void missingReadAndDeleteReturnNotFound() {
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            stubVocabulary(persistence, "library_layout", Optional.empty());

            assertEquals(404, assertThrows(NotFoundException.class,
                    () -> service.get("library_layout")).getResponse().getStatus());
            assertEquals(404, assertThrows(NotFoundException.class,
                    () -> service.delete("library_layout")).getResponse().getStatus());
        }
    }

    @Test
    void rejectsInvalidTermsBeforeAccessingOrMutatingPersistence() {
        List<PutAssayVocabularyRequest> requests = Arrays.asList(null, new PutAssayVocabularyRequest(null),
                new PutAssayVocabularyRequest(List.of()), new PutAssayVocabularyRequest(Arrays.asList("valid", null)),
                new PutAssayVocabularyRequest(List.of("valid", "")), new PutAssayVocabularyRequest(List.of("valid", " \t\n")),
                new PutAssayVocabularyRequest(List.of("paired", " paired ")),
                new PutAssayVocabularyRequest(List.of("paired", "paired")),
                new PutAssayVocabularyRequest(List.of("\u2003")), new PutAssayVocabularyRequest(List.of("\u0000")));
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            for (PutAssayVocabularyRequest request : requests) {
                assertEquals(400, assertThrows(BadRequestException.class,
                        () -> service.replace("library_layout", request)).getResponse().getStatus());
            }
            persistence.verifyNoInteractions();
        }
    }

    @Test
    void replacementNormalizesTermsAndRetainsOverlappingEntitiesAndCreationMetadata() {
        GlobalAssayVocabulary vocabulary = vocabulary("library_layout", "paired", "obsolete");
        GlobalAssayVocabularyTerm retained = vocabulary.terms.iterator().next();
        Set<GlobalAssayVocabularyTerm> originalCollection = vocabulary.terms;
        UUID retainedId = retained.id;
        Instant createdOn = vocabulary.createdOn;
        Instant modifiedOn = vocabulary.modifiedOn;
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            stubVocabulary(persistence, vocabulary.fieldKey, Optional.of(vocabulary));

            AssayVocabularyResponse response = service.replace(" library_layout ",
                    new PutAssayVocabularyRequest(List.of(" single ", " paired ", "Paired")));

            assertSame(originalCollection, vocabulary.terms);
            assertTrue(vocabulary.terms.contains(retained));
            assertEquals(retainedId, retained.id);
            assertEquals(List.of("Paired", "paired", "single"), response.terms());
            assertEquals(vocabulary.id, response.id());
            assertEquals(createdOn, response.createdOn());
            assertTrue(response.modifiedOn().isAfter(modifiedOn));
            assertEquals(vocabulary.modifiedOn, response.modifiedOn());
            vocabulary.terms.forEach(term -> assertSame(vocabulary, term.vocabulary));

            Set<GlobalAssayVocabularyTerm> existing = new LinkedHashSet<>(vocabulary.terms);
            service.replace("library_layout", new PutAssayVocabularyRequest(response.terms()));
            assertEquals(existing, vocabulary.terms);
        }
    }

    @Test
    void createsUnconfiguredVocabularyWithNormalizedTermsAndTimestamps() {
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class);
             MockedConstruction<GlobalAssayVocabulary> construction = mockConstruction(GlobalAssayVocabulary.class,
                     (vocabulary, context) -> {
                         vocabulary.terms = new LinkedHashSet<>();
                         doAnswer(invocation -> {
                             vocabulary.id = UUID.randomUUID();
                             return null;
                         }).when(vocabulary).persist();
                     })) {
            stubVocabulary(persistence, "library_layout", Optional.empty());

            AssayVocabularyResponse response = service.replace("library_layout",
                    new PutAssayVocabularyRequest(List.of(" single ", " paired ")));

            assertEquals(1, construction.constructed().size());
            GlobalAssayVocabulary vocabulary = construction.constructed().getFirst();
            verify(vocabulary).persist();
            assertEquals("library_layout", vocabulary.fieldKey);
            assertEquals(vocabulary.id, response.id());
            assertNotNull(response.id());
            assertNotNull(response.createdOn());
            assertFalse(response.modifiedOn().isBefore(response.createdOn()));
            assertEquals(List.of("paired", "single"), response.terms());
            vocabulary.terms.forEach(term -> assertSame(vocabulary, term.vocabulary));
        }
    }

    @Test
    void deletesConfiguredVocabulary() {
        GlobalAssayVocabulary vocabulary = mock(GlobalAssayVocabulary.class);
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            stubVocabulary(persistence, "library_layout", Optional.of(vocabulary));

            service.delete(" library_layout ");

            verify(vocabulary).delete();
        }
    }

    @Test
    void mutationsAreTransactional() throws NoSuchMethodException {
        assertTrue(GlobalAssayVocabularyManagementService.class.getMethod("replace", String.class,
                PutAssayVocabularyRequest.class).isAnnotationPresent(Transactional.class));
        assertTrue(GlobalAssayVocabularyManagementService.class.getMethod("delete", String.class)
                .isAnnotationPresent(Transactional.class));
    }

    private static void stubVocabulary(MockedStatic<PanacheEntityBase> persistence, String key,
                                       Optional<GlobalAssayVocabulary> vocabulary) {
        PanacheQuery<GlobalAssayVocabulary> query = mock(PanacheQuery.class);
        persistence.when(() -> GlobalAssayVocabulary.find("fieldKey", key)).thenReturn(query);
        when(query.firstResultOptional()).thenReturn(vocabulary);
    }

    private static GlobalAssayVocabulary vocabulary(String key, String... values) {
        GlobalAssayVocabulary vocabulary = new GlobalAssayVocabulary();
        vocabulary.id = UUID.randomUUID();
        vocabulary.fieldKey = key;
        vocabulary.createdOn = Instant.parse("2026-01-01T00:00:00Z");
        vocabulary.modifiedOn = vocabulary.createdOn;
        for (String value : values) {
            GlobalAssayVocabularyTerm term = new GlobalAssayVocabularyTerm();
            term.id = UUID.randomUUID();
            term.vocabulary = vocabulary;
            term.value = value;
            vocabulary.terms.add(term);
        }
        return vocabulary;
    }
}