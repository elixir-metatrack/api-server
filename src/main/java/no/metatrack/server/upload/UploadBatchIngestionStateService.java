package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import no.metatrack.server.file.FileBatchReservationService;
import no.metatrack.server.file.StorageObjectIdentity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchIngestionStateService {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 8;

    @Inject
    UploadBatchConfig config;

    @Inject
    FileBatchReservationService fileReservationService;

    @Transactional
    public Optional<UploadBatchEntryClaim> claimNext() {
        lockStagingBudget();
        Instant now = Instant.now();
        List<UploadBatchEntryStatus> claimableStatuses = List.of(
                UploadBatchEntryStatus.REGISTERED,
                UploadBatchEntryStatus.VALIDATING,
                UploadBatchEntryStatus.PROMOTING);
        PanacheQuery<UploadBatchEntry> query = UploadBatchEntry.find(
                        "batch.status = ?1 and status in ?2 "
                                + "and (nextRetryAt is null or nextRetryAt <= ?3) "
                                + "and (processingLeaseUntil is null or processingLeaseUntil <= ?3) "
                                + "order by batch.createdAt, manifestRow",
                        UploadBatchStatus.PROCESSING, claimableStatuses, now)
                .withLock(LockModeType.PESSIMISTIC_WRITE);
        UploadBatchEntry entry = query.page(Page.ofSize(1)).firstResult();
        if (entry == null) return Optional.empty();
        if (entry.attempts >= MAX_ATTEMPTS) {
            markFailed(entry, "Ingestion retry limit exceeded", now);
            return Optional.empty();
        }

        entry.attempts++;
        entry.processingLeaseUntil = now.plus(LEASE_DURATION);
        entry.processingLeaseToken = UUID.randomUUID();
        entry.nextRetryAt = null;
        if (entry.status == UploadBatchEntryStatus.REGISTERED) {
            entry.status = UploadBatchEntryStatus.VALIDATING;
        }
        entry.updatedAt = now;
        return Optional.of(new UploadBatchEntryClaim(
                entry.id,
                entry.batch.id,
                entry.batch.uploaderId,
                entry.batch.requestedProject.id,
                entry.relativePath,
                entry.stagingKey,
                entry.destinationObjectKey,
                entry.expectedSizeBytes,
                entry.expectedSha256,
                entry.expectedMd5,
                entry.expectedUnencryptedMd5,
                entry.sourceEtag,
                entry.sourceVersionId,
                entry.multipartUploadId,
                entry.multipartPartSizeBytes,
                entry.attempts,
                entry.processingLeaseToken));
    }

    @Transactional
    public boolean renewLease(UUID entryId, UUID leaseToken, long bytesTransferred) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (entry == null || entry.batch.status != UploadBatchStatus.PROCESSING
                || !Objects.equals(entry.processingLeaseToken, leaseToken)
                || (entry.status != UploadBatchEntryStatus.VALIDATING
                && entry.status != UploadBatchEntryStatus.PROMOTING)) {
            return false;
        }
        Instant now = Instant.now();
        entry.processingLeaseUntil = now.plus(LEASE_DURATION);
        entry.bytesTransferred = Math.max(entry.bytesTransferred, bytesTransferred);
        entry.updatedAt = now;
        return true;
    }

    @Transactional
    public boolean recordValidatedSource(UUID entryId, UUID leaseToken, StorageObjectIdentity source) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (!isProcessing(entry, leaseToken, UploadBatchEntryStatus.VALIDATING, UploadBatchEntryStatus.PROMOTING)) {
            return false;
        }
        if (entry.sourceEtag != null && !entry.sourceEtag.equals(source.eTag())) return false;
        if (entry.sourceVersionId != null && !entry.sourceVersionId.equals(source.versionId())) return false;
        entry.sourceEtag = source.eTag();
        entry.sourceVersionId = source.versionId();
        entry.status = UploadBatchEntryStatus.PROMOTING;
        entry.updatedAt = Instant.now();
        return true;
    }

    @Transactional
    public boolean recordMultipartUpload(UUID entryId, UUID leaseToken, String uploadId, long partSizeBytes) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (!isProcessing(entry, leaseToken, UploadBatchEntryStatus.PROMOTING)) return false;
        if (entry.multipartUploadId != null && !entry.multipartUploadId.equals(uploadId)) return false;
        entry.multipartUploadId = uploadId;
        entry.multipartPartSizeBytes = partSizeBytes;
        entry.updatedAt = Instant.now();
        return true;
    }

    @Transactional
    public void clearMultipartUpload(UUID entryId, UUID leaseToken) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (!isProcessing(entry, leaseToken, UploadBatchEntryStatus.PROMOTING)) return;
        entry.multipartUploadId = null;
        entry.multipartPartSizeBytes = null;
        entry.updatedAt = Instant.now();
    }

    @Transactional
    public boolean recordDestinationIdentity(UUID entryId, UUID leaseToken, StorageObjectIdentity destination) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (!isProcessing(entry, leaseToken, UploadBatchEntryStatus.PROMOTING)) return false;
        entry.destinationEtag = destination.eTag();
        entry.destinationVersionId = destination.versionId();
        entry.promotedAt = Instant.now();
        entry.multipartUploadId = null;
        entry.multipartPartSizeBytes = null;
        entry.updatedAt = Instant.now();
        return true;
    }

    @Transactional
    public void markFailed(UUID entryId, UUID leaseToken, String error) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (!isProcessing(entry, leaseToken, UploadBatchEntryStatus.VALIDATING, UploadBatchEntryStatus.PROMOTING)) return;
        markFailed(entry, error, Instant.now());
    }

    @Transactional
    public void scheduleRetry(UUID entryId, UUID leaseToken, String error) {
        lockStagingBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (!isProcessing(entry, leaseToken, UploadBatchEntryStatus.VALIDATING, UploadBatchEntryStatus.PROMOTING)) return;
        Instant now = Instant.now();
        if (entry.attempts >= MAX_ATTEMPTS) {
            markFailed(entry, "Ingestion retry limit exceeded: " + safeError(error), now);
            return;
        }
        long backoffSeconds = Math.min(300, 5L << Math.min(entry.attempts, 6));
        entry.status = UploadBatchEntryStatus.REGISTERED;
        entry.nextRetryAt = now.plusSeconds(backoffSeconds);
        entry.processingLeaseUntil = null;
        entry.processingLeaseToken = null;
        entry.lastError = safeError(error);
        entry.updatedAt = now;
    }

    @Transactional
    public void recomputeBatchStatus(UUID batchId) {
        lockStagingBudget();
        UploadBatch batch = UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null || batch.status != UploadBatchStatus.PROCESSING) return;
        updateBatchStatus(batch, Instant.now());
    }

    private boolean isProcessing(UploadBatchEntry entry, UUID leaseToken, UploadBatchEntryStatus... allowedStatuses) {
        if (entry == null || entry.batch.status != UploadBatchStatus.PROCESSING
                || !Objects.equals(entry.processingLeaseToken, leaseToken)) return false;
        for (UploadBatchEntryStatus allowedStatus : allowedStatuses) {
            if (entry.status == allowedStatus) return true;
        }
        return false;
    }

    private void markFailed(UploadBatchEntry entry, String error, Instant now) {
        entry.status = UploadBatchEntryStatus.FAILED;
        entry.nextRetryAt = null;
        entry.processingLeaseUntil = null;
        entry.processingLeaseToken = null;
        entry.lastError = safeError(error);
        entry.stagingCleanupAfter = now.plus(config.failedStagingRetention());
        entry.updatedAt = now;
        fileReservationService.release(entry);
        if (entry.batch.status == UploadBatchStatus.PROCESSING) updateBatchStatus(entry.batch, now);
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
        batch.cleanupAfter = failed == 0 ? now : now.plus(config.failedStagingRetention());
        batch.sftpUserRevocationNextAttemptAt = now;
        batch.updatedAt = now;
    }

    private UploadBatchEntry lockEntry(UUID entryId) {
        return UploadBatchEntry.find("id", entryId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
    }

    private void lockStagingBudget() {
        UploadBatchStagingBudget.find("id", (short) 1)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) return "File ingestion failed";
        String safe = error.replaceAll("[\\r\\n\\t]", " ").trim();
        return safe.length() > 1000 ? safe.substring(0, 1000) : safe;
    }
}