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

        FileResponse response = FileResponse.fromEntity(file);

        assertEquals(file.uploadedBy, response.uploadedBy());
    }

    @Test
    void supportsLegacyFilesWithoutUploader() {
        FileResponse response = FileResponse.fromEntity(new File());

        assertNull(response.uploadedBy());
    }
}