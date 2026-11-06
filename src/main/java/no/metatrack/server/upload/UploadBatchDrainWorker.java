package no.metatrack.server.upload;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchDrainWorker {
    private static final Logger LOG = Logger.getLogger(UploadBatchDrainWorker.class);
    private static final int PAGE_SIZE = 50;

    @Inject
    UploadBatchLifecycleService lifecycleService;

    @Inject
    UploadBatchAdmissionService admissionService;

    @Inject
    SftpGoManagementService sftpGoManagementService;

    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void drainFinalizedBatches() {
        List<UUID> batchIds = lifecycleService.drainingBatchIds(PAGE_SIZE);
        if (batchIds.isEmpty()) return;
        try {
            var connections = sftpGoManagementService.activeConnections();
            for (var batchId : batchIds) {
                admissionService.advanceDrain(batchId, connections);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Unable to inspect SFTPGo transfers while draining upload batches", exception);
        }
    }
}