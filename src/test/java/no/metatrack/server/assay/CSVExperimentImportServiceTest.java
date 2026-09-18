package no.metatrack.server.assay;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import no.metatrack.server.assay.vocabulary.AssayVocabularyRules;
import no.metatrack.server.assay.vocabulary.AssayVocabularyService;
import no.metatrack.server.csv.CSVImportSupport;
import no.metatrack.server.file.File;
import no.metatrack.server.sample.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CSVExperimentImportServiceTest {
    private static final String HEADER = "Sample,Sequencing instrument,Library Name,Library Source,Library Selection,"
            + "Library Strategy,Library Layout,Sequencing platform,Sequencing Laboratory,Insert Size,"
            + "File Name,File md5,Forward File Name,Forward File md5,Reverse File Name,Reverse File md5\n";

    @TempDir
    Path directory;

    private final Assay assay = spy(new Assay());
    private final Sample sample = new Sample();
    private final CSVExperimentImportService service = new CSVExperimentImportService();

    private List<CSVExperimentRowError> importRows(Map<String, Set<String>> terms, String rows) throws Exception {
        assay.id = UUID.randomUUID();
        assay.name = "assay";
        sample.name = "sample";
        service.csvImportSupport = new CSVImportSupport();
        service.vocabularyService = mock(AssayVocabularyService.class);
        when(service.vocabularyService.loadRules()).thenReturn(new AssayVocabularyRules(terms));
        Path csv = Files.writeString(directory.resolve("experiments.csv"), HEADER + rows);
        PanacheQuery<Assay> assays = mock(PanacheQuery.class);
        PanacheQuery<Sample> samples = mock(PanacheQuery.class);
        when(assays.firstResultOptional()).thenReturn(Optional.of(assay));
        when(samples.firstResultOptional()).thenReturn(Optional.of(sample));
        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            entities.when(() -> Assay.find("id = ?1 and project.id = ?2", assay.id, 1L)).thenReturn(assays);
            entities.when(() -> Sample.find(
                    "project.id = ?1 and name = ?2 and exists (select 1 from Assay a join a.samples s "
                            + "where a = ?3 and s = Sample)", 1L, "sample", assay)).thenReturn(samples);
            return service.importIntoAssay(1L, assay.id, csv.toFile());
        }
    }

    private String row(String platform, String laboratory, String insertSize) {
        return "sample,instrument,library,source,selection,strategy,PAIRED," + platform + "," + laboratory + ","
                + insertSize + ",reads.fastq,md5,forward.fastq,md5,reverse.fastq,md5\n";
    }

    @Test
    void invalidMetadataReportsCsvHeadersWithoutAnySideEffects() throws Exception {
        assay.instrumentModel = "legacy instrument";
        assay.libraryName = "legacy library";
        assay.sequencingPlatform = "legacy platform";
        assay.sequencingLaboratory = "legacy laboratory";
        assay.insertSize = 50;
        assay.modifiedOn = Instant.EPOCH;
        Map<String, Set<String>> terms = Map.of(
                "instrument_model", Set.of("allowed"), "library_name", Set.of("allowed"),
                "library_source", Set.of("allowed"), "library_selection", Set.of("allowed"),
                "library_strategy", Set.of("allowed"), "library_layout", Set.of("allowed"),
                "sequencing_platform", Set.of("ILLUMINA"), "sequencing_laboratory", Set.of("Lab"));
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(terms, row("invalid", "invalid", "100"));
            assertEquals(8, errors.size());
            assertEquals(Set.of("Sequencing instrument", "Library Name", "Library Source", "Library Selection",
                    "Library Strategy", "Library Layout", "Sequencing platform", "Sequencing Laboratory"),
                    errors.stream().map(CSVExperimentRowError::field).collect(Collectors.toSet()));
            assertTrue(errors.stream().allMatch(error -> error.row().equals("Row 1")));
            assertEquals("invalid", errors.stream().filter(error -> error.field().equals("Sequencing platform"))
                    .findFirst().orElseThrow().value());
            assertEquals("legacy instrument", assay.instrumentModel);
            assertEquals("legacy library", assay.libraryName);
            assertEquals("legacy platform", assay.sequencingPlatform);
            assertEquals("legacy laboratory", assay.sequencingLaboratory);
            assertNull(assay.librarySource);
            assertNull(assay.librarySelection);
            assertNull(assay.libraryStrategy);
            assertNull(assay.libraryLayout);
            assertEquals(50, assay.insertSize);
            assertEquals(Instant.EPOCH, assay.modifiedOn);
            assertTrue(assay.samples.isEmpty());
            assertTrue(sample.assays.isEmpty());
            verify(assay, never()).addSample(any());
            files.verifyNoInteractions();
        }
        verify(service.vocabularyService, times(1)).loadRules();
    }

    @Test
    void laterValidRowCanReuseRejectedRowsFileReferences() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of("sequencing_platform", Set.of("ILLUMINA"),
                            "sequencing_laboratory", Set.of("Lab")),
                    row("bad", "Lab", "100") + row("ILLUMINA", "bad", "100")
                            + row("ILLUMINA", "Lab", "200"));
            assertEquals(2, errors.size());
            assertEquals("Sequencing platform", errors.get(0).field());
            assertEquals("Sequencing Laboratory", errors.get(1).field());
            assertEquals("ILLUMINA", assay.sequencingPlatform);
            assertEquals("Lab", assay.sequencingLaboratory);
            assertEquals(200, assay.insertSize);
            assertEquals("selection", assay.librarySelection);
            assertEquals("strategy", assay.libraryStrategy);
            assertTrue(assay.samples.contains(sample));
            assertTrue(sample.assays.contains(assay));
            verify(assay, times(1)).addSample(sample);
            for (String name : List.of("reads.fastq", "forward.fastq", "reverse.fastq")) {
                files.verify(() -> File.importPending(1L, assay.id, sample, assay, name, "md5", null), times(1));
                files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, name, "md5", null), times(1));
            }
            files.verifyNoMoreInteractions();
        }
        verify(service.vocabularyService, times(1)).loadRules();
    }

    @Test
    void blankAndUnconfiguredMetadataRemainAllowed() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of("sequencing_platform", Set.of("ILLUMINA")), row(" ", "any lab", ""));
            assertTrue(errors.isEmpty());
            assertEquals("", assay.sequencingPlatform);
            assertEquals("any lab", assay.sequencingLaboratory);
            assertNull(assay.insertSize);
            verify(assay).addSample(sample);
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null));
        }
    }

    @Test
    void duplicateWithinRowDoesNotReserveReferencesForLaterRows() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(),
                    row("platform", "lab", "100").replace("forward.fastq", "reads.fastq")
                            + row("platform", "lab", "200"));
            assertEquals(1, errors.size());
            assertEquals("Row 1", errors.getFirst().row());
            assertEquals("Forward File Name", errors.getFirst().field());
            assertEquals("Duplicate file reference in import", errors.getFirst().message());
            assertEquals(200, assay.insertSize);
            verify(assay, times(1)).addSample(sample);
            for (String name : List.of("reads.fastq", "forward.fastq", "reverse.fastq")) {
                files.verify(() -> File.importPending(1L, assay.id, sample, assay, name, "md5", null), times(1));
            }
        }
    }

    @Test
    void invalidInsertSizeDoesNotReserveReferencesButSuccessfulRowsDo() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), row("platform", "lab", "invalid")
                    + row("platform", "lab", "200") + row("platform", "lab", "300"));
            assertEquals(4, errors.size());
            assertEquals("Insert Size", errors.getFirst().field());
            assertTrue(errors.subList(1, 4).stream().allMatch(error -> error.row().equals("Row 3")
                    && error.message().equals("Duplicate file reference in import")));
            assertEquals(200, assay.insertSize);
            verify(assay, times(1)).addSample(sample);
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null), times(1));
        }
    }
}