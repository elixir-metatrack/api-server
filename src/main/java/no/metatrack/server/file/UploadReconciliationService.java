package no.metatrack.server.file;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class UploadReconciliationService {
    @Inject
    UploadReconciliationWorker worker;

    @ConfigProperty(name = "metatrack.file.reconciliation-batch-size")
    int batchSize;

    private long lastFileId;

    @Scheduled(every = "${metatrack.file.reconciliation-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcilePendingUploads() {
        List<Long> pendingFileIds = worker.pendingFileIds(lastFileId, batchSize);
        if (pendingFileIds.isEmpty() && lastFileId != 0) {
            lastFileId = 0;
            pendingFileIds = worker.pendingFileIds(lastFileId, batchSize);
        }
        pendingFileIds.forEach(worker::reconcile);
        if (!pendingFileIds.isEmpty()) lastFileId = pendingFileIds.getLast();
    }
}