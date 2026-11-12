package no.metatrack.server.file;

import no.metatrack.server.assay.Assay;
import no.metatrack.server.sample.Sample;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class FileImportPendingOverwriteTest {
    @Test
    void overwritesOnlyUnreservedPendingFileMetadataAndPreservesBlankChecksums() {
        Long projectId = 1L;
        UUID assayId = UUID.randomUUID();
        Sample sample = new Sample();
        sample.name = "sample";
        Assay assay = new Assay();
        File existing = new File();
        existing.fileName = "reads.fastq";
        existing.md5 = "old-md5";
        existing.unencryptedMd5 = "old-plain-md5";
        existing.readRole = ReadRole.SINGLE;
        existing.status = UploadStatus.PENDING;
        existing.sample = sample;
        existing.assay = assay;
        String virtualPath = PresignUrlService.virtualPath(projectId, assayId, sample.name, existing.fileName);

        try (MockedStatic<File> files = mockStatic(File.class)) {
            files.when(() -> File.findByVirtualPathOptional(virtualPath)).thenReturn(Optional.of(existing));
            files.when(() -> File.validateImportPending(
                    projectId, assayId, sample, assay, existing.fileName, "new-md5", null, ReadRole.SINGLE, true))
                    .thenCallRealMethod();
            files.when(() -> File.importPending(
                    projectId, assayId, sample, assay, existing.fileName, "new-md5", null, ReadRole.SINGLE, true))
                    .thenCallRealMethod();
            files.when(() -> File.validateImportPending(
                    projectId, assayId, sample, assay, existing.fileName, "reserved-md5", "reserved-plain-md5",
                    ReadRole.SINGLE, true)).thenCallRealMethod();
            files.when(() -> File.importPending(
                    projectId, assayId, sample, assay, existing.fileName, "reserved-md5", "reserved-plain-md5",
                    ReadRole.SINGLE, true)).thenCallRealMethod();
            files.when(() -> File.validateImportPending(
                    projectId, assayId, sample, assay, existing.fileName, "wrong-owner-md5", null,
                    ReadRole.SINGLE, true)).thenCallRealMethod();

            assertTrue(File.importPending(
                    projectId, assayId, sample, assay, existing.fileName, "new-md5", null, ReadRole.SINGLE, true)
                    .isEmpty());
            assertEquals("new-md5", existing.md5);
            assertEquals("old-plain-md5", existing.unencryptedMd5);

            existing.uploadedBy = UUID.randomUUID();
            Optional<String> error = File.importPending(
                    projectId, assayId, sample, assay, existing.fileName, "reserved-md5", "reserved-plain-md5",
                    ReadRole.SINGLE, true);

            assertTrue(error.isPresent());
            assertTrue(error.get().contains("upload is active"));
            assertEquals("new-md5", existing.md5);
            assertEquals("old-plain-md5", existing.unencryptedMd5);

            existing.uploadedBy = null;
            existing.sample = new Sample();
            Optional<String> ownershipError = File.validateImportPending(
                    projectId, assayId, sample, assay, existing.fileName, "wrong-owner-md5", null,
                    ReadRole.SINGLE, true);

            assertEquals("File is not pending for this sample and assay", ownershipError.orElseThrow());
            assertEquals("new-md5", existing.md5);
        }
    }
}