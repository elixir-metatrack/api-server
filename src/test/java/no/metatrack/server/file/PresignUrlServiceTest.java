package no.metatrack.server.file;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresignUrlServiceTest {
    @Test
    void createsUniqueObjectKeyForEveryUploadAttempt() {
        UUID assayId = UUID.randomUUID();
        String virtualPath = PresignUrlService.virtualPath(1L, assayId, "sample", "result.fastq");

        String firstKey = PresignUrlService.uploadObjectKey(virtualPath);
        String secondKey = PresignUrlService.uploadObjectKey(virtualPath);

        assertTrue(firstKey.startsWith(virtualPath + "/"));
        assertTrue(secondKey.startsWith(virtualPath + "/"));
        assertNotEquals(firstKey, secondKey);
    }

    @Test
    void virtualPathIncludesAssayAndSample() {
        UUID assayId = UUID.randomUUID();

        String virtualPath = PresignUrlService.virtualPath(1L, assayId, "sample", "result.fastq");

        assertTrue(virtualPath.equals("1/" + assayId + "/sample/result.fastq"));
    }

    @Test
    void presignedUrlCarriesTheObjectKey() {
        PresignedUrl result = new PresignedUrl("https://upload.example", "generated/object/key");

        assertEquals("https://upload.example", result.url());
        assertEquals("generated/object/key", result.objectKey());
    }

    @Test
    void retryPreservesTheExistingObjectKey() {
        File existingFile = new File();
        existingFile.objectKey = "existing/object/key";

        String objectKey = PresignUrlService.objectKeyForUpload(Optional.of(existingFile), "new/virtual/path");

        assertEquals(existingFile.objectKey, objectKey);
    }
}