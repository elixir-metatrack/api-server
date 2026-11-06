package no.metatrack.server.upload;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class UploadBatchProvisioningWorker {
    private static final Logger LOG = Logger.getLogger(UploadBatchProvisioningWorker.class);
    private static final int PAGE_SIZE = 20;

    @Inject
    UploadBatchProvisioningStateService stateService;

    @Inject
    UploadBatchProvisioningService provisioningService;

    @Scheduled(every = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void provisionPendingBatches() {
        for (var batchId : stateService.provisioningBatchIds(PAGE_SIZE)) {
            if (!provisioningService.provision(batchId)) {
                LOG.debugf("Upload batch %s remains in provisioning", batchId);
            }
        }
    }
}