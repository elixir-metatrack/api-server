package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class FileIngestService {

    private static final Logger LOG = Logger.getLogger(FileIngestService.class);

    @Inject
    ObjectStorage objectStorage;

    @Transactional
    public void deleteFile(UUID fileUuid, UUID sampleId) {
        File file = File.<File>find("uuid = ?1 and sample.id = ?2", fileUuid, sampleId)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("File not found"));

        try {
            objectStorage.delete(file.objectKey);
        } catch (Exception e) {
            LOG.warnf("Could not remove object from S3 storage (key=%s): %s", file.objectKey, e.getMessage());
        }

        file.delete();
    }
}
