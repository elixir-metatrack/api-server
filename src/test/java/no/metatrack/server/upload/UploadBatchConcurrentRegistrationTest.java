package no.metatrack.server.upload;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.SubProjectDatabase;
import no.metatrack.server.project.CreateProjectRequest;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectService;
import no.metatrack.server.sample.Sample;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(UploadBatchRegistrationTestProfile.class)
class UploadBatchConcurrentRegistrationTest {
    private static final String CHECKSUM = "c".repeat(64);
    private static final String SSH_PUBLIC_KEY = "ssh-ed25519 YWJjZGVmZ2hpamtsbW5vcA== batch-test";

    @Inject
    UserTransaction userTransaction;

    @Inject
    ProjectService projectService;

    @Inject
    UploadBatchRegistrationService registrationService;

    @Inject
    UploadBatchProvisioningStateService provisioningStateService;

    @Inject
    UploadBatchLifecycleService lifecycleService;

    @Inject
    EntityManager entityManager;

    @Inject
    UploadBatchAdmissionService admissionService;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void resetDisposableBatchState() throws Exception {
        userTransaction.begin();
        entityManager.createNativeQuery("DELETE FROM file WHERE upload_batch_entry_id IS NOT NULL").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM upload_batch").executeUpdate();
        entityManager.createNativeQuery("UPDATE upload_batch_staging_budget SET reserved_bytes = 0 WHERE id = 1")
                .executeUpdate();
        userTransaction.commit();
    }

    @AfterEach
    void clearDisposableBatchState() throws Exception {
        userTransaction.begin();
        entityManager.createNativeQuery("DELETE FROM file WHERE upload_batch_entry_id IS NOT NULL").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM upload_batch").executeUpdate();
        entityManager.createNativeQuery("UPDATE upload_batch_staging_budget SET reserved_bytes = 0 WHERE id = 1")
                .executeUpdate();
        userTransaction.commit();
    }

    @Test
    void serializesConcurrentStagingBudgetReservations() throws Exception {
        UUID uploaderId = UUID.randomUUID();
        userTransaction.begin();
        Project project = projectService.createProject(new CreateProjectRequest(
                "concurrent-batch-" + UUID.randomUUID(), "test", "Test organization", null, null), uploaderId.toString());
        Sample sample = new Sample();
        sample.project = project;
        sample.name = "sample-a";
        sample.persistAndFlush();
        project.samples.add(sample);
        Assay assay = new Assay();
        assay.project = project;
        assay.name = "assay-a";
        assay.addSample(sample);
        assay.persistAndFlush();
        project.assays.add(assay);
        long batchesBefore = UploadBatch.count();
        UploadBatchStagingBudget budgetBefore = (UploadBatchStagingBudget)
                UploadBatchStagingBudget.findById((short) 1);
        budgetBefore.reservedBytes = 999_999_400L;
        entityManager.flush();
        long reservedBytesBefore = budgetBefore.reservedBytes;
        userTransaction.commit();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> register(project.id, uploaderId, assay.id, "R1.fastq", ready, start));
            var second = executor.submit(() -> register(project.id, uploaderId, assay.id, "R2.fastq", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Boolean> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            assertEquals(1, results.stream().filter(result -> !result).count());
        }

        userTransaction.begin();
        assertEquals(batchesBefore + 1, UploadBatch.count());
        UploadBatchStagingBudget budget = (UploadBatchStagingBudget) UploadBatchStagingBudget.findById((short) 1);
        assertEquals(reservedBytesBefore + 600, budget.reservedBytes);
        userTransaction.commit();
    }

    @Test
    void concurrentFinalizeRequestsAreIdempotent() throws Exception {
        UUID uploaderId = UUID.randomUUID();
        userTransaction.begin();
        Project project = projectService.createProject(new CreateProjectRequest(
                "finalize-race-" + UUID.randomUUID(), "test", "Test organization", null, null), uploaderId.toString());
        Sample sample = new Sample();
        sample.project = project;
        sample.name = "sample-a";
        sample.persistAndFlush();
        project.samples.add(sample);
        Assay assay = new Assay();
        assay.project = project;
        assay.name = "assay-a";
        assay.addSample(sample);
        assay.persistAndFlush();
        project.assays.add(assay);
        userTransaction.commit();

        Path manifestPath = temporaryDirectory.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(manifestPath, "relativePath,sampleName,assayId,sizeBytes,sha256\n"
                + "reads/R1.fastq,sample-a," + assay.id + ",100," + CHECKSUM + "\n");
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(manifestPath);
        UploadBatchRegistrationResult registration = registrationService.register(
                project.id, uploaderId, upload, SSH_PUBLIC_KEY);
        assertTrue(registration.accepted());
        assertTrue(provisioningStateService.markOpen(registration.batchId()));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> finalizeBatch(project.id, registration.batchId(), uploaderId, ready, start));
            var second = executor.submit(() -> finalizeBatch(project.id, registration.batchId(), uploaderId, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(UploadBatchStatus.DRAINING, first.get(30, TimeUnit.SECONDS));
            assertEquals(UploadBatchStatus.DRAINING, second.get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentCancellationAndUploadAdmissionCannotReopenWrites() throws Exception {
        UUID uploaderId = UUID.randomUUID();
        userTransaction.begin();
        Project project = projectService.createProject(new CreateProjectRequest(
                "cancel-race-" + UUID.randomUUID(), "test", "Test organization", null, null), uploaderId.toString());
        Sample sample = new Sample();
        sample.project = project;
        sample.name = "sample-a";
        sample.persistAndFlush();
        project.samples.add(sample);
        Assay assay = new Assay();
        assay.project = project;
        assay.name = "assay-a";
        assay.addSample(sample);
        assay.persistAndFlush();
        project.assays.add(assay);
        userTransaction.commit();

        Path manifestPath = temporaryDirectory.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(manifestPath, "relativePath,sampleName,assayId,sizeBytes,sha256\n"
                + "reads/R1.fastq,sample-a," + assay.id + ",100," + CHECKSUM + "\n");
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(manifestPath);
        UploadBatchRegistrationResult registration = registrationService.register(
                project.id, uploaderId, upload, SSH_PUBLIC_KEY);
        assertTrue(registration.accepted());
        assertTrue(provisioningStateService.markOpen(registration.batchId()));
        String sftpUsername = ((UploadBatch) UploadBatch.find("id", registration.batchId()).firstResult()).sftpUsername;
        SftpGoHookEvent event = new SftpGoHookEvent("pre-upload", sftpUsername, "reads/R1.fastq", 0, 0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var admission = executor.submit(() -> {
                ready.countDown();
                start.await();
                return admissionService.admit(event, List.of());
            });
            var cancellation = executor.submit(() -> {
                ready.countDown();
                start.await();
                return lifecycleService.cancelBatch(project.id, registration.batchId(), uploaderId);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            admission.get(30, TimeUnit.SECONDS);
            assertEquals(UploadBatchStatus.CANCELLED, cancellation.get(30, TimeUnit.SECONDS));
        }

        assertFalse(admissionService.admit(event, List.of()));
    }

    private UploadBatchStatus finalizeBatch(
            Long projectId, UUID batchId, UUID uploaderId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        return lifecycleService.finalizeBatch(projectId, batchId, uploaderId);
    }

    private boolean register(
            Long projectId, UUID uploaderId, UUID assayId, String fileName,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test start barrier timed out");
        Path path = temporaryDirectory.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(path, "relativePath,sampleName,assayId,sizeBytes,sha256\n"
                + "reads/" + fileName + ",sample-a," + assayId + ",600," + CHECKSUM + "\n");
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(path);
        try {
            return registrationService.register(projectId, uploaderId, upload, SSH_PUBLIC_KEY).accepted();
        } catch (WebApplicationException exception) {
            if (exception.getResponse().getStatus() == 507) return false;
            throw exception;
        }
    }
}