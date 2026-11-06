package no.metatrack.server.upload;

public enum UploadBatchStatus {
    PROVISIONING,
    OPEN,
    DRAINING,
    PROCESSING,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED,
    EXPIRED
}