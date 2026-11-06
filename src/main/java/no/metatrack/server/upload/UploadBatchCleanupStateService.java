package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import no.metatrack.server.file.File;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchCleanupStateService {
    @Transactional
    public List<UploadBatchRevocationClaim> revocationCandidates(int limit) {
        Instant now = Instant.now();
        List<UploadBatchStatus> terminalStatuses = List.of(
                UploadBatchStatus.SUCCEEDED,
                UploadBatchStatus.PARTIAL_SUCCESS,
                UploadBatchStatus.FAILED,
                UploadBatchStatus.CANCELLED,
                UploadBatchStatus.EXPIRED);
        PanacheQuery<UploadBatch> query = UploadBatch.find(
                "status in ?1 and sftpUserRevokedAt is null "
                        + "and (sftpUserRevocationNextAttemptAt is null or sftpUserRevocationNextAttemptAt <= ?2) "
                        + "order by createdAt",
                terminalStatuses, now);
        return query.page(Page.ofSize(limit)).list().stream()
                .map(batch -> new UploadBatchRevocationClaim(
                        batch.id, batch.sftpUsername, batch.sftpUserRevocationAttempts))
                .toList();
    }

    @Transactional
    public void markUserRevoked(UUID batchId) {
        UploadBatch batch = lockBatch(batchId);
        if (batch == null || batch.sftpUserRevokedAt != null) return;
        batch.sftpUserRevokedAt = Instant.now();
        batch.sftpUserRevocationNextAttemptAt = null;
        batch.sftpUserRevocationLastError = null;
        batch.updatedAt = Instant.now();
    }

    @Transactional
    public void deferUserRevocation(UUID batchId) {
        UploadBatch batch = lockBatch(batchId);
        if (batch == null || batch.sftpUserRevokedAt != null) return;
        batch.sftpUserRevocationNextAttemptAt = Instant.now().plusSeconds(30);
    }

    @Transactional
    public void recordUserRevocationFailure(UUID batchId, String error) {
        UploadBatch batch = lockBatch(batchId);
        if (batch == null || batch.sftpUserRevokedAt != null) return;
        batch.sftpUserRevocationAttempts++;
        long backoffSeconds = Math.min(3600, 15L << Math.min(batch.sftpUserRevocationAttempts, 8));
        batch.sftpUserRevocationNextAttemptAt = Instant.now().plusSeconds(backoffSeconds);
        batch.sftpUserRevocationLastError = safeError(error);
        batch.updatedAt = Instant.now();
    }

    @Transactional
    public List<UploadBatchCleanupClaim> cleanupCandidates(int limit) {
        Instant now = Instant.now();
        List<UploadBatchEntryStatus> cleanupStatuses = List.of(
                UploadBatchEntryStatus.COMMITTED, UploadBatchEntryStatus.FAILED);
        PanacheQuery<UploadBatchEntry> query = UploadBatchEntry.find(
                "status in ?1 and ((stagingCleanupCompletedAt is null and stagingCleanupAfter is not null "
                        + "and stagingCleanupAfter <= ?2) or (status = ?4 and destinationCleanupCompletedAt is null "
                        + "and stagingCleanupAfter is not null and stagingCleanupAfter <= ?2) "
                        + "or (status = ?3 and previousObjectKey is not null "
                        + "and previousObjectCleanupCompletedAt is null and previousObjectCleanupAfter is not null "
                        + "and previousObjectCleanupAfter <= ?2)) order by stagingCleanupAfter",
                cleanupStatuses, now, UploadBatchEntryStatus.COMMITTED, UploadBatchEntryStatus.FAILED);
        return query.page(Page.ofSize(limit)).list().stream()
                .map(entry -> new UploadBatchCleanupClaim(
                        entry.id,
                        entry.batch.id,
                        entry.batch.sftpUsername,
                        entry.stagingKey,
                        entry.destinationObjectKey,
                        entry.previousObjectKey,
                        entry.multipartUploadId,
                        entry.batch.status,
                        entry.status,
                        entry.stagingCleanupCompletedAt == null
                                && entry.stagingCleanupAfter != null
                                && !entry.stagingCleanupAfter.isAfter(now),
                        entry.status == UploadBatchEntryStatus.FAILED
                                && entry.destinationCleanupCompletedAt == null
                                && entry.stagingCleanupAfter != null
                                && !entry.stagingCleanupAfter.isAfter(now),
                        entry.status == UploadBatchEntryStatus.COMMITTED
                                && entry.previousObjectKey != null
                                && entry.previousObjectCleanupCompletedAt == null
                                && entry.previousObjectCleanupAfter != null
                                && !entry.previousObjectCleanupAfter.isAfter(now)))
                .toList();
    }

    @Transactional
    public boolean canDeleteUnpublishedDestination(UUID entryId) {
        UploadBatchEntry entry = (UploadBatchEntry) UploadBatchEntry.findByIdOptional(entryId).orElse(null);
        if (entry == null || entry.status == UploadBatchEntryStatus.COMMITTED) return false;
        Optional<File> file = File.findByObjectKeyOptional(entry.destinationObjectKey);
        return file.isEmpty()
                || (file.get().status() == no.metatrack.server.file.UploadStatus.PENDING
                && file.get().uploadBatchEntry() == null
                && file.get().uploadedBy() == null);
    }

    @Transactional
    public boolean canSkipDestinationCleanup(UUID entryId) {
        UploadBatchEntry entry = (UploadBatchEntry) UploadBatchEntry.findByIdOptional(entryId).orElse(null);
        if (entry == null) return true;
        Optional<File> file = File.findByObjectKeyOptional(entry.destinationObjectKey);
        return file.isPresent()
                && (file.get().status() == no.metatrack.server.file.UploadStatus.UPLOADED
                || (file.get().status() == no.metatrack.server.file.UploadStatus.PENDING
                && file.get().uploadedBy() != null));
    }

    @Transactional
    public boolean isObjectKeyUnreferenced(String objectKey) {
        return File.findByObjectKeyOptional(objectKey).isEmpty();
    }

    @Transactional
    public void markStagingCleanupCompleted(UUID entryId) {
        UploadBatchStagingBudget budget = lockBudget();
        UploadBatchEntry entry = lockEntry(entryId);
        if (entry == null || entry.stagingCleanupCompletedAt != null) return;
        Instant now = Instant.now();
        entry.stagingCleanupCompletedAt = now;
        budget.reservedBytes = Math.max(0, budget.reservedBytes - entry.expectedSizeBytes);
        entry.batch.reservedBytes = Math.max(0, entry.batch.reservedBytes - entry.expectedSizeBytes);
        entry.updatedAt = now;
        if (entry.batch.entries.stream().allMatch(item -> item.stagingCleanupCompletedAt != null)) {
            entry.batch.stagingCleanupCompletedAt = now;
            entry.batch.updatedAt = now;
        }
    }

    @Transactional
    public void markPreviousObjectCleanupCompleted(UUID entryId) {
        UploadBatchEntry entry = lockEntry(entryId);
        if (entry == null || entry.previousObjectCleanupCompletedAt != null) return;
        entry.previousObjectCleanupCompletedAt = Instant.now();
        entry.updatedAt = Instant.now();
    }

    @Transactional
    public void markDestinationCleanupCompleted(UUID entryId) {
        UploadBatchEntry entry = lockEntry(entryId);
        if (entry == null || entry.destinationCleanupCompletedAt != null) return;
        entry.destinationCleanupCompletedAt = Instant.now();
        entry.updatedAt = Instant.now();
    }

    private UploadBatch lockBatch(UUID batchId) {
        return UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
    }

    private UploadBatchEntry lockEntry(UUID entryId) {
        return UploadBatchEntry.find("id", entryId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
    }

    private UploadBatchStagingBudget lockBudget() {
        UploadBatchStagingBudget budget = (UploadBatchStagingBudget) UploadBatchStagingBudget
                .find("id", (short) 1).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (budget == null) throw new IllegalStateException("SFTP staging budget is not initialized");
        return budget;
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) return "SFTPGo account revocation failed";
        String safe = error.replaceAll("[\\r\\n\\t]", " ").trim();
        return safe.length() > 1000 ? safe.substring(0, 1000) : safe;
    }
}