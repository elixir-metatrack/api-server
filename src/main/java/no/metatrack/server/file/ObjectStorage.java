package no.metatrack.server.file;

import java.time.Duration;
import java.util.List;

public interface ObjectStorage {
    String presignUpload(String objectKey, Duration expiry);

    String presignDownload(String objectKey, Duration expiry);

    boolean objectExists(String objectKey);

    List<StorageObjectMetadata> listObjects(String prefix);

    void delete(String objectKey);
}