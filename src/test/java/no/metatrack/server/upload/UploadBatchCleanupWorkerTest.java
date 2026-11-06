package no.metatrack.server.upload;

import no.metatrack.server.file.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadBatchCleanupWorkerTest {
    private final UUID batchId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private UploadBatchCleanupStateService stateService;
    private SftpGoManagementService sftpGoManagementService;
    private UploadBatchAdmissionService admissionService;
    private ObjectStorage objectStorage;
    private UploadBatchCleanupWorker cleanupWorker;

    @BeforeEach
    void setUp() {
        stateService = mock(UploadBatchCleanupStateService.class);
        sftpGoManagementService = mock(SftpGoManagementService.class);
        admissionService = mock(UploadBatchAdmissionService.class);
        objectStorage = mock(ObjectStorage.class);
        cleanupWorker = new UploadBatchCleanupWorker();
        cleanupWorker.cleanupStateService = stateService;
        cleanupWorker.sftpGoManagementService = sftpGoManagementService;
        cleanupWorker.admissionService = admissionService;
        cleanupWorker.objectStorage = objectStorage;
        when(sftpGoManagementService.activeConnections()).thenReturn(List.of());
        when(objectStorage.listMultipartUploads("staging/" + batchId + "/")).thenReturn(List.of());
        when(admissionService.clearTerminalAdmissions(org.mockito.ArgumentMatchers.eq(batchId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
    }

    @Test
    void releasesBudgetOnlyAfterVersionAwareStagingCleanupSucceeds() {
        when(stateService.revocationCandidates(50)).thenReturn(List.of());
        when(stateService.cleanupCandidates(50)).thenReturn(List.of(
                new UploadBatchCleanupClaim(entryId, batchId, "mtb-user", "staging/" + batchId + "/file.fastq",
                        "final/file-key", null, null, UploadBatchStatus.SUCCEEDED,
                        UploadBatchEntryStatus.COMMITTED, true, false, false)));

        cleanupWorker.cleanTerminalBatches();

        InOrder order = inOrder(objectStorage, stateService);
        order.verify(objectStorage).purgeAllVersions("staging/" + batchId + "/file.fastq");
        order.verify(stateService).markStagingCleanupCompleted(entryId);
        verify(objectStorage, never()).purgeAllVersions("final/file-key");
    }

    @Test
    void retainsReservationWhenStagingDeletionFails() {
        when(stateService.revocationCandidates(50)).thenReturn(List.of());
        when(stateService.cleanupCandidates(50)).thenReturn(List.of(
                new UploadBatchCleanupClaim(entryId, batchId, "mtb-user", "staging/" + batchId + "/file.fastq",
                        "final/file-key", null, null, UploadBatchStatus.FAILED,
                        UploadBatchEntryStatus.FAILED, true, true, false)));
        doThrow(new IllegalStateException("S3 unavailable"))
                .when(objectStorage).purgeAllVersions("staging/" + batchId + "/file.fastq");

        cleanupWorker.cleanTerminalBatches();

        verify(stateService, never()).markStagingCleanupCompleted(entryId);
        verify(stateService, never()).markDestinationCleanupCompleted(entryId);
    }

    @Test
    void retriesDestinationCleanupWithoutPurgingStagingAgain() {
        when(stateService.cleanupCandidates(50)).thenReturn(List.of(
                new UploadBatchCleanupClaim(entryId, batchId, "mtb-user", "staging/" + batchId + "/file.fastq",
                        "final/file-key", null, null, UploadBatchStatus.FAILED,
                        UploadBatchEntryStatus.FAILED, false, true, false)));
        when(stateService.canDeleteUnpublishedDestination(entryId)).thenReturn(true);

        cleanupWorker.cleanTerminalBatches();

        InOrder order = inOrder(objectStorage, stateService);
        order.verify(objectStorage).purgeAllVersions("final/file-key");
        order.verify(stateService).markDestinationCleanupCompleted(entryId);
        verify(objectStorage, never()).purgeAllVersions("staging/" + batchId + "/file.fastq");
        verify(stateService, never()).markStagingCleanupCompleted(entryId);
    }

    @Test
    void failsClosedWhenTransferInspectionIsUnavailable() {
        when(stateService.cleanupCandidates(50)).thenReturn(List.of(
                new UploadBatchCleanupClaim(entryId, batchId, "mtb-user", "staging/" + batchId + "/file.fastq",
                        "final/file-key", null, null, UploadBatchStatus.FAILED,
                        UploadBatchEntryStatus.FAILED, true, true, false)));
        when(sftpGoManagementService.activeConnections()).thenThrow(new IllegalStateException("Unavailable"));

        cleanupWorker.cleanTerminalBatches();

        verify(objectStorage, never()).purgeAllVersions("staging/" + batchId + "/file.fastq");
        verify(stateService, never()).markStagingCleanupCompleted(entryId);
    }

    @Test
    void retainsStagingWhileAnAdmissionIsNotYetVisibleAsATransfer() {
        when(stateService.cleanupCandidates(50)).thenReturn(List.of(
                new UploadBatchCleanupClaim(entryId, batchId, "mtb-user", "staging/" + batchId + "/file.fastq",
                        "final/file-key", null, null, UploadBatchStatus.CANCELLED,
                        UploadBatchEntryStatus.FAILED, true, true, false)));
        when(admissionService.clearTerminalAdmissions(batchId, List.of())).thenReturn(false);

        cleanupWorker.cleanTerminalBatches();

        verify(objectStorage, never()).purgeAllVersions("staging/" + batchId + "/file.fastq");
        verify(stateService, never()).markStagingCleanupCompleted(entryId);
    }

    @Test
    void defersAccountRevocationWhileSftpGoReportsAnActiveTransfer() {
        when(stateService.revocationCandidates(50)).thenReturn(List.of(
                new UploadBatchRevocationClaim(batchId, "mtb-user", 0)));
        when(stateService.cleanupCandidates(50)).thenReturn(List.of());
        when(sftpGoManagementService.activeConnections()).thenReturn(List.of(
                new SftpGoConnection("mtb-user", "connection-id",
                        List.of(new SftpGoTransfer("upload", "/reads.fastq", 10)))));

        cleanupWorker.cleanTerminalBatches();

        verify(stateService).deferUserRevocation(batchId);
        verify(sftpGoManagementService, never()).deleteUser("mtb-user");
    }

    @Test
    void revokesTerminalBatchAccountAfterAdmissionAndTransferDrain() {
        when(stateService.revocationCandidates(50)).thenReturn(List.of(
                new UploadBatchRevocationClaim(batchId, "mtb-user", 0)));
        when(stateService.cleanupCandidates(50)).thenReturn(List.of());

        cleanupWorker.cleanTerminalBatches();

        verify(admissionService).clearTerminalAdmissions(batchId, List.of());
        verify(sftpGoManagementService).deleteUser("mtb-user");
        verify(stateService).markUserRevoked(batchId);
    }
}