package no.metatrack.server.upload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.ProjectAuthorizationService;
import no.metatrack.server.project.ProjectRole;

import java.util.UUID;

@ApplicationScoped
public class UploadBatchQueryService {
    @Inject
    ProjectAuthorizationService authorizationService;

    @Inject
    SftpGoConfig sftpGoConfig;

    @Transactional
    public UploadBatchReport getReport(Long projectId, UUID batchId, UUID requesterId) {
        UploadBatch batch = (UploadBatch) UploadBatch.find(
                "id = ?1 and requestedProject.id = ?2", batchId, projectId).firstResult();
        if (batch == null) throw new NotFoundException("Upload batch not found");
        if (!requesterId.equals(batch.uploaderId)
                && !authorizationService.hasAtLeast(requesterId, batch.requestedProject.id, ProjectRole.ADMIN)
                && !authorizationService.hasAtLeast(requesterId, batch.owningProject.id, ProjectRole.ADMIN)) {
            throw new ForbiddenException("Upload batch access denied");
        }
        var entries = batch.entries.stream()
                .sorted(java.util.Comparator.comparingInt(entry -> entry.manifestRow))
                .map(entry -> new UploadBatchEntryReport(
                        entry.manifestRow,
                        entry.relativePath,
                        entry.sample.name,
                        entry.sample.id,
                        entry.assay.id,
                        entry.expectedSizeBytes,
                        entry.readRole,
                        entry.status,
                        entry.bytesTransferred,
                        entry.attempts,
                        entry.lastError))
                .toList();
        return new UploadBatchReport(
                batch.id,
                batch.requestedProject.id,
                batch.owningProject.id,
                batch.uploaderId,
                batch.status,
                batch.sftpUsername,
                sftpGoConfig.sftpHost().orElse(""),
                sftpGoConfig.sftpPort(),
                batch.expiresAt,
                batch.reservedBytes,
                batch.lastError,
                entries);
    }
}