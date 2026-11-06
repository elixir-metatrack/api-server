package no.metatrack.server.upload;

public enum UploadBatchEntryStatus {
    REGISTERED,
    UPLOADING,
    VALIDATING,
    PROMOTING,
    COMMITTED,
    FAILED
}