package no.metatrack.server.upload;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileIngestService;
import no.metatrack.server.file.PresignUrlService;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.file.SubProjectDatabase;
import no.metatrack.server.file.StorageObjectIdentity;
import no.metatrack.server.file.UploadStatus;
import no.metatrack.server.project.CreateProjectRequest;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectService;
import no.metatrack.server.sample.Sample;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(UploadBatchRegistrationTestProfile.class)
@TestTransaction
class UploadBatchRegistrationPostgresTest {
    private static final String CHECKSUM = "b".repeat(64);
    private static final String SSH_PUBLIC_KEY = "ssh-ed25519 YWJjZGVmZ2hpamtsbW5vcA== batch-test";

    @Inject
    UploadBatchRegistrationService registrationService;

    @Inject
    UploadBatchQueryService queryService;

    @Inject
    UploadBatchLifecycleService lifecycleService;

    @Inject
    UploadBatchProvisioningStateService provisioningStateService;

    @Inject
    UploadBatchIngestionStateService ingestionStateService;

    @Inject
    UploadBatchAdmissionService admissionService;

    @Inject
    UploadBatchCleanupStateService cleanupStateService;

    @Inject
    FileIngestService fileIngestService;

    @Inject
    PresignUrlService presignUrlService;

    @Inject
    EntityManager entityManager;

    @Inject
    ProjectService projectService;

    @TempDir
    Path temporaryDirectory;

    @org.junit.jupiter.api.BeforeEach
    void setUpProjectAuthorization() {
        ProjectRoleCheck roleCheck = mock(ProjectRoleCheck.class);
        when(roleCheck.isAtLeast(anyLong(), eq(ProjectRole.OWNER))).thenReturn(true);
        QuarkusMock.installMockForType(roleCheck, ProjectRoleCheck.class);
    }

    @Test
    void registersBatchAndAtomicallyReservesPendingAndNewDestinations() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("batch-root", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        Optional<String> pendingResult = File.importPending(
                project.id, assay.id, sample, assay, "R1.fastq", "declared-md5", null, ReadRole.FORWARD);
        assertTrue(pendingResult.isEmpty());
        entityManager.flush();

        UploadBatchRegistrationResult result = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256,readRole\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",400," + CHECKSUM + ",FORWARD\n"
                        + "reads/R2.fastq,sample-a," + assay.id + ",500," + CHECKSUM + ",REVERSE\n"),
                SSH_PUBLIC_KEY);

        assertTrue(result.accepted());
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", result.batchId()).firstResult();
        assertEquals(UploadBatchStatus.PROVISIONING, batch.status);
        assertEquals(900, batch.reservedBytes);
        assertEquals(2, batch.entries.size());
        UploadBatchEntry existingEntry = batch.entries.stream()
                .filter(entry -> entry.fileName.equals("R1.fastq"))
                .findFirst().orElseThrow();
        UploadBatchEntry newEntry = batch.entries.stream()
                .filter(entry -> entry.fileName.equals("R2.fastq"))
                .findFirst().orElseThrow();

        assertEquals("declared-md5", existingEntry.file.md5());
        assertEquals(ReadRole.FORWARD, existingEntry.file.readRole());
        assertEquals(UploadStatus.PENDING, existingEntry.file.status());
        assertEquals(existingEntry, existingEntry.file.uploadBatchEntry());
        assertNull(existingEntry.file.uploadedBy());
        assertNotNull(existingEntry.stagingKey);
        assertEquals(newEntry.destinationObjectKey, newEntry.file.objectKey());
        assertEquals(900, stagingBudget().reservedBytes);

        UploadBatchReport creatorReport = queryService.getReport(project.id, result.batchId(), uploaderId);
        assertEquals(UploadBatchStatus.PROVISIONING, creatorReport.status());
        assertEquals(2, creatorReport.entries().size());

        UUID adminId = UUID.randomUUID();
        UUID editorId = UUID.randomUUID();
        projectService.addMember(project.id, adminId, ProjectRole.ADMIN);
        projectService.addMember(project.id, editorId, ProjectRole.EDITOR);
        entityManager.flush();
        assertEquals(2, queryService.getReport(project.id, result.batchId(), adminId).entries().size());
        assertThrows(ForbiddenException.class, () -> queryService.getReport(project.id, result.batchId(), editorId));

        UploadBatchRegistrationResult duplicate = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256,readRole\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",400," + CHECKSUM + ",FORWARD\n"
                        + "reads/R2.fastq,sample-a," + assay.id + ",500," + CHECKSUM + ",REVERSE\n"),
                SSH_PUBLIC_KEY);
        assertFalse(duplicate.accepted());
        assertEquals(1, UploadBatch.count());
        assertEquals(900, stagingBudget().reservedBytes);

        assertThrows(WebApplicationException.class, () -> presignUrlService.presignedUploadUrl(
                project.id, assay.id, sample.name, "R1.fastq", 600));
    }

    @Test
    void invalidManifestDoesNotCreateBatchFilesOrReserveBytes() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("invalid-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");

        UploadBatchRegistrationResult result = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",400," + CHECKSUM + "\n"
                        + "reads/R2.fastq,unknown-sample," + assay.id + ",500," + CHECKSUM + "\n"),
                SSH_PUBLIC_KEY);

        assertFalse(result.accepted());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.column().equals("sampleName")));
        assertEquals(0, UploadBatch.count());
        assertEquals(0, stagingBudget().reservedBytes);
        assertTrue(File.findByVirtualPathOptional(PresignUrlService.virtualPath(
                project.id, assay.id, sample.name, "R1.fastq")).isEmpty());
    }

    @Test
    void rejectsBatchWhenDeclaredBytesExceedAvailableStagingBudget() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("budget-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatchStagingBudget budget = stagingBudget();
        budget.reservedBytes = 999_999_900L;
        entityManager.flush();

        WebApplicationException exception = assertThrows(WebApplicationException.class,
                () -> registrationService.register(
                        project.id,
                        uploaderId,
                        manifest("relativePath,sampleName,assayId,sizeBytes,sha256\n"
                                + "reads/R1.fastq,sample-a," + assay.id + ",600," + CHECKSUM + "\n"
                                + "reads/R2.fastq,sample-a," + assay.id + ",600," + CHECKSUM + "\n"),
                        SSH_PUBLIC_KEY));

        assertEquals(507, exception.getResponse().getStatus());
        assertEquals(0, UploadBatch.count());
        assertEquals(999_999_900L, budget.reservedBytes);
    }

    @Test
    void requiresParentEditorMembershipForSubProjectUpload() throws IOException {
        UUID parentOwnerId = UUID.randomUUID();
        UUID uploaderId = UUID.randomUUID();
        Project parent = project("parent-project", parentOwnerId);
        Sample sample = sample(parent, "sample-a");
        Assay assay = assay(parent, sample, "assay-a");
        Project child = projectService.createSubProject(
                parent.id, "child-project-" + UUID.randomUUID(), "test", List.of(sample.id), uploaderId.toString());
        projectService.addMember(parent.id, uploaderId, ProjectRole.VIEWER);
        entityManager.flush();

        assertThrows(ForbiddenException.class, () -> registrationService.register(
                child.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",400," + CHECKSUM + "\n"),
                SSH_PUBLIC_KEY));
        assertEquals(0, UploadBatch.count());
    }

    @Test
    void freezesAdmissionAndDrainsVisibleTransfersBeforeProcessing() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("drain-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatchRegistrationResult result = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",400," + CHECKSUM + "\n"),
                SSH_PUBLIC_KEY);
        assertTrue(provisioningStateService.markOpen(result.batchId()));
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", result.batchId()).firstResult();
        UploadBatchEntry entry = batch.entries.getFirst();
        SftpGoHookEvent event = new SftpGoHookEvent(
                "pre-upload", batch.sftpUsername, entry.relativePath, 0, 0);

        assertTrue(admissionService.admit(event, List.of()));
        assertEquals(UploadBatchStatus.DRAINING,
                lifecycleService.finalizeBatch(project.id, batch.id, uploaderId));
        assertEquals(UploadBatchStatus.DRAINING,
                lifecycleService.finalizeBatch(project.id, batch.id, uploaderId));
        assertFalse(admissionService.admit(event, List.of()));

        List<SftpGoConnection> activeConnections = List.of(new SftpGoConnection(
                batch.sftpUsername,
                "connection-id",
                List.of(new SftpGoTransfer("upload", "/" + entry.relativePath, 200))));
        assertFalse(admissionService.advanceDrain(batch.id, activeConnections));
        assertEquals(UploadBatchStatus.DRAINING, batch.status);
        assertFalse(admissionService.advanceDrain(batch.id, List.of()));

        UploadBatchTransferAdmission admission = (UploadBatchTransferAdmission)
                UploadBatchTransferAdmission.find("entry.id", entry.id).firstResult();
        admission.admittedAt = Instant.now().minusSeconds(5);
        entityManager.flush();
        assertTrue(admissionService.advanceDrain(batch.id, List.of()));
        assertEquals(UploadBatchStatus.PROCESSING, batch.status);
        assertEquals(UploadBatchEntryStatus.REGISTERED, entry.status);
    }

    @Test
    void cancellationStopsAdmissionAndReleasesPendingFileReservation() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("cancel-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatchRegistrationResult result = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",400," + CHECKSUM + "\n"),
                SSH_PUBLIC_KEY);
        assertTrue(provisioningStateService.markOpen(result.batchId()));
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", result.batchId()).firstResult();
        UploadBatchEntry entry = batch.entries.getFirst();
        SftpGoHookEvent event = new SftpGoHookEvent(
                "pre-upload", batch.sftpUsername, entry.relativePath, 0, 0);
        assertTrue(admissionService.admit(event, List.of()));

        assertEquals(UploadBatchStatus.CANCELLED,
                lifecycleService.cancelBatch(project.id, batch.id, uploaderId));
        assertFalse(admissionService.admit(event, List.of()));
        assertNull(entry.file.uploadBatchEntry());
        assertEquals(400, stagingBudget().reservedBytes);
    }

    @Test
    void enforcesTwoConcurrentTransfersPerBatch() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("batch-transfer-limit", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatch batch = registerAndOpenBatch(project, uploaderId, sample, assay,
                List.of("reads/R1.fastq", "reads/R2.fastq", "reads/R3.fastq"));

        assertTrue(admissionService.admit(preUpload(batch, "reads/R1.fastq"), List.of()));
        assertTrue(admissionService.admit(preUpload(batch, "reads/R2.fastq"), List.of()));
        assertFalse(admissionService.admit(preUpload(batch, "reads/R3.fastq"), List.of()));
        assertEquals(2, UploadBatchTransferAdmission.count());
    }

    @Test
    void enforcesEightConcurrentTransfersAcrossBatches() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("global-transfer-limit", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        List<UploadBatch> batches = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            batches.add(registerAndOpenBatch(project, uploaderId, sample, assay,
                    List.of("reads/batch-" + index + ".fastq")));
        }

        int admitted = 0;
        for (UploadBatch batch : batches) {
            if (admissionService.admit(preUpload(batch, batch.entries.getFirst().relativePath), List.of())) {
                admitted++;
            }
        }

        assertEquals(8, admitted);
        assertEquals(8, UploadBatchTransferAdmission.count());
    }

    @Test
    void blocksNewWritesAfterUploaderLosesEditorMembership() throws IOException {
        UUID projectOwnerId = UUID.randomUUID();
        UUID uploaderId = UUID.randomUUID();
        Project project = project("revoked-uploader", projectOwnerId);
        projectService.addMember(project.id, uploaderId, ProjectRole.EDITOR);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatch batch = registerAndOpenBatch(project, uploaderId, sample, assay, List.of("reads/R1.fastq"));
        projectService.updateMemberRole(project.id, uploaderId, ProjectRole.VIEWER);

        assertFalse(admissionService.admit(preUpload(batch, "reads/R1.fastq"), List.of()));
        assertEquals(0, UploadBatchTransferAdmission.count());
    }

    @Test
    void expiryBlocksWritesAndRetainsDeclaredBudgetForRecoveryWindow() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("expiry-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatch batch = registerAndOpenBatch(project, uploaderId, sample, assay, List.of("reads/R1.fastq"));
        batch.expiresAt = Instant.now().minusSeconds(1);
        entityManager.flush();
        SftpGoHookEvent event = preUpload(batch, "reads/R1.fastq");

        lifecycleService.expireIfOpen(batch.id);

        assertEquals(UploadBatchStatus.EXPIRED, batch.status);
        assertTrue(batch.cleanupAfter.isAfter(Instant.now()));
        assertFalse(admissionService.admit(event, List.of()));
        assertNull(batch.entries.getFirst().file.uploadBatchEntry());
        assertEquals(100, stagingBudget().reservedBytes);
    }

    @Test
    void expiredIngestionLeaseIsReclaimedAndStaleWorkerTokenIsFenced() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("lease-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatch batch = registerAndOpenBatch(project, uploaderId, sample, assay, List.of("reads/R1.fastq"));
        assertEquals(UploadBatchStatus.DRAINING,
                lifecycleService.finalizeBatch(project.id, batch.id, uploaderId));
        assertTrue(admissionService.advanceDrain(batch.id, List.of()));

        UploadBatchEntryClaim firstClaim = ingestionStateService.claimNext().orElseThrow();
        assertEquals(1, firstClaim.attempts());
        assertTrue(ingestionStateService.renewLease(firstClaim.entryId(), firstClaim.leaseToken(), 25));
        UploadBatchEntry entry = (UploadBatchEntry) UploadBatchEntry.find("id", firstClaim.entryId()).firstResult();
        entry.processingLeaseUntil = Instant.now().minusSeconds(1);
        entityManager.flush();

        UploadBatchEntryClaim recoveredClaim = ingestionStateService.claimNext().orElseThrow();
        assertEquals(2, recoveredClaim.attempts());
        assertFalse(firstClaim.leaseToken().equals(recoveredClaim.leaseToken()));
        StorageObjectIdentity source = new StorageObjectIdentity(100, "source-etag", null,
                Instant.now(), Map.of());
        StorageObjectIdentity destination = new StorageObjectIdentity(100, "destination-etag", null,
                Instant.now(), Map.of());

        assertFalse(ingestionStateService.recordValidatedSource(entry.id, firstClaim.leaseToken(), source));
        assertTrue(ingestionStateService.recordValidatedSource(entry.id, recoveredClaim.leaseToken(), source));
        assertFalse(ingestionStateService.recordDestinationIdentity(entry.id, firstClaim.leaseToken(), destination));
        assertTrue(ingestionStateService.recordDestinationIdentity(entry.id, recoveredClaim.leaseToken(), destination));
    }

    @Test
    void commitsVerifiedMetadataAndNewPhysicalKeyWithoutReplacingPendingMetadata() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("commit-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        assertTrue(File.importPending(
                project.id, assay.id, sample, assay, "R1.fastq", "declared-md5", null, ReadRole.FORWARD).isEmpty());
        entityManager.flush();
        String oldObjectKey = File.findByVirtualPathOptional(
                PresignUrlService.virtualPath(project.id, assay.id, sample.name, "R1.fastq"))
                .orElseThrow().objectKey();

        UploadBatchRegistrationResult registration = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256,readRole\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",100," + CHECKSUM + ",FORWARD\n"),
                SSH_PUBLIC_KEY);
        assertTrue(registration.accepted());
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", registration.batchId()).firstResult();
        UploadBatchEntry entry = batch.entries.getFirst();
        assertTrue(provisioningStateService.markOpen(batch.id));
        batch.status = UploadBatchStatus.PROCESSING;
        entry.status = UploadBatchEntryStatus.PROMOTING;
        entry.processingLeaseToken = UUID.randomUUID();
        entry.destinationEtag = "destination-etag";
        entityManager.flush();
        StorageObjectIdentity destination = new StorageObjectIdentity(
                100, "destination-etag", null, Instant.now(), java.util.Map.of());

        assertTrue(fileIngestService.commitBatchUpload(
                entry.id, entry.processingLeaseToken, 100, CHECKSUM, destination));

        assertEquals(UploadStatus.UPLOADED, entry.file.status());
        assertEquals(100L, entry.file.sizeBytes());
        assertEquals(CHECKSUM, entry.file.sha256());
        assertEquals("declared-md5", entry.file.md5());
        assertEquals(ReadRole.FORWARD, entry.file.readRole());
        assertEquals(uploaderId, entry.file.uploadedBy());
        assertEquals(entry.destinationObjectKey, entry.file.objectKey());
        assertNotNull(entry.previousObjectKey);
        assertEquals(oldObjectKey, entry.previousObjectKey);
        assertEquals(UploadBatchStatus.SUCCEEDED, batch.status);
    }

    @Test
    void commitsEntriesIndependentlyAndMarksBatchPartialSuccess() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("partial-batch", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatchRegistrationResult registration = registrationService.register(
                project.id,
                uploaderId,
                manifest("relativePath,sampleName,assayId,sizeBytes,sha256\n"
                        + "reads/R1.fastq,sample-a," + assay.id + ",100," + CHECKSUM + "\n"
                        + "reads/R2.fastq,sample-a," + assay.id + ",100," + CHECKSUM + "\n"),
                SSH_PUBLIC_KEY);
        assertTrue(registration.accepted());
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", registration.batchId()).firstResult();
        assertTrue(provisioningStateService.markOpen(batch.id));
        assertEquals(UploadBatchStatus.DRAINING, lifecycleService.finalizeBatch(project.id, batch.id, uploaderId));
        assertTrue(admissionService.advanceDrain(batch.id, List.of()));

        UploadBatchEntryClaim firstClaim = ingestionStateService.claimNext().orElseThrow();
        UploadBatchEntryClaim secondClaim = ingestionStateService.claimNext().orElseThrow();
        StorageObjectIdentity firstSource = new StorageObjectIdentity(100, "source-etag-1", null,
                Instant.now(), Map.of());
        StorageObjectIdentity firstDestination = new StorageObjectIdentity(100, "destination-etag-1", null,
                Instant.now(), Map.of());
        assertTrue(ingestionStateService.recordValidatedSource(
                firstClaim.entryId(), firstClaim.leaseToken(), firstSource));
        assertTrue(ingestionStateService.recordDestinationIdentity(
                firstClaim.entryId(), firstClaim.leaseToken(), firstDestination));
        ingestionStateService.markFailed(secondClaim.entryId(), secondClaim.leaseToken(), "declared size mismatch");

        assertTrue(fileIngestService.commitBatchUpload(
                firstClaim.entryId(), firstClaim.leaseToken(), 100, CHECKSUM, firstDestination));

        UploadBatch persistedBatch = (UploadBatch) UploadBatch.find("id", batch.id).firstResult();
        assertEquals(UploadBatchStatus.PARTIAL_SUCCESS, persistedBatch.status);
        assertEquals(UploadBatchEntryStatus.COMMITTED, persistedBatch.entries.get(0).status);
        assertEquals(UploadBatchEntryStatus.FAILED, persistedBatch.entries.get(1).status);
        assertEquals(UploadStatus.UPLOADED, persistedBatch.entries.get(0).file.status());
        assertEquals(UploadStatus.PENDING, persistedBatch.entries.get(1).file.status());
        assertNull(persistedBatch.entries.get(1).file.uploadBatchEntry());
    }

    @Test
    void retriesFailedDestinationCleanupAfterStagingWasAlreadyRemoved() throws IOException {
        UUID uploaderId = UUID.randomUUID();
        Project project = project("cleanup-retry", uploaderId);
        Sample sample = sample(project, "sample-a");
        Assay assay = assay(project, sample, "assay-a");
        UploadBatch batch = registerAndOpenBatch(project, uploaderId, sample, assay, List.of("reads/R1.fastq"));
        long reservedBeforeCleanup = stagingBudget().reservedBytes;
        lifecycleService.cancelBatch(project.id, batch.id, uploaderId);
        assertEquals(reservedBeforeCleanup, stagingBudget().reservedBytes);
        UploadBatchEntry entry = batch.entries.getFirst();
        entry.stagingCleanupAfter = Instant.now().minusSeconds(1);
        cleanupStateService.markStagingCleanupCompleted(entry.id);
        cleanupStateService.markStagingCleanupCompleted(entry.id);
        assertEquals(reservedBeforeCleanup - entry.expectedSizeBytes, stagingBudget().reservedBytes);
        assertEquals(0, batch.reservedBytes);
        assertNotNull(batch.stagingCleanupCompletedAt);
        entityManager.flush();

        List<UploadBatchCleanupClaim> candidates = cleanupStateService.cleanupCandidates(50);
        assertEquals(1, candidates.size());
        assertEquals(entry.id, candidates.getFirst().entryId());
        assertFalse(candidates.getFirst().stagingDue());
        assertTrue(candidates.getFirst().destinationCleanupDue());

        cleanupStateService.markDestinationCleanupCompleted(entry.id);
        assertTrue(cleanupStateService.cleanupCandidates(50).isEmpty());
    }

    private Project project(String name, UUID ownerId) {
        return projectService.createProject(new CreateProjectRequest(
                name + "-" + UUID.randomUUID(), "test", "Test organization", null, null), ownerId.toString());
    }

    private Sample sample(Project project, String name) {
        Sample sample = new Sample();
        sample.project = project;
        sample.name = name;
        sample.persistAndFlush();
        project.samples.add(sample);
        return sample;
    }

    private Assay assay(Project project, Sample sample, String name) {
        Assay assay = new Assay();
        assay.project = project;
        assay.name = name;
        assay.addSample(sample);
        assay.persistAndFlush();
        project.assays.add(assay);
        return assay;
    }

    private FileUpload manifest(String content) throws IOException {
        Path path = temporaryDirectory.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(path, content);
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(path);
        return upload;
    }

    private UploadBatch registerAndOpenBatch(
            Project project, UUID uploaderId, Sample sample, Assay assay, List<String> paths) throws IOException {
        StringBuilder csv = new StringBuilder("relativePath,sampleName,assayId,sizeBytes,sha256\n");
        for (String path : paths) {
            csv.append(path).append(',').append(sample.name).append(',').append(assay.id)
                    .append(",100,").append(CHECKSUM).append('\n');
        }
        UploadBatchRegistrationResult result = registrationService.register(
                project.id, uploaderId, manifest(csv.toString()), SSH_PUBLIC_KEY);
        assertTrue(result.accepted());
        assertTrue(provisioningStateService.markOpen(result.batchId()));
        return (UploadBatch) UploadBatch.find("id", result.batchId()).firstResult();
    }

    private SftpGoHookEvent preUpload(UploadBatch batch, String path) {
        return new SftpGoHookEvent("pre-upload", batch.sftpUsername, path, 0, 0);
    }

    private UploadBatchStagingBudget stagingBudget() {
        return (UploadBatchStagingBudget) UploadBatchStagingBudget.findById((short) 1);
    }
}