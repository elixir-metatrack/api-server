package no.metatrack.server.upload;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;

@ConfigMapping(prefix = "metatrack.upload-batches")
public interface UploadBatchConfig {
    int maxFilesPerBatch();

    long maxFileSizeBytes();

    int maxTransfersPerBatch();

    int maxTotalTransfers();

    int maxIngestionWorkers();

    long stagingBudgetBytes();

    long maxManifestBytes();

    long multipartCopyPartSizeBytes();

    Duration uploadWindow();

    Duration failedStagingRetention();
}