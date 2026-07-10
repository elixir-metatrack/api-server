package no.metatrack.server.file;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadReconciliationSchedulerTest {
    @Test
    void reconcilesOnlyOneBoundedBatchPerRun() {
        UploadReconciliationWorker worker = mock(UploadReconciliationWorker.class);
        UploadReconciliationService service = new UploadReconciliationService();
        service.worker = worker;
        service.batchSize = 2;
        when(worker.pendingFileIds(0, 2)).thenReturn(List.of(10L, 20L));

        service.reconcilePendingUploads();

        verify(worker).pendingFileIds(0, 2);
        verify(worker).reconcile(10L);
        verify(worker).reconcile(20L);
    }
}