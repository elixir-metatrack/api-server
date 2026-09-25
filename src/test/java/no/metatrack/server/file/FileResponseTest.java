package no.metatrack.server.file;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FileResponseTest {
    @Test
    void includesUploader() {
        File file = new File();
        file.uploadedBy = UUID.randomUUID();
        file.readRole = ReadRole.REVERSE;
        file.md5 = "reverse-md5";
        file.unencryptedMd5 = "reverse-unencrypted-md5";

        FileResponse response = FileResponse.fromEntity(file);

        assertEquals(file.uploadedBy, response.uploadedBy());
        assertEquals(ReadRole.REVERSE, response.readRole());
        assertEquals("reverse-md5", response.md5());
        assertEquals("reverse-unencrypted-md5", response.unencryptedMd5());
    }

    @Test
    void supportsLegacyFilesWithoutUploader() {
        FileResponse response = FileResponse.fromEntity(new File());

        assertNull(response.uploadedBy());
        assertNull(response.readRole());
        assertNull(response.md5());
        assertNull(response.unencryptedMd5());
    }
}