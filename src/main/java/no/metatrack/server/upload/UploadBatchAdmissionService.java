package no.metatrack.server.upload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import no.metatrack.server.project.ProjectAuthorizationService;
import no.metatrack.server.project.ProjectRole;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchAdmissionService {
    private static final Duration ACTIVE_TRANSFER_VISIBILITY_GRACE = Duration.ofSeconds(3);

    private final ProjectAuthorizationService authorizationService;
    private final UploadBatchConfig config;

    public UploadBatchAdmissionService(ProjectAuthorizationService authorizationService, UploadBatchConfig config) {
        this.authorizationService = authorizationService;
        this.config = config;
    }

    @Transactional
    public boolean admit(SftpGoHookEvent event, List<SftpGoConnection> connections) {
        UploadBatchStagingBudget budget = (UploadBatchStagingBudget) UploadBatchStagingBudget
                .find("id", (short) 1).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (budget == null) {
            throw new SftpGoIntegrationException("SFTP staging budget is not initialized");
        }

        UploadBatch batch = (UploadBatch) UploadBatch.find("sftpUsername", event.username())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        Instant now = Instant.now();
        if (batch == null || batch.status != UploadBatchStatus.OPEN || !batch.expiresAt.isAfter(now)) {
            return false;
        }
        if (!authorizationService.hasAtLeast(batch.uploaderId, batch.requestedProject.id, ProjectRole.EDITOR)
                || !authorizationService.hasAtLeast(batch.uploaderId, batch.owningProject.id, ProjectRole.EDITOR)) {
            return false;
        }

        UploadBatchEntry entry = (UploadBatchEntry) UploadBatchEntry.find(
                        "batch.id = ?1 and relativePath = ?2", batch.id, event.path())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (entry == null || (entry.status != UploadBatchEntryStatus.REGISTERED
                && entry.status != UploadBatchEntryStatus.UPLOADING)) {
            return false;
        }

        Map<TransferKey, Long> activeTransfers = activeTransfers(connections);
        Set<TransferKey> activeKeys = activeTransfers.keySet();
        Set<TransferKey> reservedKeys = reconcileAdmissions(activeTransfers, now);

        TransferKey requestedTransfer = new TransferKey(batch.sftpUsername, entry.relativePath);
        if (activeKeys.contains(requestedTransfer) || reservedKeys.contains(requestedTransfer)) return false;
        Set<TransferKey> allTransfers = new HashSet<>(activeKeys);
        allTransfers.addAll(reservedKeys);
        long batchTransferCount = allTransfers.stream()
                .filter(key -> key.username().equals(batch.sftpUsername))
                .count();
        if (batchTransferCount >= config.maxTransfersPerBatch()
                || allTransfers.size() >= config.maxTotalTransfers()) {
            return false;
        }

        UploadBatchTransferAdmission admission = new UploadBatchTransferAdmission();
        admission.id = UUID.randomUUID();
        admission.entry = entry;
        admission.admittedAt = now;
        admission.persist();
        entry.status = UploadBatchEntryStatus.UPLOADING;
        entry.updatedAt = now;
        return true;
    }

    @Transactional
    public boolean advanceDrain(UUID batchId, List<SftpGoConnection> connections) {
        UploadBatchStagingBudget budget = (UploadBatchStagingBudget) UploadBatchStagingBudget
                .find("id", (short) 1).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (budget == null) {
            throw new SftpGoIntegrationException("SFTP staging budget is not initialized");
        }
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null || batch.status != UploadBatchStatus.DRAINING) return false;

        Instant now = Instant.now();
        Map<TransferKey, Long> activeTransfers = activeTransfers(connections);
        reconcileAdmissions(activeTransfers, now);
        boolean hasLiveBatchTransfer = activeTransfers.keySet().stream()
                .anyMatch(key -> key.username().equals(batch.sftpUsername));
        boolean hasBatchAdmission = UploadBatchTransferAdmission.count("entry.batch.id", batch.id) > 0;
        if (hasLiveBatchTransfer || hasBatchAdmission) return false;

        for (UploadBatchEntry entry : batch.entries) {
            if (entry.status == UploadBatchEntryStatus.UPLOADING) {
                entry.status = UploadBatchEntryStatus.REGISTERED;
                entry.updatedAt = now;
            }
        }
        batch.status = UploadBatchStatus.PROCESSING;
        batch.updatedAt = now;
        return true;
    }

    @Transactional
    public boolean clearTerminalAdmissions(UUID batchId, List<SftpGoConnection> connections) {
        UploadBatchStagingBudget budget = (UploadBatchStagingBudget) UploadBatchStagingBudget
                .find("id", (short) 1).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (budget == null) {
            throw new SftpGoIntegrationException("SFTP staging budget is not initialized");
        }
        UploadBatch batch = (UploadBatch) UploadBatch.find("id", batchId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (batch == null || !isTerminal(batch.status)) return false;
        Map<TransferKey, Long> activeTransfers = activeTransfers(connections);
        reconcileAdmissions(activeTransfers, Instant.now());
        boolean hasLiveBatchTransfer = activeTransfers.keySet().stream()
                .anyMatch(key -> key.username().equals(batch.sftpUsername));
        boolean hasBatchAdmission = UploadBatchTransferAdmission.count("entry.batch.id", batch.id) > 0;
        return !hasLiveBatchTransfer && !hasBatchAdmission;
    }

    @Transactional
    public boolean recordCompleted(SftpGoHookEvent event) {
        UploadBatch batch = (UploadBatch) UploadBatch.find("sftpUsername", event.username()).firstResult();
        if (batch == null) return true;
        UploadBatchEntry entry = (UploadBatchEntry) UploadBatchEntry.find(
                "batch.id = ?1 and relativePath = ?2", batch.id, event.path()).firstResult();
        if (entry != null) {
            entry.bytesTransferred = Math.max(entry.bytesTransferred, Math.max(0, event.sizeBytes()));
            entry.updatedAt = Instant.now();
        }
        return true;
    }

    private Set<TransferKey> reconcileAdmissions(Map<TransferKey, Long> activeTransfers, Instant now) {
        Set<TransferKey> activeKeys = activeTransfers.keySet();
        Set<TransferKey> reservedKeys = new HashSet<>();
        for (UploadBatchTransferAdmission admission : UploadBatchTransferAdmission.<UploadBatchTransferAdmission>listAll()) {
            UploadBatchEntry admittedEntry = admission.entry;
            UploadBatch admittedBatch = admittedEntry.batch;
            TransferKey key = new TransferKey(admittedBatch.sftpUsername, admittedEntry.relativePath);
            if (activeKeys.contains(key)) {
                reservedKeys.add(key);
                Long transferred = activeTransfers.get(key);
                if (transferred != null) {
                    admittedEntry.bytesTransferred = Math.max(admittedEntry.bytesTransferred, transferred);
                    admittedEntry.updatedAt = now;
                }
            } else if (admission.admittedAt.plus(ACTIVE_TRANSFER_VISIBILITY_GRACE).isAfter(now)) {
                reservedKeys.add(key);
            } else {
                if (admittedBatch.status == UploadBatchStatus.OPEN
                        && admittedEntry.status == UploadBatchEntryStatus.UPLOADING) {
                    admittedEntry.status = UploadBatchEntryStatus.REGISTERED;
                    admittedEntry.updatedAt = now;
                }
                admission.delete();
            }
        }
        return reservedKeys;
    }

    private Map<TransferKey, Long> activeTransfers(List<SftpGoConnection> connections) {
        Map<TransferKey, Long> active = new HashMap<>();
        for (SftpGoConnection connection : connections) {
            if (connection.username() == null || !connection.username().startsWith("mtb-")) continue;
            if (connection.activeTransfers() == null) continue;
            for (SftpGoTransfer transfer : connection.activeTransfers()) {
                String path = relativePath(transfer.path());
                if (path == null) continue;
                TransferKey key = new TransferKey(connection.username(), path);
                active.merge(key, Math.max(0, transfer.size()), Math::max);
            }
        }
        return active;
    }

    private String relativePath(String path) {
        if (path == null || path.isBlank()) return null;
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isBlank()) return null;
        return relative;
    }

    private boolean isTerminal(UploadBatchStatus status) {
        return status == UploadBatchStatus.SUCCEEDED
                || status == UploadBatchStatus.PARTIAL_SUCCESS
                || status == UploadBatchStatus.FAILED
                || status == UploadBatchStatus.CANCELLED
                || status == UploadBatchStatus.EXPIRED;
    }

    private record TransferKey(String username, String path) {
    }
}