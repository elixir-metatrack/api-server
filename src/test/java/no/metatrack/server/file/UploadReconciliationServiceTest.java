package no.metatrack.server.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UploadReconciliationServiceTest {
    private ObjectStorage objectStorage;
    private UploadReconciliationWorker worker;
    private File file;

    @BeforeEach
    void setUp() {
        objectStorage = mock(ObjectStorage.class);
        worker = new UploadReconciliationWorker();
        worker.objectStorage = objectStorage;
        file = new File();
        file.objectKey = "1/sample/result.fastq";
        file.status = UploadStatus.PENDING;
    }

    @Test
    void marksPendingFileUploadedWhenObjectExists() {
        when(objectStorage.objectExists(file.objectKey)).thenReturn(true);

        assertTrue(worker.reconcile(file));
        assertEquals(UploadStatus.UPLOADED, file.status);
    }

    @Test
    void keepsFilePendingWhenObjectDoesNotExist() {
        when(objectStorage.objectExists(file.objectKey)).thenReturn(false);

        assertFalse(worker.reconcile(file));
        assertEquals(UploadStatus.PENDING, file.status);
    }

    @Test
    void keepsFilePendingWhenStorageIsTemporarilyUnavailable() {
        when(objectStorage.objectExists(file.objectKey)).thenThrow(new RuntimeException("unavailable"));

        assertFalse(worker.reconcile(file));
        assertEquals(UploadStatus.PENDING, file.status);
    }
}