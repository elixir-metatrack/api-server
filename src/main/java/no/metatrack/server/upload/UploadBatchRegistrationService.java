package no.metatrack.server.upload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.file.FileBatchReservationService;
import no.metatrack.server.file.ObjectStorage;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectAuthorizationService;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchRegistrationService {
    private static final Logger LOG = Logger.getLogger(UploadBatchRegistrationService.class);

    @Inject
    UploadManifestValidator manifestValidator;

    @Inject
    UploadBatchConfig batchConfig;

    @Inject
    SftpGoConfig sftpGoConfig;

    @Inject
    ProjectAuthorizationService authorizationService;

    @Inject
    FileBatchReservationService fileReservationService;

    @Transactional
    public UploadBatchRegistrationResult register(
            Long projectId, UUID uploaderId, FileUpload manifest, String sshPublicKey) {
        validateOperationalConfig();
        authorizationService.requireUploadRights(uploaderId, projectId);

        String normalizedPublicKey = normalizePublicKey(sshPublicKey);
        UUID batchId = UUID.randomUUID();
        UploadManifestValidation validation = manifestValidator.validate(manifest, projectId, batchId);
        List<UploadManifestIssue> issues = new ArrayList<>(validation.issues());
        if (normalizedPublicKey == null) {
            issues.add(new UploadManifestIssue(0, "sshPublicKey", "A valid SSH public key is required"));
        }
        if (!issues.isEmpty()) {
            return new UploadBatchRegistrationResult(null, issues);
        }

        long declaredBytes = 0;
        try {
            for (ValidatedUploadManifestEntry entry : validation.entries()) {
                declaredBytes = Math.addExact(declaredBytes, entry.expectedSizeBytes());
            }
        } catch (ArithmeticException exception) {
            return invalidSizeResult();
        }

        if (batchConfig.stagingBudgetBytes() <= 0) {
            throw new ServiceUnavailableException("SFTP staging budget is not configured");
        }
        UploadBatchStagingBudget budget = UploadBatchStagingBudget.findById(
                (short) 1, LockModeType.PESSIMISTIC_WRITE);
        if (budget == null) {
            throw new ServiceUnavailableException("SFTP staging budget is not initialized");
        }
        if (declaredBytes > batchConfig.stagingBudgetBytes() - budget.reservedBytes) {
            throw new WebApplicationException("Declared files exceed the available S3 staging budget", 507);
        }

        Project requestedProject = Project.<Project>findByIdOptional(projectId)
                .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("Project not found"));
        Project owningProject = requestedProject.parentProject == null
                ? requestedProject
                : requestedProject.parentProject;
        Instant now = Instant.now();

        UploadBatch batch = new UploadBatch();
        batch.id = batchId;
        batch.requestedProject = requestedProject;
        batch.owningProject = owningProject;
        batch.uploaderId = uploaderId;
        batch.sftpUsername = "mtb-" + batchId.toString().replace("-", "");
        batch.sftpPublicKey = normalizedPublicKey;
        batch.status = UploadBatchStatus.PROVISIONING;
        batch.expiresAt = now.plus(batchConfig.uploadWindow());
        batch.reservedBytes = declaredBytes;
        batch.provisioningAttempts = 0;
        batch.createdAt = now;
        batch.updatedAt = now;
        batch.persist();

        for (ValidatedUploadManifestEntry validatedEntry : validation.entries()) {
            UploadBatchEntry entry = new UploadBatchEntry();
            entry.id = UUID.randomUUID();
            entry.batch = batch;
            entry.manifestRow = validatedEntry.row();
            entry.relativePath = validatedEntry.relativePath();
            entry.sample = validatedEntry.sample();
            entry.assay = validatedEntry.assay();
            entry.fileName = validatedEntry.fileName();
            entry.stagingKey = "staging/" + batchId + "/" + validatedEntry.relativePath();
            entry.destinationVirtualPath = validatedEntry.destinationVirtualPath();
            entry.destinationObjectKey = validatedEntry.destinationObjectKey();
            entry.previousObjectKey = validatedEntry.previousObjectKey();
            entry.expectedSizeBytes = validatedEntry.expectedSizeBytes();
            entry.expectedSha256 = validatedEntry.expectedSha256();
            entry.expectedMd5 = validatedEntry.expectedMd5();
            entry.expectedUnencryptedMd5 = validatedEntry.expectedUnencryptedMd5();
            entry.readRole = validatedEntry.readRole();
            entry.status = UploadBatchEntryStatus.REGISTERED;
            entry.bytesTransferred = 0;
            entry.attempts = 0;
            entry.createdAt = now;
            entry.updatedAt = now;
            entry.persist();
            batch.entries.add(entry);
            fileReservationService.reserve(entry);
        }

        budget.reservedBytes += declaredBytes;
        batch.flush();
        LOG.infof("Registered upload batch %s with %d entries and %d declared bytes",
                batchId, validation.entries().size(), declaredBytes);
        return new UploadBatchRegistrationResult(batchId, List.of());
    }

    private void validateOperationalConfig() {
        if (sftpGoConfig.s3Endpoint().filter(value -> !value.isBlank()).isEmpty()
                || sftpGoConfig.s3Region().filter(value -> !value.isBlank()).isEmpty()
                || sftpGoConfig.s3BucketName().filter(value -> !value.isBlank()).isEmpty()
                || sftpGoConfig.s3AccessKey().filter(value -> !value.isBlank()).isEmpty()
                || sftpGoConfig.s3SecretKey().filter(value -> !value.isBlank()).isEmpty()) {
            throw new ServiceUnavailableException("SFTPGo S3 storage configuration is incomplete");
        }
        if (batchConfig.uploadWindow().isNegative() || batchConfig.uploadWindow().isZero()
                || batchConfig.failedStagingRetention().isNegative()
                || batchConfig.maxFilesPerBatch() < 1
                || batchConfig.maxFilesPerBatch() > UploadBatchLimits.MAX_FILES_PER_BATCH
                || batchConfig.maxFileSizeBytes() < 1
                || batchConfig.maxFileSizeBytes() > UploadBatchLimits.MAX_FILE_SIZE_BYTES
                || batchConfig.maxTransfersPerBatch() < 1 || batchConfig.maxTotalTransfers() < 1
                || batchConfig.multipartCopyPartSizeBytes() < 5L * 1024 * 1024
                || batchConfig.multipartCopyPartSizeBytes() > ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES
                || (UploadBatchLimits.MAX_FILE_SIZE_BYTES + batchConfig.multipartCopyPartSizeBytes() - 1)
                        / batchConfig.multipartCopyPartSizeBytes() > 10_000) {
            throw new ServiceUnavailableException("SFTP upload batch limits are invalid");
        }
        if (sftpGoConfig.resumeMaxSizeBytes() < batchConfig.maxFileSizeBytes()) {
            throw new ServiceUnavailableException("SFTPGo resume limit is below the configured batch file limit");
        }
    }

    private String normalizePublicKey(String publicKey) {
        if (publicKey == null || publicKey.isBlank() || publicKey.length() > 8192) return null;
        String normalized = publicKey.trim();
        String[] parts = normalized.split("\\s+", 3);
        if (parts.length < 2 || !parts[0].matches("[A-Za-z0-9@._+-]+")) return null;
        try {
            if (Base64.getDecoder().decode(parts[1]).length < 16) return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return normalized;
    }

    private UploadBatchRegistrationResult invalidSizeResult() {
        return new UploadBatchRegistrationResult(null, List.of(new UploadManifestIssue(
                0, "sizeBytes", "Declared byte total exceeds the supported range")));
    }
}