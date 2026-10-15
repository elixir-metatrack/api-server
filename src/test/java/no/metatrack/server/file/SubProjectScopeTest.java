package no.metatrack.server.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.assay.AssayService;
import no.metatrack.server.assay.CSVExperimentImportService;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.project.ProjectService;
import no.metatrack.server.sample.BulkPatchSampleRequest;
import no.metatrack.server.sample.CSVSampleSheetImportService;
import no.metatrack.server.sample.LinkSamplesRequest;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleService;
import no.metatrack.server.stats.StatisticsService;
import no.metatrack.server.stats.StorageStatistics;
import no.metatrack.server.sample.metadata.CreateSampleMetadataFieldRequest;
import no.metatrack.server.sample.metadata.SampleMetadataFieldService;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.PutSampleVocabularyRequest;
import no.metatrack.server.sample.vocabulary.SampleVocabularyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(SubProjectTestProfile.class)
@TestTransaction
class SubProjectScopeTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID EDITOR = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID PARENT_ADMIN = UUID.randomUUID();

    @Inject ProjectService projectService;
    @Inject ProjectRoleCheck roleCheck;
    @Inject SampleService sampleService;
    @Inject AssayService assayService;
    @Inject FileService fileService;
    @Inject StatisticsService statisticsService;
    @Inject FileIngestService fileIngestService;
    @Inject PresignUrlService presignService;
    @Inject CSVSampleSheetImportService sampleImport;
    @Inject CSVExperimentImportService experimentImport;
    @Inject SampleMetadataFieldService fieldService;
    @Inject SampleMetadataService metadataService;
    @Inject SampleVocabularyManagementService vocabularyService;
    @Inject EntityManager entityManager;
    @Inject ObjectMapper mapper;
    @Inject Validator validator;
    @TempDir Path directory;

    UserService users;
    ObjectStorage storage;

    @BeforeEach
    void mockExternalServices() {
        users = mock(UserService.class);
        storage = mock(S3ObjectStorage.class);
        QuarkusMock.installMockForType(users, UserService.class);
        QuarkusMock.installMockForType(storage, ObjectStorage.class);
        asUser(OWNER);
    }

    @Test
    void viewerSeesOnlyLinkedSamplesAndTheirAssayFiles() {
        Fixture f = fixture();
        asUser(VIEWER);
        assertTrue(roleCheck.isAtLeast(f.sub.id, ProjectRole.VIEWER));
        assertFalse(roleCheck.isAtLeast(f.sub.id, ProjectRole.EDITOR));
        assertEquals(List.of(f.visible), sampleService.getAllSamples(f.sub.id));
        assertEquals(List.of(f.shared), assayService.getAllAssaysInProject(f.sub.id));
        assertEquals(List.of(f.visible), assayService.getAllSamplesInAssay(f.sub.id, f.shared.id));
        assertEquals(List.of(f.visibleFile), fileService.getAllFilesInAssay(f.sub.id, f.shared.id));
        assertEquals(List.of(f.visibleFile), fileService.getAllFilesInSample(f.sub.id, f.visible.id));
        assertEquals(List.of(f.visibleFile), fileService.getFilesInSampleAndAssay(f.sub.id, f.shared.id, f.visible.id));
        assertEquals(List.of(f.hidden), assayService.getAllSamplesInAssay(f.sibling.id, f.shared.id));
        assertEquals(List.of(f.hiddenFile), fileService.getAllFilesInAssay(f.sibling.id, f.shared.id));
    }

    @Test
    void sampleAssaysRespectLinkedSampleScope() {
        Fixture f = fixture();
        asUser(VIEWER);
        assertEquals(List.of(f.shared), assayService.getAllAssaysInSample(f.sub.id, f.visible.id));
        assertThrows(NotFoundException.class, () -> assayService.getAllAssaysInSample(f.sub.id, f.hidden.id));
        assertThrows(NotFoundException.class, () -> assayService.getAllAssaysInSample(f.sub.id, f.foreign.id));
        asUser(OWNER);
        assertEquals(Set.of(f.shared, f.hiddenAssay),
                Set.copyOf(assayService.getAllAssaysInSample(f.root.id, f.hidden.id)));
    }

    @Test
    void storageStatisticsCountOnlyUploadedFilesOfLinkedSamples() {
        Fixture f = fixture();
        f.visibleFile.status = UploadStatus.UPLOADED;
        f.hiddenFile.status = UploadStatus.UPLOADED;
        f.visibleFile.objectKey = f.root.id + "/visible";
        f.hiddenFile.objectKey = f.root.id + "/hidden";
        entityManager.flush();
        when(storage.listObjects(f.root.id + "/")).thenReturn(List.of(
                new StorageObjectMetadata(f.visibleFile.objectKey, 10L),
                new StorageObjectMetadata(f.hiddenFile.objectKey, 20L),
                new StorageObjectMetadata(f.root.id + "/untracked", 100L)));
        assertEquals(new StorageStatistics(1L, 10L), statisticsService.getProjectStorageStatistics(f.sub.id));
        assertEquals(new StorageStatistics(1L, 20L), statisticsService.getProjectStorageStatistics(f.sibling.id));
        assertEquals(new StorageStatistics(2L, 30L), statisticsService.getProjectStorageStatistics(f.root.id));
        verify(storage, never()).listObjects(f.sub.id + "/");
    }

    @Test
    void rootRetainsAllSamplesAssaysAndFiles() {
        Fixture f = fixture();
        assertEquals(Set.of(f.visible, f.hidden), Set.copyOf(sampleService.getAllSamples(f.root.id)));
        assertEquals(Set.of(f.shared, f.hiddenAssay), Set.copyOf(assayService.getAllAssaysInProject(f.root.id)));
        assertEquals(Set.of(f.visible, f.hidden), Set.copyOf(assayService.getAllSamplesInAssay(f.root.id, f.shared.id)));
        assertEquals(Set.of(f.visibleFile, f.hiddenFile), Set.copyOf(fileService.getAllFilesInAssay(f.root.id, f.shared.id)));
    }

    @Test
    void knownHiddenAndForeignSampleIdsAndNamesRemainInaccessible() {
        Fixture f = fixture();
        assertThrows(NotFoundException.class, () -> sampleService.getSampleById(f.hidden.id, f.sub.id));
        assertThrows(NotFoundException.class, () -> sampleService.getSampleByName(f.hidden.name, f.sub.id));
        assertThrows(NotFoundException.class, () -> sampleService.getSampleById(f.foreign.id, f.sub.id));
        assertThrows(NotFoundException.class, () -> fileService.getAllFilesInSample(f.sub.id, f.hidden.id));
        assertThrows(NotFoundException.class,
                () -> fileService.getFilesInSampleAndAssay(f.sub.id, f.shared.id, f.hidden.id));
    }

    @Test
    void knownHiddenAndForeignAssayIdsRemainInaccessible() {
        Fixture f = fixture();
        assertThrows(NotFoundException.class, () -> assayService.getAssayById(f.sub.id, f.hiddenAssay.id));
        assertThrows(NotFoundException.class, () -> assayService.getAssayById(f.root.id, f.foreignAssay.id));
        assertThrows(NotFoundException.class, () -> fileService.getAllFilesInAssay(f.sub.id, f.hiddenAssay.id));
        assertThrows(NotFoundException.class, () -> assayService.getAllSamplesInAssay(f.sub.id, f.foreignAssay.id));
    }

    @Test
    void foreignAssayCannotBePatchedDeletedOrAssignedSamples() {
        Fixture f = fixture();
        assertThrows(NotFoundException.class, () -> assayService.updateAssay(f.root.id, f.foreignAssay.id,
                "changed", null, null, null, null, null, null, null, null));
        assertThrows(NotFoundException.class, () -> assayService.deleteAssay(f.root.id, f.foreignAssay.id));
        assertThrows(NotFoundException.class,
                () -> assayService.addSamplesToAssay(f.sub.id, List.of(f.visible.name), f.foreignAssay.id));
        assertThrows(NotFoundException.class,
                () -> assayService.removeSamplesFromAssay(f.sub.id, List.of(f.visible.name), f.foreignAssay.id));
        assertEquals("foreign-assay", f.foreignAssay.name);
        assertEquals(Set.of(f.foreign), f.foreignAssay.samples);
    }

    @Test
    void editorCannotExpandOrShrinkCuratedScope() {
        Fixture f = fixture();
        asUser(EDITOR);
        assertTrue(roleCheck.isAtLeast(f.sub.id, ProjectRole.EDITOR));
        assertStatus(403, () -> sampleService.linkSamples(f.sub.id, List.of(f.hidden.id)));
        assertStatus(403, () -> sampleService.unlinkSamples(f.sub.id, List.of(f.visible.id)));
        assertEquals(Set.of(f.visible), f.sub.linkedSamples);
    }

    @Test
    void parentAdminCanLinkAndUnlinkWithoutSubProjectMembership() {
        Fixture f = fixture();
        asUser(PARENT_ADMIN);
        sampleService.linkSamples(f.sub.id, List.of(f.hidden.id));
        entityManager.flush();
        assertEquals(Set.of(f.visible, f.hidden), Set.copyOf(sampleService.getAllSamples(f.sub.id)));
        sampleService.unlinkSamples(f.sub.id, List.of(f.hidden.id));
        entityManager.flush();
        assertEquals(List.of(f.visible), sampleService.getAllSamples(f.sub.id));
        assertTrue(Sample.findByIdOptional(f.hidden.id).isPresent());
    }

    @Test
    void linkRejectsForeignRootSamples() {
        Fixture f = fixture();
        asUser(PARENT_ADMIN);
        assertStatus(400, () -> sampleService.linkSamples(f.sub.id, List.of(f.foreign.id)));
        assertEquals(Set.of(f.visible), f.sub.linkedSamples);
    }

    @Test
    void linkRequestRequiresIdsButAcceptsAnEmptyNoOp() {
        assertFalse(validator.validate(new LinkSamplesRequest(null)).isEmpty());
        assertFalse(validator.validate(new LinkSamplesRequest(Arrays.asList((UUID) null))).isEmpty());
        assertTrue(validator.validate(new LinkSamplesRequest(List.of())).isEmpty());
        Fixture f = fixture();
        sampleService.linkSamples(f.sub.id, List.of());
        sampleService.unlinkSamples(f.sub.id, List.of());
        assertEquals(Set.of(f.visible), f.sub.linkedSamples);
    }

    @Test
    void subProjectCannotPhysicallyDeleteSharedSamplesOrAssays() {
        Fixture f = fixture();
        asUser(EDITOR);
        assertThrows(ForbiddenException.class, () -> sampleService.deleteSample(f.sub.id, f.visible.id));
        assertThrows(ForbiddenException.class, () -> assayService.deleteAssay(f.sub.id, f.shared.id));
        assertTrue(Sample.findByIdOptional(f.visible.id).isPresent());
        assertTrue(Assay.findByIdOptional(f.shared.id).isPresent());
        assertEquals(Set.of(f.visible, f.hidden), f.shared.samples);
    }

    @Test
    void rootCanStillDeleteSamplesAndAssays() {
        Fixture f = fixture();
        Sample disposable = sample(f.root, "disposable");
        Assay empty = assayService.createAssay(f.root.id, "empty", null, null, null, null, null, null, null, null);
        entityManager.flush();
        entityManager.clear();
        sampleService.deleteSample(f.root.id, disposable.id);
        assayService.deleteAssay(f.root.id, empty.id);
        entityManager.flush();
        assertTrue(Sample.findByIdOptional(disposable.id).isEmpty());
        assertTrue(Assay.findByIdOptional(empty.id).isEmpty());
    }

    @Test
    void subProjectCannotCreateAnInvisibleEmptyAssay() {
        Fixture f = fixture();
        assertThrows(ForbiddenException.class,
                () -> assayService.createAssay(f.sub.id, "empty", null, null, null, null, null, null, null, null));
    }

    @Test
    void editingVisibleAssayDoesNotChangeHiddenSampleRelationships() {
        Fixture f = fixture();
        assayService.updateAssay(f.sub.id, f.shared.id, "updated", null, null, null, null, null, null, null, null);
        assertEquals("updated", f.shared.name);
        assertFalse(assayService.removeSamplesFromAssay(f.sub.id, List.of(f.hidden.name), f.shared.id).isEmpty());
        assertTrue(f.shared.samples.contains(f.hidden));
        assertTrue(assayService.removeSamplesFromAssay(f.sub.id, List.of(f.visible.name), f.shared.id).isEmpty());
        assertEquals(Set.of(f.hidden), f.shared.samples);
        entityManager.flush();
        entityManager.clear();
        assertTrue(File.findByUuidOptional(f.hiddenFile.uuid).isPresent());
        assertTrue(File.findByUuidOptional(f.visibleFile.uuid).isEmpty());
    }

    @Test
    void fileDeleteChecksSampleScopeBeforeTouchingStorage() {
        Fixture f = fixture();
        assertThrows(NotFoundException.class,
                () -> fileIngestService.deleteFile(f.sub.id, f.hiddenFile.uuid, f.hidden.id));
        assertThrows(NotFoundException.class,
                () -> fileIngestService.deleteFile(f.sub.id, f.hiddenFile.uuid, f.visible.id));
        verifyNoInteractions(storage);
    }

    @Test
    void visibleFileCanBeDeletedWithoutDeletingHiddenFiles() {
        Fixture f = fixture();
        fileIngestService.deleteFile(f.sub.id, f.visibleFile.uuid, f.visible.id);
        verify(storage).delete(f.visibleFile.objectKey);
        verifyNoMoreInteractions(storage);
        assertTrue(File.findByUuidOptional(f.hiddenFile.uuid).isPresent());
    }

    @Test
    void presignUsesCanonicalRootPathAndPreservesExistingKeys() {
        Fixture f = fixture();
        when(storage.presignUpload(anyString(), any())).thenReturn("upload-url");
        when(storage.presignDownload(anyString(), any())).thenReturn("download-url");
        assertEquals(f.visibleFile.objectKey, presignService.presignedUploadUrl(
                f.sub.id, f.shared.id, f.visible.name, f.visibleFile.fileName, 600).objectKey());
        assertEquals(f.visibleFile.objectKey, presignService.presignedDownloadUrl(
                f.sub.id, f.shared.id, f.visible.name, f.visibleFile.fileName, 600).objectKey());
        assertEquals(f.visibleFile.objectKey, presignService.presignedDownloadUrl(
                f.root.id, f.shared.id, f.visible.name, f.visibleFile.fileName, 600).objectKey());
        PresignedUrl newUpload = presignService.presignedUploadUrl(
                f.sub.id, f.shared.id, f.visible.name, "new.fastq", 600);
        assertTrue(newUpload.objectKey().startsWith(f.root.id + "/" + f.shared.id + "/visible/new.fastq/"));
        entityManager.flush();
        assertEquals(newUpload.objectKey(), presignService.presignedDownloadUrl(
                f.root.id, f.shared.id, f.visible.name, "new.fastq", 600).objectKey());
        verify(storage).presignUpload(f.visibleFile.objectKey, Duration.ofSeconds(600));
    }

    @Test
    void presignRejectsHiddenSamplesAndForeignAssaysBeforeStorageAccess() {
        Fixture f = fixture();
        assertThrows(NotFoundException.class, () -> presignService.presignedUploadUrl(
                f.sub.id, f.shared.id, f.hidden.name, "reads.fastq", 600));
        assertThrows(NotFoundException.class, () -> presignService.presignedDownloadUrl(
                f.sub.id, f.shared.id, f.hidden.name, "reads.fastq", 600));
        assertThrows(NotFoundException.class, () -> presignService.presignedUploadUrl(
                f.sub.id, f.foreignAssay.id, f.visible.name, "reads.fastq", 600));
        verifyNoInteractions(storage);
    }

    @Test
    void presignRejectsFilePathWithMismatchedSampleRelationship() {
        Fixture f = fixture();
        f.visibleFile.sample = f.hidden;
        entityManager.flush();
        assertThrows(NotFoundException.class, () -> presignService.presignedDownloadUrl(
                f.sub.id, f.shared.id, f.visible.name, "reads.fastq", 600));
        assertThrows(NotFoundException.class, () -> presignService.presignedUploadUrl(
                f.sub.id, f.shared.id, f.visible.name, "reads.fastq", 600));
        verifyNoInteractions(storage);
    }

    @Test
    void sampleCsvUsesRootMetadataAndOwnershipAndLinksOnlyNewSamples() throws Exception {
        Fixture f = fixture();
        configureMetadata(f.root);
        Path csv = Files.writeString(directory.resolve("samples.csv"), "name,custom_status\nnew-sample,known\nhidden,known\ninvalid,unknown\n");
        var errors = sampleImport.importNewSamples(f.sub.id, csv.toFile());
        entityManager.flush();
        assertEquals(2, errors.size());
        Sample created = sampleService.getSampleByName("new-sample", f.sub.id);
        assertEquals(f.root.id, created.project.id);
        assertEquals(Map.of("custom_status", "known"), metadataService.getActiveMetadata(List.of(created)).get(created.id));
        assertTrue(Sample.findBySampleNameInProject("invalid", f.root.id).isEmpty());
        assertFalse(f.sub.linkedSamples.contains(f.hidden));
        assertEquals(created.id, sampleService.getSampleByName("new-sample", f.root.id).id);
    }

    @Test
    void bulkPatchUsesRootRulesAndCannotChangeHiddenSamples() throws Exception {
        Fixture f = fixture();
        configureMetadata(f.root);
        asUser(EDITOR);
        assertTrue(roleCheck.isAtLeast(f.sub.id, ProjectRole.EDITOR));
        BulkPatchSampleRequest request = mapper.readValue("""
                {"sampleData":[
                  {"name":"visible","alias":"edited","customMetadata":{"custom_status":"known"}},
                  {"name":"hidden","alias":"forbidden"}
                ]}
                """, BulkPatchSampleRequest.class);
        var errors = sampleService.bulkPatchSamples(f.sub.id, request);
        entityManager.flush();
        assertEquals(1, errors.size());
        assertEquals("edited", f.visible.alias);
        assertNull(f.hidden.alias);
        assertEquals(Map.of("custom_status", "known"), metadataService.getActiveMetadata(List.of(f.visible)).get(f.visible.id));
        BulkPatchSampleRequest invalid = mapper.readValue("""
                {"sampleData":[{"name":"visible","alias":"invalid","customMetadata":{"custom_status":"unknown"}}]}
                """, BulkPatchSampleRequest.class);
        assertEquals(1, sampleService.bulkPatchSamples(f.sub.id, invalid).size());
        assertEquals("edited", f.visible.alias);
    }

    @Test
    void inheritedMetadataAndVocabulariesAreReadableButNotConfigurableInSubProject() {
        Fixture f = fixture();
        configureMetadata(f.root);
        assertEquals("custom_status", fieldService.list(f.sub.id, false).getFirst().key);
        assertEquals(vocabularyService.list(f.root.id), vocabularyService.list(f.sub.id));
        assertEquals(vocabularyService.get(f.root.id, "custom_status"), vocabularyService.get(f.sub.id, "custom_status"));
        assertThrows(ForbiddenException.class, () -> fieldService.create(f.sub.id,
                new CreateSampleMetadataFieldRequest("new_field", "New", SampleMetadataFieldType.TEXT)));
        assertThrows(ForbiddenException.class, () -> vocabularyService.replace(f.sub.id, "custom_status",
                new PutSampleVocabularyRequest(List.of("changed"))));
    }

    @Test
    void experimentCsvImportsOnlyVisibleSamplesUsingRootFilePaths() throws Exception {
        Fixture f = fixture();
        Path csv = Files.writeString(directory.resolve("experiments.csv"),
                "Sample,File Name,File md5,Forward File Name,Forward File md5,Reverse File Name,Reverse File md5\n"
                        + "visible,import.fastq,abc,forward.fastq,def,reverse.fastq,ghi\n"
                        + "hidden,secret.fastq,abc,secret-forward.fastq,def,secret-reverse.fastq,ghi\n");
        var errors = experimentImport.importIntoAssay(f.sub.id, f.shared.id, csv.toFile());
        entityManager.flush();
        assertEquals(1, errors.size());
        File imported = File.findByVirtualPathOptional(
                PresignUrlService.virtualPath(f.root.id, f.shared.id, "visible", "import.fastq")).orElseThrow();
        assertEquals(f.visible, imported.sample);
        assertEquals(f.shared, imported.assay);
        assertEquals("abc", imported.md5);
        assertTrue(File.findByVirtualPathOptional(
                PresignUrlService.virtualPath(f.root.id, f.shared.id, "hidden", "secret.fastq")).isEmpty());
    }

    @Test
    void experimentCsvRejectsForeignAssayBeforeReadingFile() {
        Fixture f = fixture();
        assertThrows(NotFoundException.class, () -> experimentImport.importIntoAssay(f.sub.id, f.foreignAssay.id, null));
    }

    @Test
    void singleSampleCreateUsesRootOwnershipAndLinksIntoSubProject() {
        Fixture f = fixture();
        configureMetadata(f.root);
        asUser(EDITOR);
        assertTrue(roleCheck.isAtLeast(f.sub.id, ProjectRole.EDITOR));
        Sample created = createSampleWithMetadata(f.sub.id);
        entityManager.flush();
        assertEquals(f.root.id, created.project.id);
        assertEquals(created.id, sampleService.getSampleByName("single-created", f.sub.id).id);
        assertEquals(created.id, sampleService.getSampleByName("single-created", f.root.id).id);
        assertEquals(Map.of("custom_status", "known"), metadataService.getActiveMetadata(List.of(created)).get(created.id));
    }

    @Test
    void editorCanPatchVisibleSampleWithRootMetadata() {
        Fixture f = fixture();
        configureMetadata(f.root);
        asUser(EDITOR);
        assertTrue(roleCheck.isAtLeast(f.sub.id, ProjectRole.EDITOR));
        updateSampleWithMetadata(f.sub.id, f.visible.id);
        entityManager.flush();
        assertEquals("edited", f.visible.alias);
        assertNull(f.hidden.alias);
        assertEquals(Map.of("custom_status", "known"), metadataService.getActiveMetadata(List.of(f.visible)).get(f.visible.id));
    }

    @Test
    void knownHiddenSampleCannotBePatched() {
        Fixture f = fixture();
        asUser(EDITOR);
        assertThrows(NotFoundException.class, () -> updateSampleWithMetadata(f.sub.id, f.hidden.id));
        assertNull(f.hidden.alias);
    }

    private Sample createSampleWithMetadata(Long projectId) {
        return sampleService.createSample(
                projectId, "single-created", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, Map.of("custom_status", "known"));
    }

    private void updateSampleWithMetadata(Long projectId, UUID sampleId) {
        sampleService.updateSample(
                projectId, sampleId, null, "edited", null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, Map.of("custom_status", "known"));
    }

    private Fixture fixture() {
        Project root = projectService.createProject("root", "test", OWNER.toString());
        Project other = projectService.createProject("other", "test", OWNER.toString());
        Sample visible = sample(root, "visible");
        Sample hidden = sample(root, "hidden");
        Sample foreign = sample(other, "foreign");
        Assay shared = assay(root, "shared", visible, hidden);
        Assay hiddenAssay = assay(root, "hidden-assay", hidden);
        Assay foreignAssay = assay(other, "foreign-assay", foreign);
        entityManager.flush();
        File visibleFile = file(shared, visible);
        File hiddenFile = file(shared, hidden);
        Project sub = projectService.createSubProject(root.id, "sub", "test", List.of(visible.id), OWNER.toString());
        Project sibling = projectService.createSubProject(root.id, "sibling", "test", List.of(hidden.id), OWNER.toString());
        projectService.addMember(sub.id, VIEWER, ProjectRole.VIEWER);
        projectService.addMember(sub.id, EDITOR, ProjectRole.EDITOR);
        projectService.addMember(root.id, PARENT_ADMIN, ProjectRole.ADMIN);
        entityManager.flush();
        return new Fixture(root, sub, sibling, visible, hidden, foreign, shared, hiddenAssay, foreignAssay, visibleFile, hiddenFile);
    }

    private Sample sample(Project root, String name) {
        Sample sample = new Sample();
        sample.project = root;
        sample.name = name;
        sample.persist();
        // Keep both sides consistent with the production create path.
        root.samples.add(sample);
        return sample;
    }

    private Assay assay(Project root, String name, Sample... samples) {
        Assay assay = new Assay();
        assay.project = root;
        assay.name = name;
        for (Sample sample : samples) assay.addSample(sample);
        assay.persist();
        root.assays.add(assay);
        return assay;
    }

    private File file(Assay assay, Sample sample) {
        File file = new File();
        file.uuid = UUID.randomUUID();
        file.sample = sample;
        file.assay = assay;
        file.fileName = "reads.fastq";
        file.virtualPath = PresignUrlService.virtualPath(assay.project.id, assay.id, sample.name, file.fileName);
        file.objectKey = "existing/" + file.uuid;
        file.status = UploadStatus.PENDING;
        file.persist();
        sample.files.add(file);
        return file;
    }

    private void configureMetadata(Project root) {
        fieldService.create(root.id,
                new CreateSampleMetadataFieldRequest("custom_status", "Status", SampleMetadataFieldType.TEXT));
        vocabularyService.replace(root.id, "custom_status", new PutSampleVocabularyRequest(List.of("known")));
        entityManager.flush();
    }

    private void asUser(UUID userId) {
        when(users.requireCurrentUser()).thenReturn(new CurrentUser(userId.toString(), "test", Set.of(), null, null, null));
    }

    private void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(WebApplicationException.class, action).getResponse().getStatus());
    }

    private record Fixture(Project root, Project sub, Project sibling, Sample visible, Sample hidden, Sample foreign,
            Assay shared, Assay hiddenAssay, Assay foreignAssay, File visibleFile, File hiddenFile) {}
}
