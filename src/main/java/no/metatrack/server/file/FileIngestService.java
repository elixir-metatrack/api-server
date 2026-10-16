package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class FileIngestService {

    private static final Logger LOG = Logger.getLogger(FileIngestService.class);

    @Inject
    ObjectStorage objectStorage;

    @Transactional
    public void deleteFile(Long projectId, UUID fileUuid) {
        File file = File.<File>find("uuid = ?1 and sample.project.id = ?2 and assay.project.id = ?2", fileUuid, projectId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("File not found"));

        if (file.status != UploadStatus.UPLOADED) {
            throw new WebApplicationException("Only uploaded files can be deleted", 409);
        }
        try {
            objectStorage.purgeAllVersions(file.objectKey);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Storage cleanup failed for file %s", fileUuid);
            throw new WebApplicationException("File storage cleanup failed", 502);
        }

        file.sample.files.remove(file);
        file.delete();
        File.flush();
    }
}
