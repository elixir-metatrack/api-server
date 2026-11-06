package no.metatrack.server.upload;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UploadBatchExpiryWorker {
    private static final int PAGE_SIZE = 50;

    @Inject
    UploadBatchLifecycleService lifecycleService;

    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void expireUploadWindows() {
        for (var batchId : lifecycleService.expiredOpenBatchIds(PAGE_SIZE)) {
            lifecycleService.expireIfOpen(batchId);
        }
    }
}