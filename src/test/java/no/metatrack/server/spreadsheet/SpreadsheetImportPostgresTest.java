package no.metatrack.server.spreadsheet;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.assay.AssayController;
import no.metatrack.server.assay.CSVExperimentImportService;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectService;
import no.metatrack.server.sample.CSVSampleSheetImportService;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleController;
import no.metatrack.server.sample.metadata.*;
import no.metatrack.server.sample.vocabulary.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static no.metatrack.server.spreadsheet.SpreadsheetImportSupportTest.row;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(SpreadsheetPostgresProfile.class)
@EnabledIfEnvironmentVariable(named = "INVITATION_TEST_JDBC_URL", matches = ".+")
@TestTransaction
@TestSecurity(user = "spreadsheet-importer")
class SpreadsheetImportPostgresTest {
    @Inject ProjectService projects;
    @Inject SampleMetadataFieldService fields;
    @Inject SampleMetadataService metadata;
    @Inject SampleVocabularyManagementService vocabularies;
    @Inject GlobalSampleVocabularyManagementService globals;
    @Inject CSVSampleSheetImportService samples;
    @Inject CSVExperimentImportService experiments;
    @Inject SampleController sampleController;
    @Inject AssayController assayController;
    @Inject EntityManager em;
    @TempDir Path directory;
    @TestHTTPResource URI server;
    Project project;
    UUID owner = UUID.randomUUID();
    UUID editor = UUID.randomUUID();
    UUID viewer = UUID.randomUUID();
    UserService users;

    @BeforeEach
    void setup() {
        users = mock(UserService.class);
        QuarkusMock.installMockForType(users, UserService.class);
        as(owner);
    }

    void seed() {
        project = projects.createProject("xlsx-" + UUID.randomUUID(), "test", owner.toString());
        projects.addMember(project.id, editor, ProjectRole.EDITOR);
        projects.addMember(project.id, viewer, ProjectRole.VIEWER);
        fields.create(project.id, new CreateSampleMetadataFieldRequest("group", "Group", SampleMetadataFieldType.TEXT));
        em.flush();
    }

    @Test
    void downloadsVersionedExcelTemplate() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(server.resolve("/api/templates/excel/sample")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals(SpreadsheetTemplateController.XLSX_TYPE,
                    response.headers().firstValue("Content-Type").orElseThrow().split(";")[0]);
            assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("sample-v1.xlsx"));
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
                assertEquals("1", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
                assertEquals("Vocabularies", workbook.getSheetName(1));
            }
            var missing = client.send(HttpRequest.newBuilder(server.resolve("/api/templates/excel/unknown")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(404, missing.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void importsCustomVocabularyAndPreservesPartialRowBehavior(String format) throws Exception {
        seed();
        try (Workbook workbook = workbook(format)) {
            Sheet data = workbook.createSheet("Samples");
            row(data, 0, "name", "group");
            row(data, 1, "valid", "control");
            row(data, 2, "invalid", "other");
            vocabulary(workbook, "group", "control");
            var errors = samples.importNewSamples(project.id, write(workbook, format).toFile());
            em.flush();
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().sample().contains("Samples!row 3"));
            assertEquals("group", errors.getFirst().fieldKey());
            assertEquals(1, Sample.count("project.id", project.id));
            Sample sample = Sample.findBySampleNameInProject("valid", project.id).orElseThrow();
            assertEquals(Map.of("group", "control"), metadata.getActiveMetadata(sample));
            assertEquals(List.of("control"), vocabularies.get(project.id, "group").terms());
            assertEquals(1, SampleMetadataField.count("project.id", project.id));
        }
    }

    @Test
    void editorCannotCreateVocabularyAndNothingIsImported() throws Exception {
        seed();
        as(editor);
        try (Workbook workbook = sampleWorkbook("new", "group", "control")) {
            vocabulary(workbook, "group", "control");
            var error = assertThrows(WebApplicationException.class,
                    () -> samples.importNewSamples(project.id, write(workbook, "xlsx").toFile()));
            assertEquals(403, error.getResponse().getStatus());
            assertEquals(0, Sample.count("project.id", project.id));
            assertEquals(0, SampleVocabulary.count("project.id", project.id));
        }
    }

    @Test
    void editorCanReuseIdenticalVocabularyWithoutChangingIt() throws Exception {
        seed();
        vocabularies.replace(project.id, "group", new PutSampleVocabularyRequest(List.of("control", "treated")));
        as(editor);
        try (Workbook workbook = sampleWorkbook("new", "group", "control")) {
            Sheet vocabulary = vocabulary(workbook, "group", "treated");
            row(vocabulary, 2, "group", "control");
            assertTrue(samples.importNewSamples(project.id, write(workbook, "xlsx").toFile()).isEmpty());
            assertEquals(1, SampleVocabulary.count("project.id", project.id));
        }
    }

    @Test
    void validatesAllDefinitionsBeforeWritingAnyVocabularyOrSample() throws Exception {
        seed();
        try (Workbook workbook = sampleWorkbook("new", "group", "control")) {
            Sheet vocabulary = vocabulary(workbook, "group", "control");
            row(vocabulary, 2, "host_sex", "arbitrary");
            assertThrows(WebApplicationException.class,
                    () -> samples.importNewSamples(project.id, write(workbook, "xlsx").toFile()));
            assertEquals(0, Sample.count("project.id", project.id));
            assertEquals(0, SampleVocabulary.count("project.id", project.id));
            assertEquals(0, GlobalSampleVocabulary.count("fieldKey", "host_sex"));
        }
    }

    @Test
    void rejectsChangesToExistingVocabulary() throws Exception {
        seed();
        vocabularies.replace(project.id, "group", new PutSampleVocabularyRequest(List.of("existing")));
        try (Workbook workbook = sampleWorkbook("new", "group", "control")) {
            vocabulary(workbook, "group", "control");
            assertThrows(WebApplicationException.class,
                    () -> samples.importNewSamples(project.id, write(workbook, "xlsx").toFile()));
            assertEquals(List.of("existing"), vocabularies.get(project.id, "group").terms());
            assertEquals(0, Sample.count("project.id", project.id));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "number", "archived", "absent"})
    void rejectsIneligibleOrMissingCustomAttributes(String key) throws Exception {
        seed();
        if (key.equals("number")) fields.create(project.id, new CreateSampleMetadataFieldRequest(key, key, SampleMetadataFieldType.NUMBER));
        if (key.equals("archived")) {
            var field = fields.create(project.id, new CreateSampleMetadataFieldRequest(key, key, SampleMetadataFieldType.TEXT));
            fields.archive(project.id, field.id);
        }
        if (key.equals("absent")) fields.create(project.id, new CreateSampleMetadataFieldRequest(key, key, SampleMetadataFieldType.TEXT));
        try (Workbook workbook = sampleWorkbook("new", key.equals("absent") ? "group" : key, "value")) {
            vocabulary(workbook, key, "value");
            assertThrows(WebApplicationException.class,
                    () -> samples.importNewSamples(project.id, write(workbook, "xlsx").toFile()));
            assertEquals(0, Sample.count("project.id", project.id));
            assertEquals(0, SampleVocabulary.count("project.id", project.id));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx", "csv", "tsv"})
    void ordinaryImportsStillUseGlobalRulesAndEditorPermission(String format) throws Exception {
        seed();
        globals.replace("host_sex", new PutSampleVocabularyRequest(List.of("female")));
        as(editor);
        Path path;
        if (format.equals("csv") || format.equals("tsv")) {
            String delimiter = format.equals("csv") ? "," : "\t";
            path = Files.writeString(directory.resolve("samples." + format),
                    String.join(delimiter, "name", "host_sex") + "\n" + String.join(delimiter, "valid", "female") + "\n"
                            + String.join(delimiter, "invalid", "unsupported") + "\n");
        } else {
            try (Workbook workbook = workbook(format)) {
                row(workbook.createSheet("Samples"), 0, "name", "host_sex");
                row(workbook.getSheetAt(0), 1, "valid", "female");
                row(workbook.getSheetAt(0), 2, "invalid", "unsupported");
                path = write(workbook, format);
            }
        }
        FileUpload upload = upload(path, format);
        assertEquals(400, sampleController.importCSV(project.id, upload).getStatus());
        em.flush();
        assertTrue(Sample.findBySampleNameInProject("valid", project.id).isPresent());
        assertTrue(Sample.findBySampleNameInProject("invalid", project.id).isEmpty());
        assertEquals(List.of("female"), globals.get("host_sex").terms());
    }

    @Test
    void viewerCannotImportEitherTable() throws Exception {
        seed();
        as(viewer);
        try (Workbook workbook = sampleWorkbook("new", "group", "control")) {
            FileUpload upload = upload(write(workbook, "xlsx"), "xlsx");
            assertEquals(403, assertThrows(WebApplicationException.class, () -> sampleController.importCSV(project.id, upload))
                    .getResponse().getStatus());
            assertEquals(403, assertThrows(WebApplicationException.class,
                    () -> assayController.importExperiments(project.id, UUID.randomUUID(), upload)).getResponse().getStatus());
            assertEquals(0, Sample.count("project.id", project.id));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx", "csv", "tsv"})
    void importsExperimentRowsIntoExistingAssayAndRejectsUnlinkedSamples(String format) throws Exception {
        seed();
        Sample sample = new Sample();
        sample.name = "linked";
        sample.project = project;
        sample.persist();
        Assay assay = new Assay();
        assay.name = "experiment";
        assay.project = project;
        assay.addSample(sample);
        assay.persist();
        em.flush();
        as(editor);
        try (Workbook workbook = workbook(format)) {
            Sheet sheet = workbook.createSheet("Experiments");
            row(sheet, 0, "Sample", "File Name", "File md5", "Forward File Name", "Forward File md5", "Reverse File Name", "Reverse File md5");
            row(sheet, 1, "unlinked", "all.fastq", "md5", "forward.fastq", "md5", "reverse.fastq", "md5");
            row(sheet, 2, "linked", "all.fastq", "md5", "forward.fastq", "md5", "reverse.fastq", "md5");
            Path path;
            if (format.equals("csv") || format.equals("tsv")) {
                String delimiter = format.equals("csv") ? "," : "\t";
                StringBuilder content = new StringBuilder();
                for (var row : sheet) {
                    content.append(String.join(delimiter, java.util.stream.StreamSupport.stream(row.spliterator(), false)
                            .map(cell -> cell.getStringCellValue()).toList())).append("\n");
                }
                path = Files.writeString(directory.resolve("table." + format), content.toString());
            } else {
                path = write(workbook, format);
            }
            var errors = experiments.importIntoAssay(project.id, assay.id, path.toFile());
            em.flush();
            assertEquals(1, errors.size(), errors.toString());
            assertEquals(format.equals("csv") || format.equals("tsv") ? "Row 1" : "Experiments!row 2", errors.getFirst().row());
            assertEquals(3, no.metatrack.server.file.File.count("assay.id", assay.id));
            assertEquals(1, Assay.count("project.id", project.id));
        }
    }

    private void as(UUID id) { when(users.requireCurrentUser()).thenReturn(new CurrentUser(id.toString(), "test", Set.of(), null, null, null)); }
    private Workbook workbook(String format) { return format.equals("xls") ? new HSSFWorkbook() : new XSSFWorkbook(); }
    private Workbook sampleWorkbook(String name, String key, String value) {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Samples");
        row(sheet, 0, "name", key);
        row(sheet, 1, name, value);
        return workbook;
    }
    private Sheet vocabulary(Workbook workbook, String key, String value) {
        Sheet sheet = workbook.createSheet("Vocabularies");
        row(sheet, 0, "field_key", "value");
        row(sheet, 1, key, value);
        return sheet;
    }
    private Path write(Workbook workbook, String format) throws Exception {
        Path path = directory.resolve("table." + format);
        try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        return path;
    }
    private FileUpload upload(Path path, String format) {
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(path);
        when(upload.fileName()).thenReturn("table." + format);
        when(upload.contentType()).thenReturn(format.equals("csv") ? "text/csv" : format.equals("tsv") ? "text/tab-separated-values" : "application/octet-stream");
        return upload;
    }
}
