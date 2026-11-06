package no.metatrack.server.upload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import no.metatrack.server.file.FileIngestService;
import no.metatrack.server.file.ObjectStorage;
import no.metatrack.server.file.StorageObjectCopyPart;
import no.metatrack.server.file.StorageObjectIdentity;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class UploadBatchIngestionService {
    private static final Logger LOG = Logger.getLogger(UploadBatchIngestionService.class);
    private static final int BUFFER_SIZE_BYTES = 64 * 1024;
    private static final int MAX_MULTIPART_PARTS = 10_000;
    private static final long MIN_MULTIPART_PART_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Duration LEASE_RENEWAL_INTERVAL = Duration.ofSeconds(20);

    @Inject
    ObjectStorage objectStorage;

    @Inject
    UploadBatchIngestionStateService stateService;

    @Inject
    FileIngestService fileIngestService;

    @Inject
    UploadBatchConfig config;

    public void process(UploadBatchEntryClaim claim) {
        try {
            StorageObjectIdentity source = objectStorage.objectIdentity(claim.stagingKey())
                    .orElseThrow(() -> new PermanentIngestionException("Staged object is missing"));
            verifyStableSourceIdentity(claim, source);
            if (source.size() != claim.expectedSizeBytes()) {
                throw new PermanentIngestionException("Staged file byte size does not match the manifest");
            }

            HashResult sourceHash = hashObject(claim, claim.stagingKey(), source);
            verifyManifestHashes(claim, sourceHash);
            if (!stateService.recordValidatedSource(claim.entryId(), claim.leaseToken(), source)) return;

            StorageObjectIdentity destination = promote(claim, source);
            if (!stateService.recordDestinationIdentity(claim.entryId(), claim.leaseToken(), destination)) return;
            fileIngestService.commitBatchUpload(
                    claim.entryId(), claim.leaseToken(), sourceHash.size(), sourceHash.sha256(), destination);
        } catch (LeaseLostException ignored) {
            LOG.debugf("Ingestion lease lost for upload batch entry %s", claim.entryId());
        } catch (PermanentIngestionException exception) {
            abortClaimedMultipartUpload(claim);
            stateService.markFailed(claim.entryId(), claim.leaseToken(), exception.getMessage());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 412) {
                abortClaimedMultipartUpload(claim);
                stateService.markFailed(claim.entryId(), claim.leaseToken(),
                        "Staged object changed while it was being validated or promoted");
            } else {
                LOG.warnf("Transient S3 failure for upload batch entry %s: %s",
                        claim.entryId(), exception.awsErrorDetails() == null
                                ? exception.getMessage()
                                : exception.awsErrorDetails().errorCode());
                stateService.scheduleRetry(claim.entryId(), claim.leaseToken(), "Transient S3 storage failure");
            }
        } catch (RuntimeException exception) {
            LOG.warnf("Transient ingestion failure for upload batch entry %s: %s",
                    claim.entryId(), exception.getClass().getSimpleName());
            stateService.scheduleRetry(claim.entryId(), claim.leaseToken(), "Transient ingestion infrastructure failure");
        }
    }

    private HashResult hashObject(UploadBatchEntryClaim claim, String objectKey, StorageObjectIdentity identity) {
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest md5 = digest("MD5");
        byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long bytesRead = 0;
        long nextRenewal = System.nanoTime() + LEASE_RENEWAL_INTERVAL.toNanos();
        try (InputStream input = objectStorage.openObject(objectKey, identity)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytesRead += read;
                if (bytesRead > claim.expectedSizeBytes()) {
                    throw new PermanentIngestionException("Staged file exceeds the declared byte size");
                }
                sha256.update(buffer, 0, read);
                md5.update(buffer, 0, read);
                if (System.nanoTime() >= nextRenewal) {
                    if (!stateService.renewLease(claim.entryId(), claim.leaseToken(), bytesRead)) {
                        throw new LeaseLostException();
                    }
                    nextRenewal = System.nanoTime() + LEASE_RENEWAL_INTERVAL.toNanos();
                }
            }
        } catch (IOException exception) {
            throw new TransientIngestionException("Unable to stream S3 object", exception);
        }
        if (bytesRead != claim.expectedSizeBytes()) {
            throw new PermanentIngestionException("Staged file byte size does not match the manifest");
        }
        StorageObjectIdentity currentIdentity = objectStorage.objectIdentity(objectKey)
                .orElseThrow(() -> new PermanentIngestionException("S3 object disappeared during verification"));
        if (!sameIdentity(identity, currentIdentity)) {
            throw new PermanentIngestionException("S3 object identity changed during verification");
        }
        return new HashResult(bytesRead, HexFormat.of().formatHex(sha256.digest()), HexFormat.of().formatHex(md5.digest()));
    }

    private StorageObjectIdentity promote(UploadBatchEntryClaim claim, StorageObjectIdentity source) {
        Optional<StorageObjectIdentity> existingDestination = objectStorage.objectIdentity(claim.destinationObjectKey());
        if (existingDestination.isPresent()) {
            StorageObjectIdentity destination = existingDestination.get();
            if (destination.size() != claim.expectedSizeBytes()) {
                throw new PermanentIngestionException("Stable destination already contains an incompatible object");
            }
            if (!hasPromotionMarkers(destination, claim, source)) {
                verifyManifestHashes(claim, hashObject(claim, claim.destinationObjectKey(), destination));
            }
            return destination;
        }

        Map<String, String> metadata = promotionMetadata(claim, source);
        StorageObjectIdentity destination = !requiresMultipartCopy(source.size())
                ? objectStorage.copyObject(claim.stagingKey(), claim.destinationObjectKey(), source, metadata)
                : multipartCopy(claim, source, metadata);
        if (destination.size() != claim.expectedSizeBytes()) {
            throw new PermanentIngestionException("Promoted file byte size does not match the manifest");
        }
        if (!hasPromotionMarkers(destination, claim, source)) {
            verifyManifestHashes(claim, hashObject(claim, claim.destinationObjectKey(), destination));
        }
        return destination;
    }

    static boolean requiresMultipartCopy(long objectSizeBytes) {
        return objectSizeBytes > ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES;
    }

    StorageObjectIdentity multipartCopy(
            UploadBatchEntryClaim claim, StorageObjectIdentity source, Map<String, String> metadata) {
        long partSize = claim.multipartPartSizeBytes() == null
                ? config.multipartCopyPartSizeBytes()
                : claim.multipartPartSizeBytes();
        if (partSize < MIN_MULTIPART_PART_SIZE_BYTES
                || partSize > ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES) {
            throw new PermanentIngestionException("Configured multipart copy part size is invalid");
        }
        long partCount = (source.size() + partSize - 1) / partSize;
        if (partCount > MAX_MULTIPART_PARTS) {
            throw new PermanentIngestionException("Object requires too many multipart copy parts");
        }

        String uploadId = claim.multipartUploadId();
        List<StorageObjectCopyPart> completedParts = List.of();
        if (uploadId != null) {
            try {
                completedParts = objectStorage.listMultipartCopyParts(claim.destinationObjectKey(), uploadId);
            } catch (S3Exception exception) {
                if (!"NoSuchUpload".equals(errorCode(exception))) throw exception;
                stateService.clearMultipartUpload(claim.entryId(), claim.leaseToken());
                uploadId = null;
            }
        }
        if (uploadId == null) {
            uploadId = objectStorage.initiateMultipartCopy(claim.destinationObjectKey(), metadata);
            if (!stateService.recordMultipartUpload(
                    claim.entryId(), claim.leaseToken(), uploadId, partSize)) {
                objectStorage.abortMultipartCopy(claim.destinationObjectKey(), uploadId);
                throw new LeaseLostException();
            }
            completedParts = List.of();
        }

        Map<Integer, StorageObjectCopyPart> partsByNumber = new HashMap<>();
        for (StorageObjectCopyPart part : completedParts) partsByNumber.put(part.partNumber(), part);
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            if (partsByNumber.containsKey(partNumber)) continue;
            long firstByte = (partNumber - 1L) * partSize;
            long lastByte = Math.min(source.size() - 1, firstByte + partSize - 1);
            if (!stateService.renewLease(claim.entryId(), claim.leaseToken(), firstByte)) {
                throw new LeaseLostException();
            }
            StorageObjectCopyPart copiedPart;
            try {
                copiedPart = objectStorage.copyObjectPart(
                        claim.stagingKey(), claim.destinationObjectKey(), uploadId, source,
                        partNumber, firstByte, lastByte);
            } catch (S3Exception exception) {
                if (exception.statusCode() == 412) {
                    objectStorage.abortMultipartCopy(claim.destinationObjectKey(), uploadId);
                    throw new PermanentIngestionException("Staged object changed during multipart copy");
                }
                throw exception;
            }
            partsByNumber.put(partNumber, copiedPart);
            if (!stateService.renewLease(claim.entryId(), claim.leaseToken(), lastByte + 1)) {
                throw new LeaseLostException();
            }
        }
        return objectStorage.completeMultipartCopy(claim.destinationObjectKey(), uploadId,
                new ArrayList<>(partsByNumber.values()));
    }

    private Map<String, String> promotionMetadata(UploadBatchEntryClaim claim, StorageObjectIdentity source) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("metatrack-batch-id", claim.batchId().toString());
        metadata.put("metatrack-entry-id", claim.entryId().toString());
        metadata.put("metatrack-sha256", claim.expectedSha256().toLowerCase(java.util.Locale.ROOT));
        metadata.put("metatrack-size", Long.toString(claim.expectedSizeBytes()));
        if (source.eTag() != null) metadata.put("metatrack-source-etag", source.eTag());
        if (source.versionId() != null) metadata.put("metatrack-source-version-id", source.versionId());
        return metadata;
    }

    private boolean hasPromotionMarkers(
            StorageObjectIdentity destination, UploadBatchEntryClaim claim, StorageObjectIdentity source) {
        Map<String, String> metadata = destination.metadata();
        return claim.batchId().toString().equals(metadata.get("metatrack-batch-id"))
                && claim.entryId().toString().equals(metadata.get("metatrack-entry-id"))
                && claim.expectedSha256().equalsIgnoreCase(metadata.get("metatrack-sha256"))
                && Long.toString(claim.expectedSizeBytes()).equals(metadata.get("metatrack-size"))
                && Objects.equals(source.eTag(), metadata.get("metatrack-source-etag"))
                && Objects.equals(source.versionId(), metadata.get("metatrack-source-version-id"));
    }

    private void verifyStableSourceIdentity(UploadBatchEntryClaim claim, StorageObjectIdentity source) {
        if ((source.eTag() == null || source.eTag().isBlank())
                && (source.versionId() == null || source.versionId().isBlank())) {
            throw new PermanentIngestionException("S3 provider did not return a stable source identity");
        }
        if (claim.sourceEtag() != null && !claim.sourceEtag().equals(source.eTag())) {
            throw new PermanentIngestionException("S3 source ETag changed after validation");
        }
        if (claim.sourceVersionId() != null && !claim.sourceVersionId().equals(source.versionId())) {
            throw new PermanentIngestionException("S3 source version changed after validation");
        }
    }

    private void verifyManifestHashes(UploadBatchEntryClaim claim, HashResult result) {
        if (!claim.expectedSha256().equalsIgnoreCase(result.sha256())) {
            throw new PermanentIngestionException("SHA-256 checksum does not match the manifest");
        }
        if (!matchesMd5(claim.expectedMd5(), result.md5())) {
            throw new PermanentIngestionException("Declared MD5 checksum does not match the pending file metadata");
        }
        if (!matchesMd5(claim.expectedUnencryptedMd5(), result.md5())) {
            throw new PermanentIngestionException("Declared unencrypted MD5 does not match the pending file metadata");
        }
    }

    private boolean matchesMd5(String expected, String actual) {
        return expected == null || expected.isBlank()
                || expected.trim().matches("[0-9A-Fa-f]{32}") && expected.trim().equalsIgnoreCase(actual);
    }

    private boolean sameIdentity(StorageObjectIdentity first, StorageObjectIdentity second) {
        return first.size() == second.size()
                && Objects.equals(first.eTag(), second.eTag())
                && Objects.equals(first.versionId(), second.versionId())
                && Objects.equals(first.lastModified(), second.lastModified());
    }

    private MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required checksum algorithm is unavailable", exception);
        }
    }

    private String errorCode(S3Exception exception) {
        return exception.awsErrorDetails() == null ? null : exception.awsErrorDetails().errorCode();
    }

    private void abortClaimedMultipartUpload(UploadBatchEntryClaim claim) {
        if (claim.multipartUploadId() == null) return;
        try {
            objectStorage.abortMultipartCopy(claim.destinationObjectKey(), claim.multipartUploadId());
        } catch (RuntimeException exception) {
            LOG.warnf("Unable to abort multipart copy for entry %s", claim.entryId());
        }
    }

    private record HashResult(long size, String sha256, String md5) {
    }

    private static class PermanentIngestionException extends RuntimeException {
        private PermanentIngestionException(String message) {
            super(message);
        }
    }

    private static class LeaseLostException extends RuntimeException {
    }

    private static class TransientIngestionException extends RuntimeException {
        private TransientIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}