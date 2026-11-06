package no.metatrack.server.upload;

public final class UploadBatchLimits {
    public static final int MAX_FILES_PER_BATCH = 100;
    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 * 1024;

    private UploadBatchLimits() {
    }
}