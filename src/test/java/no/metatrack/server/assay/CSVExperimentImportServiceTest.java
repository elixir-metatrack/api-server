package no.metatrack.server.assay;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.assay.vocabulary.AssayVocabularyRules;
import no.metatrack.server.assay.vocabulary.AssayVocabularyService;
import no.metatrack.server.csv.CSVImportSupport;
import no.metatrack.server.file.File;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.project.Project;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.UploadStatus;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.spreadsheet.SpreadsheetTable;
import no.metatrack.server.spreadsheet.SpreadsheetExportWriter;
import no.metatrack.server.spreadsheet.SpreadsheetImportSupport;
import no.metatrack.server.spreadsheet.SpreadsheetTemplateService;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
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
        return importFile(terms, Files.writeString(directory.resolve("experiments.csv"), HEADER + rows));
    }

    private List<CSVExperimentRowError> importFile(Map<String, Set<String>> terms, Path path) throws Exception {
        return importFile(terms, path, Optional.of(sample));
    }

    private List<CSVExperimentRowError> importFile(Map<String, Set<String>> terms, Path path,
            Optional<Sample> projectSample) throws Exception {
        if (assay.id == null) assay.id = UUID.randomUUID();
        assay.name = "assay";
        assay.project = new Project();
        assay.project.id = 1L;
        sample.name = "sample";
        if (sample.id == null) sample.id = UUID.randomUUID();
        service.csvImportSupport = new CSVImportSupport();
        service.spreadsheetSupport = new SpreadsheetImportSupport();
        service.vocabularyService = mock(AssayVocabularyService.class);
        when(service.vocabularyService.loadRules()).thenReturn(new AssayVocabularyRules(terms));
        AssayService assayService = new AssayService();
        assayService.vocabularyService = service.vocabularyService;
        service.rowWriter = new CSVExperimentRowWriter();
        service.rowWriter.entityManager = mock(EntityManager.class);
        service.rowWriter.assayService = assayService;
        when(service.vocabularyService.validate(anyString(), anyMap())).thenAnswer(invocation ->
                AssayVocabularyService.validate(new AssayVocabularyRules(terms),
                        invocation.getArgument(0), invocation.getArgument(1)));
        try (MockedStatic<Assay> scopedAssays = mockStatic(Assay.class);
             MockedStatic<Sample> scopedSamples = mockStatic(Sample.class)) {
            scopedAssays.when(() -> Assay.findByIdInProjectScope(1L, assay.id)).thenReturn(Optional.of(assay));
            scopedSamples.when(() -> Sample.findBySampleNameInProject("sample", 1L)).thenReturn(projectSample);
            return service.importIntoAssay(1L, assay.id, path.toFile());
        }
    }

    @Test
    void importsExportedExperimentsIncludingStudyAccessionAndMultipleFilesPerRole() throws Exception {
        Assay source = new Assay();
        source.id = UUID.randomUUID();
        source.studyAccession = "PRJ1234";
        source.experimentAccession = "000012";
        source.runAccession = "ERR1234";
        source.libraryName = "00012";
        source.sequencingPlatform = "ILLUMINA";
        source.sequencingLaboratory = "Lab";
        source.insertSize = 1234;
        sample.id = UUID.randomUUID();
        sample.name = "sample";
        List<FileResponse> files = List.of(
                exportFile(source, "single.fastq", ReadRole.SINGLE),
                exportFile(source, "r1.fastq", ReadRole.FORWARD),
                exportFile(source, "r1-lane2.fastq", ReadRole.FORWARD),
                exportFile(source, "r2.fastq", ReadRole.REVERSE));
        Path path = Files.write(directory.resolve("export.xlsx"),
                new SpreadsheetExportWriter().experiments(source, List.of(sample), files));
        try (MockedStatic<File> fileEntities = mockStatic(File.class)) {
            assertTrue(importFile(Map.of(), path).isEmpty());
            for (FileResponse file : files) {
                fileEntities.verify(() -> File.importPending(1L, assay.id, sample, assay, file.name(),
                        file.md5(), file.unencryptedMd5(), file.readRole()));
            }
            assertEquals(source.studyAccession, assay.studyAccession);
            assertEquals(source.experimentAccession, assay.experimentAccession);
            assertEquals(source.runAccession, assay.runAccession);
            assertEquals(source.libraryName, assay.libraryName);
            assertEquals(source.sequencingPlatform, assay.sequencingPlatform);
            assertEquals(source.sequencingLaboratory, assay.sequencingLaboratory);
            assertEquals(source.insertSize, assay.insertSize);
        }
    }

    @Test
    void exportedStudyAccessionCannotReplaceExistingValue() throws Exception {
        assay.id = UUID.randomUUID();
        assay.studyAccession = "existing";
        Assay source = new Assay();
        source.id = assay.id;
        source.studyAccession = "different";
        sample.id = UUID.randomUUID();
        sample.name = "sample";
        Path path = Files.write(directory.resolve("conflict.xlsx"),
                new SpreadsheetExportWriter().experiments(source, List.of(sample), List.of()));
        var errors = importFile(Map.of(), path);
        assertEquals(1, errors.size());
        assertEquals("Study Accession", errors.getFirst().field());
        assertEquals("existing", assay.studyAccession);
    }

    private FileResponse exportFile(Assay source, String name, ReadRole role) {
        return new FileResponse(UUID.randomUUID(), name, null, null, UploadStatus.PENDING, null,
                sample.id, source.id, role, "md5", "plain-md5");
    }

    private String row(String platform, String laboratory, String insertSize) {
        return "sample,instrument,library,source,selection,strategy,PAIRED," + platform + "," + laboratory + ","
                + insertSize + ",reads.fastq,md5,forward.fastq,md5,reverse.fastq,md5\n";
    }

    @Test
    void subProjectRowUsesScopedLookupAndRootOwnedFiles() {
        assay.id = UUID.randomUUID();
        assay.project = new Project();
        assay.project.id = 1L;
        CSVExperimentRowWriter writer = new CSVExperimentRowWriter();
        writer.assayService = mock(AssayService.class);
        writer.entityManager = mock(EntityManager.class);
        when(writer.assayService.getAssayById(2L, assay.id)).thenReturn(assay);
        try (MockedStatic<Sample> samples = mockStatic(Sample.class);
             MockedStatic<File> files = mockStatic(File.class)) {
            samples.when(() -> Sample.findBySampleNameInProject("sample", 2L)).thenReturn(Optional.of(sample));
            files.when(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE)).thenAnswer(invocation -> {
                verify(writer.entityManager).flush();
                return Optional.empty();
            });
            writer.write(2L, assay.id, "sample", null, null, null, null, null, null, null, null, null, null,
                    List.of(new CSVExperimentImportService.PendingFile(
                            "File Name", "reads.fastq", "md5", null, ReadRole.SINGLE)), null, null);
            verify(writer.assayService).updateAssay(2L, assay.id, null, null, null, null, null, null, null, null, null,
                    null, null);
            verify(writer.assayService).associateSample(assay, sample);
            InOrder order = inOrder(writer.assayService, writer.entityManager);
            order.verify(writer.assayService).associateSample(assay, sample);
            order.verify(writer.entityManager).flush();
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
            files.verifyNoMoreInteractions();
        }
    }

    private String singleEndRow() {
        return row("platform", "lab", "").replace(",forward.fastq,md5,reverse.fastq,md5", ",,,,");
    }

    private String pairedEndRow() {
        return row("platform", "lab", "100")
                .replace(",reads.fastq,md5,", ",,,")
                .replace("forward.fastq,md5,reverse.fastq,md5",
                        "forward.fastq,forward-md5,reverse.fastq,reverse-md5");
    }

    @Test
    void singleEndTemplateAssignsSingleReadRole() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            assertTrue(assay.samples.isEmpty());
            assertTrue(sample.assays.isEmpty());
            var errors = importRows(Map.of(), singleEndRow());

            assertTrue(errors.isEmpty());
            assertEquals(Set.of(sample), assay.samples);
            assertEquals(Set.of(assay), sample.assays);
            verify(assay).addSample(sample);
            files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
            files.verifyNoMoreInteractions();
        }
    }

    @Test
    void pairedEndTemplateAssignsForwardAndReverseRoles() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), pairedEndRow());

            assertTrue(errors.isEmpty());
            files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, "forward.fastq", "forward-md5", null,
                    ReadRole.FORWARD));
            files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, "reverse.fastq", "reverse-md5", null,
                    ReadRole.REVERSE));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "forward.fastq", "forward-md5", null,
                    ReadRole.FORWARD));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reverse.fastq", "reverse-md5", null,
                    ReadRole.REVERSE));
            files.verifyNoMoreInteractions();
        }
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
    void repeatedRowsAssociateSampleWithoutDuplicatingMembership() throws Exception {
        Path path = Files.writeString(directory.resolve("repeated-sample.csv"), "Sample\nsample\nsample\n");

        assertTrue(importFile(Map.of(), path).isEmpty());

        assertEquals(Set.of(sample), assay.samples);
        assertEquals(Set.of(assay), sample.assays);
        verify(assay, times(2)).addSample(sample);
    }

    @Test
    void rowsWithoutFilesDoNotFlushBeforeTransactionCompletion() throws Exception {
        Path path = Files.writeString(directory.resolve("no-files.csv"), "Sample\nsample\n");

        assertTrue(importFile(Map.of(), path).isEmpty());

        verify(service.rowWriter.entityManager, never()).flush();
    }

    @Test
    void existingAssayMembershipRemainsValidDuringImport() throws Exception {
        assay.addSample(sample);
        clearInvocations(assay);
        Path path = Files.writeString(directory.resolve("existing-membership.csv"), "Sample\nsample\n");

        assertTrue(importFile(Map.of(), path).isEmpty());

        assertEquals(Set.of(sample), assay.samples);
        assertEquals(Set.of(assay), sample.assays);
        verify(assay).addSample(sample);
    }

    @Test
    void missingProjectSampleIsRejectedWithoutAssociatingIt() throws Exception {
        Path path = Files.writeString(directory.resolve("missing-sample.csv"), "Sample\nsample\n");

        var errors = importFile(Map.of(), path, Optional.empty());

        assertEquals(1, errors.size());
        assertEquals("Sample", errors.getFirst().field());
        assertEquals("Sample does not exist in the project", errors.getFirst().message());
        assertTrue(assay.samples.isEmpty());
        assertTrue(sample.assays.isEmpty());
        verify(assay, never()).addSample(any());
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
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "forward.fastq", "md5", null,
                    ReadRole.FORWARD));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reverse.fastq", "md5", null,
                    ReadRole.REVERSE));
            files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
            files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, "forward.fastq", "md5", null,
                    ReadRole.FORWARD));
            files.verify(() -> File.validateImportPending(1L, assay.id, sample, assay, "reverse.fastq", "md5", null,
                    ReadRole.REVERSE));
            files.verifyNoMoreInteractions();
        }
        verify(service.vocabularyService, times(1)).loadRules();
    }

    @Test
    void blankAndUnconfiguredMetadataRemainAllowed() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of("sequencing_platform", Set.of("ILLUMINA")), row(" ", "any lab", ""));
            assertTrue(errors.isEmpty());
            assertNull(assay.sequencingPlatform);
            assertEquals("any lab", assay.sequencingLaboratory);
            assertNull(assay.insertSize);
            verify(assay).addSample(sample);
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
        }
    }

    @Test
    void missingPatchValuesPreserveExistingAssayFields() throws Exception {
        assay.instrumentModel = "existing instrument";
        assay.insertSize = 45;
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), row("platform", "lab", "")
                    .replace("sample,instrument,library", "sample,,library"));
            assertTrue(errors.isEmpty());
            assertEquals("existing instrument", assay.instrumentModel);
            assertEquals("library", assay.libraryName);
            assertEquals(45, assay.insertSize);
            assertEquals("platform", assay.sequencingPlatform);
        }
    }

    @Test
    void blankPlatformAndLaboratoryPreserveExistingAssayFields() throws Exception {
        assay.sequencingPlatform = "existing platform";
        assay.sequencingLaboratory = "existing laboratory";
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), row(" ", "", ""));
            assertTrue(errors.isEmpty());
            assertEquals("existing platform", assay.sequencingPlatform);
            assertEquals("existing laboratory", assay.sequencingLaboratory);
        }
    }

    @Test
    void resubmissionAllowsBackfillingAndUnchangedMetadata() throws Exception {
        assay.instrumentModel = "instrument";
        assay.libraryName = "library";
        assay.sequencingPlatform = "platform";
        assay.insertSize = 100;
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), row("platform", "lab", "100"));
            assertTrue(errors.isEmpty());
            assertEquals("instrument", assay.instrumentModel);
            assertEquals("library", assay.libraryName);
            assertEquals("source", assay.librarySource);
            assertEquals("lab", assay.sequencingLaboratory);
            assertEquals(100, assay.insertSize);
            verify(assay).addSample(sample);
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE));
        }
    }

    @Test
    void resubmissionRejectsReplacementOfPopulatedMetadata() throws Exception {
        assay.instrumentModel = "previous instrument";
        assay.modifiedOn = Instant.EPOCH;
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), row("platform", "lab", "100"));
            assertEquals(1, errors.size());
            assertEquals("Sequencing instrument", errors.getFirst().field());
            assertEquals("instrument", errors.getFirst().value());
            assertEquals("Cannot replace existing value 'previous instrument' with 'instrument'",
                    errors.getFirst().message());
            assertEquals("previous instrument", assay.instrumentModel);
            assertEquals(Instant.EPOCH, assay.modifiedOn);
            verify(assay, never()).addSample(any());
            files.verifyNoInteractions();
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
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE), times(1));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "forward.fastq", "md5", null,
                    ReadRole.FORWARD), times(1));
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reverse.fastq", "md5", null,
                    ReadRole.REVERSE), times(1));
        }
    }

    @Test
    void invalidInsertSizeDoesNotReserveReferencesButSuccessfulRowsDo() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            var errors = importRows(Map.of(), row("platform", "lab", "invalid")
                    + row("platform", "lab", "200") + row("platform", "lab", "300"));
            assertEquals(2, errors.size());
            assertEquals("Insert Size", errors.getFirst().field());
            assertEquals("Row 3", errors.get(1).row());
            assertEquals("Insert Size", errors.get(1).field());
            assertEquals("Cannot replace existing value '200' with '300'", errors.get(1).message());
            assertEquals(200, assay.insertSize);
            verify(assay, times(1)).addSample(sample);
            files.verify(() -> File.importPending(1L, assay.id, sample, assay, "reads.fastq", "md5", null,
                    ReadRole.SINGLE), times(1));
        }
    }

    @Test
    void pendingFileConflictRejectsRowWithoutUpdatingAssay() throws Exception {
        assay.id = UUID.randomUUID();
        assay.instrumentModel = "instrument";
        assay.modifiedOn = Instant.EPOCH;
        try (MockedStatic<File> files = mockStatic(File.class)) {
            files.when(() -> File.validateImportPending(1L, assay.id, sample, assay,
                    "reads.fastq", "md5", null, ReadRole.SINGLE)).thenReturn(Optional.of("Conflicting file metadata"));
            var errors = importRows(Map.of(), row("platform", "lab", "100"));
            assertEquals(1, errors.size());
            assertEquals("File Name", errors.getFirst().field());
            assertEquals("Conflicting file metadata", errors.getFirst().message());
            assertEquals("instrument", assay.instrumentModel);
            assertEquals(Instant.EPOCH, assay.modifiedOn);
            verify(assay, never()).addSample(any());
            files.verify(() -> File.importPending(anyLong(), any(), any(), any(), any(), any(), any(), any()), never());
        }
    }

    @Test
    void conflictAfterPrevalidationIsReportedAndDoesNotReserveReferences() throws Exception {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            files.when(() -> File.importPending(eq(1L), any(), any(), any(), eq("reads.fastq"),
                    eq("md5"), isNull(), eq(ReadRole.SINGLE))).thenReturn(Optional.of("Late file conflict"))
                    .thenReturn(Optional.empty());
            var errors = importRows(Map.of(), row("platform", "lab", "100") + row("platform", "lab", "200"));
            assertEquals(2, errors.size());
            assertEquals("Row 1", errors.getFirst().row());
            assertEquals("File Name", errors.getFirst().field());
            assertEquals("Late file conflict", errors.getFirst().message());
            assertEquals("Row 2", errors.get(1).row());
            assertEquals("Insert Size", errors.get(1).field());
            assertEquals(100, assay.insertSize);
        }
    }

    @Test
    void validatesWorkbookHeadersAndRowsBeforeImporting() throws Exception {
        service.csvImportSupport = new CSVImportSupport();

        assertThrows(BadRequestException.class, () -> service.validateSpreadsheet(
                spreadsheet("File Name\nreads.fastq\n")));
        assertThrows(BadRequestException.class, () -> service.validateSpreadsheet(
                spreadsheet("Sample,Unexpected\nsample,value\n")));

        SpreadsheetTable empty = new SpreadsheetTable(
                "Experiments", List.of("Sample"), List.of(), Map.of(), Map.of());
        BadRequestException error = assertThrows(
                BadRequestException.class, () -> service.validateSpreadsheet(empty));
        assertTrue(error.getMessage().contains("no data rows"));
    }

    @Test
    void accessionImportsBackfillPreserveBlanksAndRejectConflictsPerRow() throws Exception {
        Path path = Files.writeString(directory.resolve("accessions.csv"),
                "Sample,experiment_accession,run_accession\n"
                        + "sample,  ERX123  , ERR123 \n"
                        + "sample,,\n"
                        + "sample,ERX999,ERR999\n"
                        + "sample,ERX123,ERR123\n");
        var errors = importFile(Map.of(), path);
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> error.row().equals("Row 3")));
        assertEquals(Set.of("Experiment Accession", "Run Accession"),
                errors.stream().map(CSVExperimentRowError::field).collect(Collectors.toSet()));
        assertEquals("ERX123", assay.experimentAccession);
        assertEquals("ERR123", assay.runAccession);
        verify(assay, times(3)).addSample(sample);
    }

    @Test
    void omittedAccessionColumnsPreserveExistingValues() throws Exception {
        assay.experimentAccession = "ERX123";
        assay.runAccession = "ERR123";
        assertTrue(importFile(Map.of(), Files.writeString(directory.resolve("old.csv"),
                "Sample\nsample\n")).isEmpty());
        assertEquals("ERX123", assay.experimentAccession);
        assertEquals("ERR123", assay.runAccession);
    }

    @Test
    void generatedExperimentTemplatesImportOptionalAccessions() throws Exception {
        for (String type : List.of("experiment_PE", "experiment_SE")) {
            for (boolean includeAccessions : List.of(false, true)) {
                byte[] template = new SpreadsheetTemplateService().create(type);
                Path path = directory.resolve(type + ".xlsx");
                try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template))) {
                    var sheet = workbook.getSheetAt(0);
                    for (var header : sheet) {
                        if (header.getCell(0) == null
                                || !"METADATA FIELDS:".equals(header.getCell(0).getStringCellValue())) continue;
                        if (includeAccessions) {
                            int column = header.getLastCellNum();
                            header.createCell(column).setCellValue("experiment_accession");
                            header.createCell(column + 1).setCellValue("run_accession");
                        }
                        var data = sheet.createRow(header.getRowNum() + 1);
                        for (var cell : header) {
                            String value = switch (cell.getStringCellValue()) {
                                case "Sample" -> "sample";
                                case "experiment_accession" -> "000123";
                                case "run_accession" -> "ERR123";
                                default -> null;
                            };
                            if (value != null) data.createCell(cell.getColumnIndex()).setCellValue(value);
                        }
                        break;
                    }
                    try (var output = Files.newOutputStream(path)) {
                        workbook.write(output);
                    }
                }
                assay.experimentAccession = null;
                assay.runAccession = null;
                assertTrue(importFile(Map.of(), path).isEmpty(), type);
                assertEquals(includeAccessions ? "000123" : null, assay.experimentAccession, type);
                assertEquals(includeAccessions ? "ERR123" : null, assay.runAccession, type);
            }
        }
    }

    private SpreadsheetTable spreadsheet(String contents) throws Exception {
        List<CSVRecord> records = service.csvImportSupport.prepareRecords(new StringReader(contents), ',');
        return new SpreadsheetTable(
                "Experiments",
                records.getFirst().getParser().getHeaderNames(),
                records,
                Map.of(1L, 2),
                Map.of());
    }
}
