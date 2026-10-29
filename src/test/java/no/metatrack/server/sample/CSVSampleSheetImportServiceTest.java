package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.CreateSampleMetadataFieldRequest;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import no.metatrack.server.sample.vocabulary.SampleVocabularyValidationException;
import no.metatrack.server.spreadsheet.SpreadsheetTable;
import no.metatrack.server.spreadsheet.SpreadsheetImportSupport;
import no.metatrack.server.spreadsheet.SpreadsheetExportWriter;
import no.metatrack.server.sample.vocabulary.SpreadsheetVocabularyImportService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class CSVSampleSheetImportServiceTest {
    @TempDir
    Path directory;

    @Test
    void importsExportedSamplesWithDistinctBuiltInAliasesAndCustomMetadata() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        service.metadataService = new SampleMetadataService();
        service.spreadsheetSupport = new SpreadsheetImportSupport();
        service.spreadsheetVocabularies = mock(SpreadsheetVocabularyImportService.class);
        Sample source = new Sample();
        source.id = java.util.UUID.randomUUID();
        source.name = "00012";
        source.institution = "owner institution";
        source.collectingInstitution = "collecting institution";
        source.sequencingLab = "sequencing lab";
        source.collectedBy = "collector";
        source.collectionDate = LocalDate.of(2024, 2, 29);
        source.latitude = 69.649208;
        SampleMetadataField field = metadataField("amount", "Amount");
        field.type = SampleMetadataFieldType.NUMBER;
        Map<String, Object> values = Map.of("amount", new BigDecimal("12345678901234567890.12345"));
        byte[] bytes = new SpreadsheetExportWriter().samples(List.of(source), List.of(field),
                Map.of(source.id, values), List.of());
        Path path = Files.write(directory.resolve("export.xlsx"), bytes);
        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of(field));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());
            assertTrue(service.importNewSamples(1L, path.toFile()).isEmpty());
            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample")).toList();
            assertEquals(1, calls.size());
            var call = calls.getFirst();
            assertEquals(source.name, call.getArgument(1));
            assertEquals(source.sequencingLab, call.getArgument(7));
            assertEquals(source.institution, call.getArgument(8));
            assertEquals(source.collectionDate, call.getArgument(10));
            assertEquals(source.collectedBy, call.getArgument(15));
            assertEquals(source.latitude, call.getArgument(16));
            assertEquals(source.collectingInstitution, call.getArgument(23));
            Object[] arguments = call.getArguments();
            assertEquals(values, arguments[arguments.length - 1]);
        }
    }

    @Test
    void forwardsSnakeCaseAccessionsInCreationOrder() throws Exception {
        assertAccessionImport("name,project_accession,study_accession,sample_accession,secondary_accession\n"
                + "sample-1,PRJNA000001,ERP000001,SAMN000001,0000123\n",
                List.of("PRJNA000001", "ERP000001", "SAMN000001", "0000123"));
    }

    @Test
    void forwardsTitleSpaceAccessionsFromHeaderFirstTsv() throws Exception {
        assertAccessionImport("Sample Name\tProject Accession\tStudy Accession\tSample Accession"
                + "\tSecondary Accession\n"
                + "sample-1\tPRJNA000001\tERP000001\tSAMN000001\t0000123\n",
                List.of("PRJNA000001", "ERP000001", "SAMN000001", "0000123"));
    }

    @Test
    void acceptsOmittedAccessionColumns() throws Exception {
        assertAccessionImport("Sample Name\nsample-1\n", java.util.Arrays.asList(null, null, null, null));
    }

    @Test
    void acceptsBlankAccessionValues() throws Exception {
        assertAccessionImport("Sample Name,Project Accession,Study Accession,Sample Accession,Secondary Accession\n"
                + "sample-1, ,,, \n", List.of("", "", "", ""));
    }

    @Test
    void reservesAccessionAliasesFromCustomMetadataLabels() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        for (String header : List.of("project_accession", "Project Accession", "study_accession", "Study Accession",
                "sample_accession", "Sample Accession", "secondary_accession", "Secondary Accession")) {
            CSVRecord record = service.prepareRecords(new StringReader(header + "\naccession-value\n"), ',')
                    .getFirst();
            assertEquals(null, service.getCustomMetadataValue(record, metadataField("custom_status", header)));
        }
    }

    @Test
    void includesAccessionsInBuiltInValidationValues() {
        Sample sample = new Sample();
        sample.projectAccession = "PRJNA000001";
        sample.studyAccession = "ERP000001";
        sample.sampleAccession = "SAMN000001";
        sample.secondaryAccession = "0000123";

        Map<String, String> values = CSVSampleSheetImportService.builtInValues(sample);

        assertEquals("PRJNA000001", values.get("project_accession"));
        assertEquals("ERP000001", values.get("study_accession"));
        assertEquals("SAMN000001", values.get("sample_accession"));
        assertEquals("0000123", values.get("secondary_accession"));
    }

    private void assertAccessionImport(String content, List<String> expectedAccessions) throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        Path csv = Files.writeString(directory.resolve("accessions.csv"), content);

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            assertTrue(service.importNewSamples(1L, csv.toFile()).isEmpty());

            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample"))
                    .toList();
            assertEquals(1, calls.size());
            Object[] arguments = calls.getFirst().getArguments();
            assertEquals(46, arguments.length);
            for (int i = 0; i < expectedAccessions.size(); i++) {
                assertEquals(expectedAccessions.get(i), arguments[41 + i]);
            }
            assertEquals(Map.of(), arguments[45]);
        }
    }

    @Test
    void validRowsUseApiCreationWhileInvalidRowsRemainIsolated() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.metadataService = new SampleMetadataService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        when(service.metadataFieldService.create(eq(1L), any(CreateSampleMetadataFieldRequest.class)))
                .thenAnswer(invocation -> {
                    CreateSampleMetadataFieldRequest request = invocation.getArgument(1);
                    return metadataField(request.key(), request.label());
                });
        service.sampleService = mock(SampleService.class, invocation -> {
            if ("second".equals(invocation.getArgument(1))) {
                throw new SampleVocabularyValidationException(List.of(new SampleValidationViolation(
                        "second", "host_sex", "male", "Invalid vocabulary value")));
            }
            return null;
        });
        Path csv = Files.writeString(directory.resolve("samples.csv"),
                "Sample Name,Tax ID,Host Sex,custom_status\n"
                        + "first,287,female,known\n"
                        + "bad,not-an-integer,male,unknown\n"
                        + "second,288,male,unknown\n");
        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());
            var errors = service.importNewSamples(1L, csv.toFile());
            assertEquals(2, errors.size());
            assertEquals("tax_id", errors.get(0).fieldKey());
            assertEquals("host_sex", errors.get(1).fieldKey());
            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample"))
                    .toList();
            assertEquals(List.of("first", "second"), calls.stream()
                    .map(invocation -> (String) invocation.getArgument(1)).toList());
            assertEquals(Integer.valueOf(287), calls.getFirst().getArgument(3));
            assertEquals("female", calls.getFirst().getArgument(24));
        }
    }

    @Test
    void importsUnknownCsvColumnsAsTextMetadata() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        service.metadataService = new SampleMetadataService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        when(service.metadataFieldService.create(eq(1L), any(CreateSampleMetadataFieldRequest.class)))
                .thenAnswer(invocation -> {
                    CreateSampleMetadataFieldRequest request = invocation.getArgument(1);
                    return metadataField(request.key(), request.label());
                });
        Path csv = Files.writeString(directory.resolve("custom-columns.csv"),
                "Sample Name,Instrument Code,Empty Extra,Invalid Only,Tax ID\n"
                        + "sample-1,00123,,,287\n"
                        + "bad,00456,not-used,not-saved,not-an-integer\n"
                        + "sample-2,,,,288\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            assertEquals(1, service.importNewSamples(1L, csv.toFile()).size());

            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample"))
                    .toList();
            assertEquals(List.of("sample-1", "sample-2"), calls.stream()
                    .map(invocation -> (String) invocation.getArgument(1)).toList());
            assertEquals(Map.of("instrument_code", "00123"),
                    calls.getFirst().getArguments()[45]);
            assertEquals(Map.of(), calls.get(1).getArguments()[45]);
            verify(service.metadataFieldService, times(1)).create(eq(1L), any(CreateSampleMetadataFieldRequest.class));
            var request = mockingDetails(service.metadataFieldService).getInvocations().stream().findFirst().orElseThrow()
                    .getArgument(1, CreateSampleMetadataFieldRequest.class);
            assertEquals("instrument_code", request.key());
            assertEquals("Instrument Code", request.label());
            assertEquals(SampleMetadataFieldType.TEXT, request.type());
        }
    }

    @Test
    void discoversUnknownColumnsInTsvAndSemicolonDelimitedFiles() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        service.metadataService = new SampleMetadataService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        when(service.metadataFieldService.create(eq(1L), any(CreateSampleMetadataFieldRequest.class)))
                .thenAnswer(invocation -> {
                    CreateSampleMetadataFieldRequest request = invocation.getArgument(1);
                    return metadataField(request.key(), request.label());
                });
        Path tsv = Files.writeString(directory.resolve("custom-columns.tsv"),
                "Sample Name\tGenome Build\nsample-tsv\t00123\n");
        Path semicolon = Files.writeString(directory.resolve("custom-columns-semicolon.csv"),
                "Sample Name;Sequencing Platform\nsample-semicolon;00123\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            assertTrue(service.importNewSamples(1L, tsv.toFile()).isEmpty());
            assertTrue(service.importNewSamples(1L, semicolon.toFile()).isEmpty());

            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample"))
                    .toList();
            assertEquals(Map.of("genome_build", "00123"), calls.getFirst().getArguments()[45]);
            assertEquals(Map.of("sequencing_platform", "00123"), calls.get(1).getArguments()[45]);
            verify(service.metadataFieldService, times(2)).create(eq(1L), any(CreateSampleMetadataFieldRequest.class));
        }
    }

    @Test
    void rejectsGeneratedKeyCollisionsBeforeCreatingFieldsOrSamples() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        service.sampleService = mock(SampleService.class);
        Path csv = Files.writeString(directory.resolve("colliding-columns.csv"),
                "name,Field Name,Field-Name\nsample-1,first,second\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            BadRequestException error = assertThrows(
                    BadRequestException.class, () -> service.importNewSamples(1L, csv.toFile()));

            assertTrue(error.getMessage().contains("Field Name"));
            assertTrue(error.getMessage().contains("Field-Name"));
            assertTrue(error.getMessage().contains("field_name"));
            verifyNoInteractions(service.metadataFieldService, service.sampleService);
        }
    }

    @Test
    void invalidGeneratedKeyExplainsHeaderRequirementsBeforeWrites() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        service.sampleService = mock(SampleService.class);
        Path csv = Files.writeString(directory.resolve("invalid-key.csv"),
                "name,123 Identifier\nsample-1,value\n");
        Path reservedKeyCsv = Files.writeString(directory.resolve("reserved-key.csv"),
                "name,Created On\nsample-1,value\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            BadRequestException error = assertThrows(
                    BadRequestException.class, () -> service.importNewSamples(1L, csv.toFile()));

            assertTrue(error.getMessage().contains("123 Identifier"));
            assertTrue(error.getMessage().contains("start with a letter"));
            BadRequestException reservedKeyError = assertThrows(
                    BadRequestException.class, () -> service.importNewSamples(1L, reservedKeyCsv.toFile()));
            assertTrue(reservedKeyError.getMessage().contains("reserved"));
            assertTrue(reservedKeyError.getMessage().contains("not reserved"));
            verifyNoInteractions(service.metadataFieldService, service.sampleService);
        }
    }

    @Test
    void ambiguousCustomLabelsExplainConflictingFieldsBeforeWrites() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        service.sampleService = mock(SampleService.class);
        SampleMetadataField first = metadataField("first_status", "Status");
        SampleMetadataField second = metadataField("second_status", "Status");
        Path csv = Files.writeString(directory.resolve("ambiguous-label.csv"),
                "name,Status\nsample-1,ready\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of(first, second));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            BadRequestException error = assertThrows(
                    BadRequestException.class, () -> service.importNewSamples(1L, csv.toFile()));

            assertTrue(error.getMessage().contains("multiple custom metadata labels"));
            assertTrue(error.getMessage().contains("first_status"));
            assertTrue(error.getMessage().contains("second_status"));
            verifyNoInteractions(service.metadataFieldService, service.sampleService);
        }
    }

    @Test
    void archivedFieldHeaderExplainsHowToResolveIt() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        service.sampleService = mock(SampleService.class);
        SampleMetadataField archivedField = metadataField("old_status", "Old Status");
        archivedField.archivedOn = Instant.now();
        Path csv = Files.writeString(directory.resolve("archived-field.csv"),
                "name,Old Status\nsample-1,ready\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L))
                    .thenReturn(List.of(archivedField));

            BadRequestException error = assertThrows(
                    BadRequestException.class, () -> service.importNewSamples(1L, csv.toFile()));

            assertTrue(error.getMessage().contains("old_status"));
            assertTrue(error.getMessage().contains("Restore this field or rename the column"));
            verifyNoInteractions(service.metadataFieldService, service.sampleService);
        }
    }

    @Test
    void importsUnknownWorkbookColumnsAsTextMetadata() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        service.metadataService = new SampleMetadataService();
        service.metadataFieldService = mock(SampleMetadataFieldService.class);
        when(service.metadataFieldService.create(eq(1L), any(CreateSampleMetadataFieldRequest.class)))
                .thenAnswer(invocation -> {
                    CreateSampleMetadataFieldRequest request = invocation.getArgument(1);
                    return metadataField(request.key(), request.label());
                });
        service.spreadsheetSupport = new SpreadsheetImportSupport();
        service.spreadsheetVocabularies = mock(SpreadsheetVocabularyImportService.class);
        Path path = directory.resolve("custom-columns.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Samples");
            var headers = sheet.createRow(0);
            headers.createCell(0).setCellValue("name");
            headers.createCell(1).setCellValue("Sequencing Platform");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("sample-1");
            row.createCell(1).setCellValue("00123");
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            assertTrue(service.importNewSamples(1L, path.toFile()).isEmpty());

            var call = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample"))
                    .findFirst().orElseThrow();
            Object[] arguments = call.getArguments();
            assertEquals(Map.of("sequencing_platform", "00123"), arguments[45]);
            var request = mockingDetails(service.metadataFieldService).getInvocations().stream().findFirst().orElseThrow()
                    .getArgument(1, CreateSampleMetadataFieldRequest.class);
            assertEquals("Sequencing Platform", request.label());
            assertEquals(SampleMetadataFieldType.TEXT, request.type());
        }
    }

    @Test
    void rejectsCsvRowsWithValuesOutsideConfiguredVocabulary() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(
                new SampleVocabularyRules(Set.of(), Map.of("host_sex", Set.of("female"))));
        service.sampleService = mock(SampleService.class);
        Path csv = Files.writeString(directory.resolve("invalid-vocabulary.csv"),
                "Sample Name,Host Sex\nsample-1,male\n");

        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());

            List<SampleValidationViolation> errors = service.importNewSamples(1L, csv.toFile());

            assertEquals(1, errors.size());
            assertEquals("host_sex", errors.getFirst().fieldKey());
            assertEquals("male", errors.getFirst().rejectedValue());
            verifyNoInteractions(service.sampleService);
        }
    }

    @ParameterizedTest
    @CsvSource({"2024-02-29,2024-02-29", "29/2/2024,2024-02-29", "12/31/2026,2026-12-31",
            "31/12/26,2026-12-31", "12/31/26,2026-12-31", "3/4/2026,2026-04-03",
            "2025-02-29,", "31/02/2026,", "02/29/2025,", "2026-04-31,"})
    void validatesCollectionDatesWithoutSilentlyChangingDays(String input, String expected) throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        Path csv = Files.writeString(directory.resolve("dates.csv"),
                "name,collection_date\nsample-1," + input + "\n");
        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of());
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());
            var errors = service.importNewSamples(1L, csv.toFile());
            if (expected == null) {
                assertEquals(1, errors.size());
                assertEquals("collection_date", errors.getFirst().fieldKey());
                assertEquals(input, errors.getFirst().rejectedValue());
                verifyNoInteractions(service.sampleService);
            } else {
                assertTrue(errors.isEmpty(), errors.toString());
                var calls = mockingDetails(service.sampleService).getInvocations().stream()
                        .filter(invocation -> invocation.getMethod().getName().equals("createSample")).toList();
                assertEquals(1, calls.size());
                assertEquals(LocalDate.parse(expected), calls.getFirst().getArgument(10));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"xls,concentration", "xls,Concentration value", "xlsx,concentration", "xlsx,Concentration value"})
    void importsCustomNumbersByKeyOrLabelWithoutRounding(String format, String header) throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        service.vocabularyService = mock(SampleVocabularyService.class);
        when(service.vocabularyService.loadRules(1L)).thenReturn(new SampleVocabularyRules(Set.of(), Map.of()));
        service.sampleService = mock(SampleService.class);
        service.metadataService = new SampleMetadataService();
        service.spreadsheetSupport = new SpreadsheetImportSupport();
        service.spreadsheetVocabularies = mock(SpreadsheetVocabularyImportService.class);
        SampleMetadataField field = metadataField("concentration", "Concentration value");
        field.type = SampleMetadataFieldType.NUMBER;
        Path path = directory.resolve("numbers." + format);
        try (Workbook workbook = format.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Samples");
            var headers = sheet.createRow(0);
            headers.createCell(0).setCellValue("name");
            headers.createCell(1).setCellValue(header);
            var data = sheet.createRow(1);
            data.createCell(0).setCellValue("sample-1");
            var cell = data.createCell(1);
            cell.setCellValue(1234.56789);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            cell.setCellStyle(style);
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }
        try (MockedStatic<PanacheEntityBase> entities = mockStatic(PanacheEntityBase.class)) {
            Project project = new Project();
            project.id = 1L;
            entities.when(() -> Project.findByIdOptional(1L)).thenReturn(Optional.of(project));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is null order by key", 1L)).thenReturn(List.of(field));
            entities.when(() -> SampleMetadataField.list(
                    "project.id = ?1 and archivedOn is not null order by key", 1L)).thenReturn(List.of());
            assertTrue(service.importNewSamples(1L, path.toFile()).isEmpty());
            var calls = mockingDetails(service.sampleService).getInvocations().stream()
                    .filter(invocation -> invocation.getMethod().getName().equals("createSample")).toList();
            assertEquals(1, calls.size());
            Object[] arguments = calls.getFirst().getArguments();
            assertEquals(Map.of("concentration", new BigDecimal("1234.56789")), arguments[arguments.length - 1]);
        }
    }

    @Test
    void parsesHeaderFirstRowsWithoutRemovingColumns() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> records = service.prepareRecords(new StringReader(
                "Tax ID;Sample Name\n287;NorPa_0001\n"), ';');

        assertEquals(1, records.size());
        assertEquals("287", records.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", records.get(0).get("Sample Name"));
        assertEquals(List.of("Tax ID", "Sample Name"), records.getFirst().getParser().getHeaderNames());
    }

    @Test
    void supportsBomAtBeginningOfHeaderFromReader() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        StringReader reader = new StringReader("\uFEFFSample Name;Tax ID\nNorPa_0001;287\n");

        List<CSVRecord> records = service.prepareRecords(reader, ';');

        assertEquals("NorPa_0001", records.get(0).get("Sample Name"));
        assertEquals("Sample Name", records.getFirst().getParser().getHeaderNames().getFirst());
    }

    @Test
    void supportsHeaderFirstTsv() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> records = service.prepareRecords(new StringReader(
                "Tax ID\tSample Name\n287\tNorPa_0001\n"), '\t');

        assertEquals("287", records.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", records.get(0).get("Sample Name"));
    }

    @ParameterizedTest
    @CsvSource({"sample.csv", "sample_extended.csv", "sample_virus.csv"})
    void parsesShippedSampleTemplateHeadersWithData(String filename) throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        String template;
        try (var input = getClass().getResourceAsStream(
                "/META-INF/resources/templates/samples/" + filename)) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        }
        String[] headers = template.split(";", -1);
        String row = "sample-1" + ";".repeat(headers.length - 1);

        List<CSVRecord> records = service.prepareRecords(new StringReader(template + "\n" + row), ';');

        assertEquals(headers.length, records.getFirst().size());
        assertEquals("sample-1", records.getFirst().get("Sample Name"));
    }

    @Test
    void preservesAppendedTemplateColumnsAndUnevenRows() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> records = service.prepareRecords(new StringReader(
                "Sample Name;custom_status;Unknown\n"
                        + "sample-1;known;ignored\n"
                        + "sample-2\n"), ';');

        assertEquals("known", records.get(0).get("custom_status"));
        assertEquals("ignored", records.get(0).get("Unknown"));
        assertEquals(null, service.getCustomMetadataValue(records.get(1), metadataField("custom_status", "Status")));
    }

    @Test
    void resolvesCustomMetadataByKeyAndLabelWithKeyPrecedence() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField field = metadataField("custom_status", "Status");

        CSVRecord keyRecord = service.prepareRecords(
                new StringReader("  CUSTOM_STATUS  ,Status\nkey-value,label-value\n"), ',').get(0);
        CSVRecord labelRecord = service.prepareRecords(
                new StringReader("Status\nlabel-value\n"), ',').get(0);

        assertEquals("key-value", service.getCustomMetadataValue(keyRecord, field));
        assertEquals("label-value", service.getCustomMetadataValue(labelRecord, field));
    }

    @Test
    void ignoresAmbiguousCustomMetadataLabels() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField builtInCollision = metadataField("custom_status", "Sample Name");
        SampleMetadataField keyCollision = metadataField("sample_name", "Status");
        SampleMetadataField otherField = metadataField("status", "Other Status");
        CSVRecord record = service.prepareRecords(
                new StringReader("Sample Name,Status\nsample-1,status-value\n"), ',').get(0);

        assertEquals(null, service.getCustomMetadataValue(record, builtInCollision));
        assertEquals(null, service.getCustomMetadataValue(record, keyCollision, List.of(keyCollision, otherField)));
    }

    @Test
    void keyMatchRemainsAuthoritativeWhenLabelIsAmbiguous() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField field = metadataField("custom_status", "Sample Name");
        CSVRecord record = service.prepareRecords(
                new StringReader("custom_status,Sample Name\nkey-value,label-value\n"), ',').get(0);

        assertEquals("key-value", service.getCustomMetadataValue(record, field));
    }

    @Test
    void rejectsDuplicateCustomHeaders() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        assertThrows(BadRequestException.class, () -> service.prepareRecords(
                new StringReader("custom_status,custom_status\nkey-value,other-value\n"), ','));
    }

    @Test
    void preservesHeaderFirstCsvAndTsv() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        List<CSVRecord> csvRecords = service.prepareRecords(
                new StringReader("Tax ID,Sample Name\n287,NorPa_0001\n"), ',');
        List<CSVRecord> tsvRecords = service.prepareRecords(
                new StringReader("Tax ID\tSample Name\n287\tNorPa_0001\n"), '\t');

        assertEquals("287", csvRecords.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", csvRecords.get(0).get("Sample Name"));
        assertEquals("287", tsvRecords.get(0).get("Tax ID"));
        assertEquals("NorPa_0001", tsvRecords.get(0).get("Sample Name"));
    }

    @Test
    void rejectsHeaderWithoutUsableNames() {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();

        assertThrows(BadRequestException.class, () -> service.prepareRecords(
                new StringReader(";\n"), ';'));
    }

    @Test
    void exposesCanonicalKeysForBuiltInAliases() {
        Sample sample = new Sample();
        sample.sequencingLab = "Lab A";
        sample.isolationSource = "swab";
        sample.hostSex = "female";

        Map<String, String> values = CSVSampleSheetImportService.builtInValues(sample);

        assertEquals("Lab A", values.get("sequencing_lab"));
        assertEquals("swab", values.get("isolation_source"));
        assertEquals("female", values.get("host_sex"));
        assertTrue(values.keySet().stream().noneMatch(key -> key.contains(" ")));
    }

    @Test
    void validatesMultipleRowsAgainstOneLoadedRuleSnapshot() {
        SampleVocabularyRules rules = new SampleVocabularyRules(
                Set.of("custom_status"), Map.of("host_sex", Set.of("female"), "custom_status", Set.of("known")));

        assertTrue(SampleVocabularyService.validate(
                        rules, "sample-1", Map.of("host_sex", "female"), Map.of("custom_status", "known"))
                .isEmpty());
        assertEquals(2, SampleVocabularyService.validate(
                        rules, "sample-2", Map.of("host_sex", "male"), Map.of("custom_status", "unknown"))
                .size());
    }

    @Test
    void validatesWorkbookHeadersBeforeImportingRows() throws Exception {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SampleMetadataField customField = metadataField("custom_status", "Status");

        assertThrows(BadRequestException.class, () -> service.validateSpreadsheet(
                spreadsheet(service, "alias\nvalue\n"), List.of(customField)));
        service.validateSpreadsheet(
                spreadsheet(service, "name,typo\nsample,value\n"), List.of(customField));
        service.validateSpreadsheet(
                spreadsheet(service, "name,custom_status\nsample,ready\n"), List.of(customField));
    }

    @Test
    void rejectsWorkbookWithoutDataRows() {
        CSVSampleSheetImportService service = new CSVSampleSheetImportService();
        SpreadsheetTable table = new SpreadsheetTable(
                "Samples", List.of("name"), List.of(), Map.of(), Map.of());

        BadRequestException error = assertThrows(
                BadRequestException.class, () -> service.validateSpreadsheet(table, List.of()));

        assertTrue(error.getMessage().contains("no data rows"));
    }

    private SpreadsheetTable spreadsheet(CSVSampleSheetImportService service, String contents) throws Exception {
        List<CSVRecord> records = service.prepareRecords(new StringReader(contents), ',');
        return new SpreadsheetTable(
                "Samples",
                records.getFirst().getParser().getHeaderNames(),
                records,
                Map.of(1L, 2),
                Map.of());
    }

    private SampleMetadataField metadataField(String key, String label) {
        SampleMetadataField field = new SampleMetadataField();
        field.key = key;
        field.label = label;
        field.type = SampleMetadataFieldType.TEXT;
        return field;
    }
}
