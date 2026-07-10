package no.metatrack.server.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresignUrlServiceTest {
    @Test
    void createsUniqueObjectKeyForEveryUploadAttempt() {
        String virtualPath = "1/sample/result.fastq";

        String firstKey = PresignUrlService.uploadObjectKey(virtualPath);
        String secondKey = PresignUrlService.uploadObjectKey(virtualPath);

        assertTrue(firstKey.startsWith(virtualPath + "/"));
        assertTrue(secondKey.startsWith(virtualPath + "/"));
        assertNotEquals(firstKey, secondKey);
    }
}