package no.metatrack.server.assay;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.vocabulary.AssayVocabularyRules;
import no.metatrack.server.assay.vocabulary.AssayVocabularyService;
import no.metatrack.server.assay.vocabulary.AssayVocabularyValidationException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssayServiceTest {
    private AssayService serviceWithRules(Map<String, Set<String>> terms) {
        AssayService service = new AssayService();
        service.vocabularyService = mock(AssayVocabularyService.class);
        when(service.vocabularyService.validate(any(), anyMap())).thenAnswer(invocation ->
                AssayVocabularyService.validate(new AssayVocabularyRules(terms),
                        invocation.getArgument(0), invocation.getArgument(1)));
        return service;
    }

    @Test
    void invalidCreateDoesNotAttachAssayToProject() {
        Project project = new Project();
        var service = serviceWithRules(Map.of("library_layout", Set.of("PAIRED")));
        try (MockedStatic<PanacheEntityBase> projects = mockStatic(PanacheEntityBase.class)) {
            projects.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            var exception = assertThrows(AssayVocabularyValidationException.class, () ->
                    service.createAssay(1L, "new", null, null, null, null, null, null, "invalid", 100));
            assertEquals("library_layout", exception.violations().getFirst().field());
            assertTrue(project.assays.isEmpty());
        }
    }

    @Test
    void createValidatesEveryRestFieldAndKeepsStrategySelectionOrder() {
        Map<String, Set<String>> terms = Map.of(
                "study_accession", Set.of("study"), "instrument_model", Set.of("instrument"),
                "library_name", Set.of("library"), "library_source", Set.of("source"),
                "library_strategy", Set.of("strategy"), "library_selection", Set.of("selection"),
                "library_layout", Set.of("layout"));
        var service = serviceWithRules(terms);
        Project project = new Project();
        try (MockedStatic<PanacheEntityBase> projects = mockStatic(PanacheEntityBase.class)) {
            projects.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            Assay assay = service.createAssay(1L, "new", "study", "instrument", "library", "source",
                    "strategy", "selection", " layout ", 100);
            assertTrue(project.assays.contains(assay));
            assertEquals("strategy", assay.libraryStrategy);
            assertEquals("selection", assay.librarySelection);
            assertEquals(" layout ", assay.libraryLayout);
            assertEquals(100, assay.insertSize);
            assertSame(project, assay.project);
            var exception = assertThrows(AssayVocabularyValidationException.class, () ->
                    service.createAssay(1L, "bad", "bad", "bad", "bad", "bad", "bad", "bad", "bad", null));
            assertEquals(7, exception.violations().size());
            assertEquals(1, project.assays.size());
        }
    }

    @Test
    void createAllowsNullBlankAndUnconfiguredValues() {
        var service = serviceWithRules(Map.of("library_layout", Set.of("PAIRED")));
        Project project = new Project();
        try (MockedStatic<PanacheEntityBase> projects = mockStatic(PanacheEntityBase.class)) {
            projects.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            for (String layout : new String[]{null, "", " \t "}) {
                Assay assay = service.createAssay(1L, "new", null, null, "unconfigured", null,
                        null, null, layout, null);
                assertEquals(layout, assay.libraryLayout);
                assertEquals("unconfigured", assay.libraryName);
                assertTrue(project.assays.contains(assay));
            }
        }
    }

    @Test
    void patchValidatesEveryRestFieldAndKeepsSelectionStrategyOrder() {
        var service = serviceWithRules(Map.of(
                "study_accession", Set.of("study"), "instrument_model", Set.of("instrument"),
                "library_name", Set.of("library"), "library_source", Set.of("source"),
                "library_selection", Set.of("selection"), "library_strategy", Set.of("strategy"),
                "library_layout", Set.of("layout")));
        Assay assay = new Assay();
        assay.name = "existing";
        UUID id = UUID.randomUUID();
        try (MockedStatic<Assay> assays = mockStatic(Assay.class)) {
            assays.when(() -> Assay.findByIdInProjectScope(1L, id)).thenReturn(Optional.of(assay));
            var exception = assertThrows(AssayVocabularyValidationException.class, () ->
                    service.updateAssay(1L, id, null, "bad", "bad", "bad", "bad", "bad", "bad", "bad", null));
            assertEquals(7, exception.violations().size());
            assertEquals("existing", exception.violations().getFirst().assay());
            service.updateAssay(1L, id, null, "study", "instrument", "library", "source",
                    "selection", "strategy", "layout", null);
            assertEquals("selection", assay.librarySelection);
            assertEquals("strategy", assay.libraryStrategy);
            assertEquals("study", assay.studyAccession);
            assertEquals("instrument", assay.instrumentModel);
            assertEquals("library", assay.libraryName);
            assertEquals("source", assay.librarySource);
            assertEquals("layout", assay.libraryLayout);
        }
    }

    @Test
    void invalidPatchDoesNotMutateAnyFieldsOrTimestamp() {
        Assay assay = new Assay();
        assay.name = "old";
        assay.libraryLayout = "legacy";
        assay.insertSize = 100;
        assay.modifiedOn = Instant.EPOCH;
        UUID id = UUID.randomUUID();
        var service = serviceWithRules(Map.of("library_layout", Set.of("PAIRED")));
        try (MockedStatic<Assay> assays = mockStatic(Assay.class)) {
            assays.when(() -> Assay.findByIdInProjectScope(1L, id)).thenReturn(Optional.of(assay));
            assertThrows(AssayVocabularyValidationException.class, () ->
                    service.updateAssay(1L, id, "new", "study", "instrument", "library", "source",
                            "selection", "strategy", "invalid", 200));
            assertEquals("old", assay.name);
            assertNull(assay.studyAccession);
            assertNull(assay.instrumentModel);
            assertNull(assay.libraryName);
            assertNull(assay.librarySource);
            assertNull(assay.librarySelection);
            assertNull(assay.libraryStrategy);
            assertEquals("legacy", assay.libraryLayout);
            assertEquals(100, assay.insertSize);
            assertEquals(Instant.EPOCH, assay.modifiedOn);
        }
    }

    @Test
    void unrelatedPatchAllowsLegacyValuesAndBlankOrAllowedSubmittedValues() {
        Assay assay = new Assay();
        assay.name = "old";
        assay.libraryLayout = "legacy";
        assay.sequencingPlatform = "legacy platform";
        UUID id = UUID.randomUUID();
        var service = serviceWithRules(Map.of("library_layout", Set.of("PAIRED"),
                "sequencing_platform", Set.of("ILLUMINA")));
        try (MockedStatic<Assay> assays = mockStatic(Assay.class)) {
            assays.when(() -> Assay.findByIdInProjectScope(1L, id)).thenReturn(Optional.of(assay));
            service.updateAssay(1L, id, "new", null, null, null, null, null, null, null, 200);
            assertEquals("new", assay.name);
            assertEquals("legacy", assay.libraryLayout);
            assertEquals("legacy platform", assay.sequencingPlatform);
            assertEquals(200, assay.insertSize);
            service.updateAssay(1L, id, null, null, null, "unconfigured", null, null, null, " \t ", null);
            assertEquals(" \t ", assay.libraryLayout);
            assertEquals("unconfigured", assay.libraryName);
            service.updateAssay(1L, id, null, null, null, null, null, null, null, "PAIRED", null);
            assertEquals("PAIRED", assay.libraryLayout);
            assertNotNull(assay.modifiedOn);
        }
    }
    @Test
    void rejectsSampleOutsideProjectBeforeLoadingAssays() {
        Long projectId = 1L;
        UUID sampleId = UUID.randomUUID();
        AssayService service = new AssayService();

        try (MockedStatic<Sample> sample = mockStatic(Sample.class)) {
            sample.when(() -> Sample.findByIdInProjectScope(sampleId, projectId)).thenReturn(Optional.empty());

            assertThrows(NotFoundException.class, () -> service.getAllAssaysInSample(projectId, sampleId));

            sample.verify(() -> Sample.getAllAssaysInSample(projectId, sampleId), never());
        }
    }
}