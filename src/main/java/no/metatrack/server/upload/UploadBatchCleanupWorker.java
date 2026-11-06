package no.metatrack.server.upload;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import no.metatrack.server.file.ObjectStorage;
import no.metatrack.server.file.StorageMultipartUpload;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class UploadBatchCleanupWorker {
    private static final Logger LOG = Logger.getLogger(UploadBatchCleanupWorker.class);
    private static final int PAGE_SIZE = 50;

    @Inject
    UploadBatchCleanupStateService cleanupStateService;

    @Inject
    SftpGoManagementService sftpGoManagementService;

    @Inject
    UploadBatchAdmissionService admissionService;

    @Inject
    ObjectStorage objectStorage;

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanTerminalBatches() {
        List<UploadBatchRevocationClaim> revocations = cleanupStateService.revocationCandidates(PAGE_SIZE);
        List<UploadBatchCleanupClaim> cleanupCandidates = cleanupStateService.cleanupCandidates(PAGE_SIZE);
        if (revocations.isEmpty() && cleanupCandidates.isEmpty()) return;

        List<SftpGoConnection> connections;
        try {
            connections = sftpGoManagementService.activeConnections();
        } catch (RuntimeException exception) {
            LOG.warn("Unable to inspect SFTPGo transfers during batch cleanup", exception);
            return;
        }

        revokeAccounts(revocations, connections);
        for (UploadBatchCleanupClaim claim : cleanupCandidates) {
            if (hasActiveTransfers(connections, claim.sftpUsername())) continue;
            try {
                if (claim.batchStatus() != UploadBatchStatus.PROCESSING
                        && !admissionService.clearTerminalAdmissions(claim.batchId(), connections)) continue;
                cleanupEntry(claim);
            } catch (RuntimeException exception) {
                LOG.warnf("Batch storage cleanup will retry entry %s: %s",
                        claim.entryId(), exception.getClass().getSimpleName());
            }
        }
    }

    private void revokeAccounts(List<UploadBatchRevocationClaim> claims, List<SftpGoConnection> connections) {
        for (UploadBatchRevocationClaim claim : claims) {
            if (hasActiveTransfers(connections, claim.username())) {
                cleanupStateService.deferUserRevocation(claim.batchId());
                continue;
            }
            if (!admissionService.clearTerminalAdmissions(claim.batchId(), connections)) {
                cleanupStateService.deferUserRevocation(claim.batchId());
                continue;
            }
            try {
                sftpGoManagementService.deleteUser(claim.username());
                cleanupStateService.markUserRevoked(claim.batchId());
            } catch (RuntimeException exception) {
                cleanupStateService.recordUserRevocationFailure(claim.batchId(),
                        "SFTPGo account revocation failed");
                LOG.warnf("SFTPGo account revocation will retry for batch %s", claim.batchId());
            }
        }
    }

    private void cleanupEntry(UploadBatchCleanupClaim claim) {
        if (claim.multipartUploadId() != null) {
            objectStorage.abortMultipartCopy(claim.destinationObjectKey(), claim.multipartUploadId());
        }
        String stagingPrefix = "staging/" + claim.batchId() + "/";
        for (StorageMultipartUpload upload : objectStorage.listMultipartUploads(stagingPrefix)) {
            if (upload.objectKey() != null && upload.objectKey().startsWith(stagingPrefix)) {
                objectStorage.abortMultipartCopy(upload.objectKey(), upload.uploadId());
            }
        }

        if (claim.stagingDue()) {
            objectStorage.purgeAllVersions(claim.stagingKey());
            if (claim.entryStatus() != UploadBatchEntryStatus.COMMITTED) {
                cleanupUnpublishedDestination(claim);
            }
            cleanupStateService.markStagingCleanupCompleted(claim.entryId());
        } else if (claim.destinationCleanupDue()) {
            cleanupUnpublishedDestination(claim);
        }

        if (claim.previousObjectDue() && claim.previousObjectKey() != null
                && cleanupStateService.isObjectKeyUnreferenced(claim.previousObjectKey())) {
            objectStorage.purgeAllVersions(claim.previousObjectKey());
            cleanupStateService.markPreviousObjectCleanupCompleted(claim.entryId());
        }
    }

    private void cleanupUnpublishedDestination(UploadBatchCleanupClaim claim) {
        if (cleanupStateService.canDeleteUnpublishedDestination(claim.entryId())) {
            objectStorage.purgeAllVersions(claim.destinationObjectKey());
            cleanupStateService.markDestinationCleanupCompleted(claim.entryId());
        } else if (cleanupStateService.canSkipDestinationCleanup(claim.entryId())) {
            cleanupStateService.markDestinationCleanupCompleted(claim.entryId());
        }
    }

    private boolean hasActiveTransfers(List<SftpGoConnection> connections, String username) {
        return connections.stream()
                .filter(connection -> username.equals(connection.username()))
                .anyMatch(connection -> connection.activeTransfers() != null && !connection.activeTransfers().isEmpty());
    }
}