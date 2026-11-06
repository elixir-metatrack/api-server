package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchProvisioningStateService {
    private static final long PROVISIONING_LEASE_SECONDS = 60;

    @Inject
    UploadBatchConfig config;

    @Transactional
    public Optional<UploadBatchProvisioningClaim> claim(UUID batchId) {
        UploadBatch batch = UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null || batch.status != UploadBatchStatus.PROVISIONING) return Optional.empty();

        Instant now = Instant.now();
        if (!batch.expiresAt.isAfter(now)) return Optional.empty();
        if (batch.provisioningLeaseUntil != null && batch.provisioningLeaseUntil.isAfter(now)) {
            return Optional.empty();
        }

        batch.provisioningAttempts++;
        batch.provisioningLeaseUntil = now.plusSeconds(PROVISIONING_LEASE_SECONDS);
        batch.updatedAt = now;
        return Optional.of(new UploadBatchProvisioningClaim(
                batch.id, batch.sftpUsername, batch.sftpPublicKey, batch.expiresAt, batch.provisioningAttempts,
                batch.reservedBytes, batch.entries.size()));
    }

    @Transactional
    public boolean markOpen(UUID batchId) {
        UploadBatch batch = lockBatch(batchId);
        if (batch == null || batch.status != UploadBatchStatus.PROVISIONING) return false;
        Instant now = Instant.now();
        if (!batch.expiresAt.isAfter(now)) {
            batch.status = UploadBatchStatus.EXPIRED;
            batch.cleanupAfter = now.plus(config.failedStagingRetention());
            batch.provisioningLeaseUntil = null;
            batch.updatedAt = now;
            return false;
        }
        batch.status = UploadBatchStatus.OPEN;
        batch.lastError = null;
        batch.provisioningLeaseUntil = null;
        batch.updatedAt = now;
        return true;
    }

    @Transactional
    public void recordFailure(UUID batchId, String error) {
        UploadBatch batch = lockBatch(batchId);
        if (batch == null || batch.status != UploadBatchStatus.PROVISIONING) return;
        Instant now = Instant.now();
        if (!batch.expiresAt.isAfter(now)) {
            batch.status = UploadBatchStatus.EXPIRED;
            batch.cleanupAfter = now.plus(config.failedStagingRetention());
            batch.provisioningLeaseUntil = null;
        } else {
            long backoffSeconds = Math.min(300, 5L << Math.min(batch.provisioningAttempts, 6));
            batch.provisioningLeaseUntil = now.plusSeconds(backoffSeconds);
            batch.lastError = error == null || error.isBlank() ? "SFTPGo account provisioning failed" : error;
            if (batch.lastError.length() > 1000) batch.lastError = batch.lastError.substring(0, 1000);
        }
        batch.updatedAt = now;
    }

    @Transactional
    public List<UUID> provisioningBatchIds(int limit) {
        Instant now = Instant.now();
        PanacheQuery<UploadBatch> query = UploadBatch.find(
                "status = ?1 and expiresAt > ?2 and (provisioningLeaseUntil is null or provisioningLeaseUntil <= ?2) "
                        + "order by createdAt",
                UploadBatchStatus.PROVISIONING, now);
        return query.page(Page.ofSize(limit)).list().stream().map(batch -> batch.id).toList();
    }


    private UploadBatch lockBatch(UUID batchId) {
        return UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
    }
}