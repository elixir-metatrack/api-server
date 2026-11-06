package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.file.FileBatchReservationService;
import no.metatrack.server.project.ProjectAuthorizationService;
import no.metatrack.server.project.ProjectRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchLifecycleService {
    @Inject
    ProjectAuthorizationService authorizationService;

    @Inject
    UploadBatchConfig config;

    @Inject
    FileBatchReservationService fileReservationService;

    @Transactional
    public UploadBatchStatus finalizeBatch(Long projectId, UUID batchId, UUID requesterId) {
        lockStagingBudget();
        UploadBatch batch = lockBatch(projectId, batchId);
        authorizeManager(batch, requesterId);
        authorizationService.requireUploadRights(batch.uploaderId, batch.requestedProject.id);
        if (batch.status == UploadBatchStatus.PROVISIONING) {
            throw new WebApplicationException("SFTP account provisioning is not complete", 409);
        }
        if (batch.status != UploadBatchStatus.OPEN) return batch.status;

        Instant now = Instant.now();
        if (!batch.expiresAt.isAfter(now)) {
            expire(batch, now);
            return batch.status;
        }
        batch.status = UploadBatchStatus.DRAINING;
        batch.frozenAt = now;
        batch.updatedAt = now;
        return batch.status;
    }

    @Transactional
    public UploadBatchStatus cancelBatch(Long projectId, UUID batchId, UUID requesterId) {
        lockStagingBudget();
        UploadBatch batch = lockBatch(projectId, batchId);
        authorizeManager(batch, requesterId);
        authorizationService.requireUploadRights(batch.uploaderId, batch.requestedProject.id);
        if (isTerminal(batch.status)) return batch.status;

        Instant now = Instant.now();
        batch.status = UploadBatchStatus.CANCELLED;
        batch.frozenAt = now;
        batch.cleanupAfter = now.plus(config.failedStagingRetention());
        batch.updatedAt = now;
        batch.sftpUserRevocationNextAttemptAt = now;
        for (UploadBatchEntry entry : batch.entries) {
            entry.processingLeaseUntil = null;
            entry.processingLeaseToken = null;
            if (entry.status != UploadBatchEntryStatus.COMMITTED) {
                entry.status = UploadBatchEntryStatus.FAILED;
                entry.lastError = "Batch cancelled";
                entry.stagingCleanupAfter = batch.cleanupAfter;
                fileReservationService.release(entry);
            } else {
                entry.stagingCleanupAfter = now;
            }
            entry.updatedAt = now;
        }
        return batch.status;
    }

    @Transactional
    public List<UUID> drainingBatchIds(int limit) {
        PanacheQuery<UploadBatch> query = UploadBatch.find(
                "status = ?1 order by frozenAt", UploadBatchStatus.DRAINING);
        return query.page(Page.ofSize(limit)).list().stream().map(batch -> batch.id).toList();
    }

    @Transactional
    public List<UUID> expiredOpenBatchIds(int limit) {
        Instant now = Instant.now();
        List<UploadBatchStatus> expirable = List.of(UploadBatchStatus.OPEN, UploadBatchStatus.PROVISIONING);
        PanacheQuery<UploadBatch> query = UploadBatch.find(
                "status in ?1 and expiresAt <= ?2 order by expiresAt", expirable, now);
        return query.page(Page.ofSize(limit)).list().stream().map(batch -> batch.id).toList();
    }

    @Transactional
    public void expireIfOpen(UUID batchId) {
        lockStagingBudget();
        UploadBatch batch = UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null || (batch.status != UploadBatchStatus.OPEN
                && batch.status != UploadBatchStatus.PROVISIONING)
                || batch.expiresAt.isAfter(Instant.now())) {
            return;
        }
        expire(batch, Instant.now());
    }

    private UploadBatch lockBatch(Long projectId, UUID batchId) {
        UploadBatch batch = UploadBatch.find("id = ?1 and requestedProject.id = ?2", batchId, projectId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null) throw new NotFoundException("Upload batch not found");
        return batch;
    }

    private UploadBatchStagingBudget lockStagingBudget() {
        UploadBatchStagingBudget budget = (UploadBatchStagingBudget) UploadBatchStagingBudget
                .find("id", (short) 1).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (budget == null) throw new IllegalStateException("SFTP staging budget is not initialized");
        return budget;
    }

    private void authorizeManager(UploadBatch batch, UUID requesterId) {
        boolean creator = requesterId.equals(batch.uploaderId);
        boolean projectAdmin = authorizationService.hasAtLeast(
                requesterId, batch.requestedProject.id, ProjectRole.ADMIN);
        boolean owningProjectAdmin = authorizationService.hasAtLeast(
                requesterId, batch.owningProject.id, ProjectRole.ADMIN);
        if (!creator && !projectAdmin && !owningProjectAdmin) {
            throw new ForbiddenException("Upload batch access denied");
        }
    }

    private void expire(UploadBatch batch, Instant now) {
        batch.status = UploadBatchStatus.EXPIRED;
        batch.frozenAt = now;
        batch.cleanupAfter = now.plus(config.failedStagingRetention());
        batch.provisioningLeaseUntil = null;
        batch.sftpUserRevocationNextAttemptAt = now;
        batch.updatedAt = now;
        for (UploadBatchEntry entry : batch.entries) {
            entry.processingLeaseUntil = null;
            entry.processingLeaseToken = null;
            if (entry.status != UploadBatchEntryStatus.COMMITTED) {
                entry.status = UploadBatchEntryStatus.FAILED;
                entry.lastError = "Upload window expired";
                entry.stagingCleanupAfter = batch.cleanupAfter;
                fileReservationService.release(entry);
            } else {
                entry.stagingCleanupAfter = now;
            }
            entry.updatedAt = now;
        }
    }

    private boolean isTerminal(UploadBatchStatus status) {
        return status == UploadBatchStatus.SUCCEEDED
                || status == UploadBatchStatus.PARTIAL_SUCCESS
                || status == UploadBatchStatus.FAILED
                || status == UploadBatchStatus.CANCELLED
                || status == UploadBatchStatus.EXPIRED;
    }
}