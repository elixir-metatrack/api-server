package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.sample.Sample;

import java.time.Duration;
import java.util.UUID;

@ApplicationScoped
public class PresignUrlService {
    @Inject
    ObjectStorage objectStorage;

    @Transactional
    public String presignedUploadUrl(Long projectId, String sampleName, String fileName, int expiryInSeconds) {
        Sample sample = Sample.findBySampleNameInProject(sampleName, projectId).orElseThrow(NotFoundException::new);

        String virtualPath = projectId + "/" + sampleName + "/" + fileName;
        String objectKey = uploadObjectKey(virtualPath);

        var existingFile = File.findByVirtualPathOptional(virtualPath);
        if (existingFile.isPresent()) {
            File file = existingFile.get();
            file.objectKey = objectKey;
            file.status = UploadStatus.PENDING;

            return objectStorage.presignUpload(objectKey, Duration.ofSeconds(expiryInSeconds));
        }

        UUID fileId = UUID.randomUUID();

        File file = new File();
        file.uuid = fileId;
        file.fileName = fileName;
        file.virtualPath = virtualPath;
        file.objectKey = objectKey;
        file.status = UploadStatus.PENDING;
        file.sample = sample;

        sample.files.add(file);

        return objectStorage.presignUpload(objectKey, Duration.ofSeconds(expiryInSeconds));
    }

    public String presignedDownloadUrl(Long projectId, String sampleName, String fileName, int expiryInSeconds) {
        String virtualPath = projectId + "/" + sampleName + "/" + fileName;
        File file = File.findByVirtualPathOptional(virtualPath).orElseThrow(NotFoundException::new);
        return objectStorage.presignDownload(file.objectKey, Duration.ofSeconds(expiryInSeconds));
    }

    static String uploadObjectKey(String virtualPath) {
        return virtualPath + "/" + UUID.randomUUID();
    }
}
