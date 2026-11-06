package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.ProjectAuthorizationService;
import no.metatrack.server.upload.UploadBatch;
import no.metatrack.server.upload.UploadBatchConfig;
import no.metatrack.server.upload.UploadBatchEntry;
import no.metatrack.server.upload.UploadBatchEntryStatus;
import no.metatrack.server.upload.UploadBatchStagingBudget;
import no.metatrack.server.upload.UploadBatchStatus;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class FileIngestService {

    private static final Logger LOG = Logger.getLogger(FileIngestService.class);

    @Inject
    ObjectStorage objectStorage;

    @Inject
    ProjectAuthorizationService authorizationService;

    @Inject
    FileBatchReservationService batchReservationService;

    @Inject
    UploadBatchConfig batchConfig;

    @Transactional
    public void deleteFile(Long projectId, UUID fileUuid) {
        File file = File.<File>find("uuid = ?1 and sample.project.id = ?2 and assay.project.id = ?2", fileUuid, projectId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("File not found"));

        if (file.status != UploadStatus.UPLOADED) {
            throw new WebApplicationException("Only uploaded files can be deleted", 409);
        }
        try {
            objectStorage.purgeAllVersions(file.objectKey);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Storage cleanup failed for file %s", fileUuid);
            throw new WebApplicationException("File storage cleanup failed", 502);
        }

        file.sample.files.remove(file);
        file.delete();
        File.flush();
    }

    @Transactional
    public boolean commitBatchUpload(
            UUID entryId, UUID leaseToken, long verifiedSizeBytes,
            String verifiedSha256, StorageObjectIdentity destination) {
        UploadBatchStagingBudget.find("id", (short) 1)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        UploadBatchEntry entry = (UploadBatchEntry) UploadBatchEntry.find("id", entryId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (entry == null) return false;
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", entry.batch.id)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null || batch.status != UploadBatchStatus.PROCESSING
                || entry.status != UploadBatchEntryStatus.PROMOTING
                || !Objects.equals(entry.processingLeaseToken, leaseToken)) {
            return false;
        }

        if (verifiedSizeBytes != entry.expectedSizeBytes
                || !entry.expectedSha256.equalsIgnoreCase(verifiedSha256)
                || destination.size() != verifiedSizeBytes
                || !Objects.equals(entry.destinationEtag, destination.eTag())
                || !Objects.equals(entry.destinationVersionId, destination.versionId())) {
            return failBatchEntry(entry, "Verified destination metadata changed before file linking");
        }

        try {
            authorizationService.requireUploadRights(batch.uploaderId, batch.requestedProject.id);
        } catch (WebApplicationException exception) {
            return failBatchEntry(entry, "Uploader no longer has permission to link this file");
        }

        File file = (File) File.find("uploadBatchEntry.id", entry.id)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (file == null || file.status != UploadStatus.PENDING
                || file.uploadBatchEntry == null || !entry.id.equals(file.uploadBatchEntry.id)
                || !entry.destinationVirtualPath.equals(file.virtualPath)
                || !Objects.equals(entry.previousObjectKey == null
                        ? entry.destinationObjectKey
                        : entry.previousObjectKey, file.objectKey)
                || !entry.sample.id.equals(file.sample.id)
                || !entry.assay.id.equals(file.assay.id)
                || !Objects.equals(entry.expectedMd5, file.md5)
                || !Objects.equals(entry.expectedUnencryptedMd5, file.unencryptedMd5)
                || (file.readRole != null && file.readRole != entry.readRole)) {
            return failBatchEntry(entry, "Reserved PENDING file metadata changed before commit");
        }
        if (entry.sample.project.id.equals(batch.owningProject.id) == false
                || entry.assay.samples.stream().noneMatch(sample -> sample.id.equals(entry.sample.id))) {
            return failBatchEntry(entry, "Sample and assay relationship changed before commit");
        }
        if (batch.requestedProject.parentProject != null
                && entry.sample.linkedInSubProjects.stream()
                .noneMatch(project -> project.id.equals(batch.requestedProject.id))) {
            return failBatchEntry(entry, "Sample is no longer linked to the requested sub-project");
        }

        if (file.readRole == null) file.readRole = entry.readRole;
        file.objectKey = entry.destinationObjectKey;
        file.sizeBytes = verifiedSizeBytes;
        file.sha256 = verifiedSha256.toLowerCase(java.util.Locale.ROOT);
        file.uploadedBy = batch.uploaderId;
        file.status = UploadStatus.UPLOADED;
        entry.status = UploadBatchEntryStatus.COMMITTED;
        entry.bytesTransferred = verifiedSizeBytes;
        entry.promotedAt = Instant.now();
        entry.destinationCleanupCompletedAt = entry.promotedAt;
        entry.stagingCleanupAfter = Instant.now();
        if (entry.previousObjectKey != null) {
            entry.previousObjectCleanupAfter = Instant.now().plus(batchConfig.failedStagingRetention());
        }
        entry.processingLeaseUntil = null;
        entry.processingLeaseToken = null;
        entry.nextRetryAt = null;
        entry.lastError = null;
        entry.updatedAt = Instant.now();
        updateBatchStatus(batch, entry.updatedAt);
        return true;
    }

    private boolean failBatchEntry(UploadBatchEntry entry, String message) {
        entry.status = UploadBatchEntryStatus.FAILED;
        entry.lastError = message;
        entry.processingLeaseUntil = null;
        entry.processingLeaseToken = null;
        entry.stagingCleanupAfter = Instant.now().plus(batchConfig.failedStagingRetention());
        entry.nextRetryAt = null;
        entry.updatedAt = Instant.now();
        batchReservationService.release(entry);
        updateBatchStatus(entry.batch, entry.updatedAt);
        return false;
    }

    private void updateBatchStatus(UploadBatch batch, Instant now) {
        List<UploadBatchEntry> entries = batch.entries;
        if (entries.stream().anyMatch(entry -> entry.status != UploadBatchEntryStatus.COMMITTED
                && entry.status != UploadBatchEntryStatus.FAILED)) {
            return;
        }
        long committed = entries.stream().filter(entry -> entry.status == UploadBatchEntryStatus.COMMITTED).count();
        long failed = entries.size() - committed;
        batch.status = failed == 0
                ? UploadBatchStatus.SUCCEEDED
                : committed == 0 ? UploadBatchStatus.FAILED : UploadBatchStatus.PARTIAL_SUCCESS;
        batch.cleanupAfter = failed == 0 ? now : now.plus(batchConfig.failedStagingRetention());
        batch.sftpUserRevocationNextAttemptAt = now;
        batch.updatedAt = now;
    }
}
