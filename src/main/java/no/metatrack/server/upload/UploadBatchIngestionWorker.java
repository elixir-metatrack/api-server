package no.metatrack.server.upload;

import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Startup
@ApplicationScoped
public class UploadBatchIngestionWorker {
    private static final Logger LOG = Logger.getLogger(UploadBatchIngestionWorker.class);

    @Inject
    UploadBatchConfig config;

    @Inject
    UploadBatchIngestionStateService stateService;

    @Inject
    UploadBatchIngestionService ingestionService;

    private final AtomicInteger activeWorkers = new AtomicInteger();
    private ExecutorService executor;

    void start(@Observes StartupEvent event) {
        int workerCount = config.maxIngestionWorkers();
        if (workerCount < 1) throw new IllegalStateException("At least one upload ingestion worker is required");
        executor = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = new Thread(task, "upload-batch-ingestion");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void dispatch() {
        int workerCount = config.maxIngestionWorkers();
        while (activeWorkers.get() < workerCount) {
            var claim = stateService.claimNext();
            if (claim.isEmpty()) return;
            activeWorkers.incrementAndGet();
            try {
                executor.execute(() -> {
                    try {
                        ingestionService.process(claim.get());
                    } catch (RuntimeException exception) {
                        LOG.errorf(exception, "Unexpected upload ingestion failure for entry %s", claim.get().entryId());
                        stateService.scheduleRetry(
                                claim.get().entryId(), claim.get().leaseToken(), "Unexpected ingestion worker failure");
                    } finally {
                        activeWorkers.decrementAndGet();
                    }
                });
            } catch (RuntimeException exception) {
                activeWorkers.decrementAndGet();
                stateService.scheduleRetry(claim.get().entryId(), claim.get().leaseToken(),
                        "Ingestion executor is unavailable");
                return;
            }
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }
}