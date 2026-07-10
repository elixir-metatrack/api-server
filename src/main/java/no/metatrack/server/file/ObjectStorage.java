package no.metatrack.server.file;

import java.time.Duration;

public interface ObjectStorage {
    String presignUpload(String objectKey, Duration expiry);

    String presignDownload(String objectKey, Duration expiry);

    boolean objectExists(String objectKey);

    void delete(String objectKey);
}