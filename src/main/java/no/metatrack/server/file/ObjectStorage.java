package no.metatrack.server.file;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ObjectStorage {
    long MULTIPART_COPY_THRESHOLD_BYTES = 5_000_000_000L;
    String presignUpload(String objectKey, Duration expiry);

    String presignDownload(String objectKey, Duration expiry);

    boolean objectExists(String objectKey);

    List<StorageObjectMetadata> listObjects(String prefix);

    void delete(String objectKey);

    void purgeAllVersions(String objectKey);

    Optional<StorageObjectIdentity> objectIdentity(String objectKey);

    InputStream openObject(String objectKey, StorageObjectIdentity identity);

    StorageObjectIdentity copyObject(
            String sourceKey, String destinationKey, StorageObjectIdentity source, Map<String, String> metadata);

    String initiateMultipartCopy(String destinationKey, Map<String, String> metadata);

    List<StorageObjectCopyPart> listMultipartCopyParts(String destinationKey, String uploadId);

    StorageObjectCopyPart copyObjectPart(
            String sourceKey,
            String destinationKey,
            String uploadId,
            StorageObjectIdentity source,
            int partNumber,
            long firstByte,
            long lastByte);

    StorageObjectIdentity completeMultipartCopy(
            String destinationKey, String uploadId, List<StorageObjectCopyPart> parts);

    void abortMultipartCopy(String destinationKey, String uploadId);

    List<StorageMultipartUpload> listMultipartUploads(String prefix);
}