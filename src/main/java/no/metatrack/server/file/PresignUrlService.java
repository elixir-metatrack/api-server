package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.sample.Sample;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PresignUrlService {
    @Inject
    ObjectStorage objectStorage;

    @Transactional
    public PresignedUrl presignedUploadUrl(
            Long projectId, UUID assayId, String sampleName, String fileName, int expiryInSeconds) {
        Sample sample = Sample.findBySampleNameInProject(sampleName, projectId).orElseThrow(NotFoundException::new);
        Assay assay = findAssayForSample(projectId, assayId, sample.id);

        String virtualPath = virtualPath(projectId, assayId, sampleName, fileName);
        var existingFile = File.findByVirtualPathOptional(virtualPath);
        String objectKey = objectKeyForUpload(existingFile, virtualPath);
        if (existingFile.isPresent()) {
            File file = existingFile.get();
            file.sample = sample;
            file.assay = assay;

            return presignUpload(objectKey, expiryInSeconds);
        }

        UUID fileId = UUID.randomUUID();

        File file = new File();
        file.uuid = fileId;
        file.fileName = fileName;
        file.virtualPath = virtualPath;
        file.objectKey = objectKey;
        file.status = UploadStatus.PENDING;
        file.sample = sample;
        file.assay = assay;

        sample.files.add(file);

        return presignUpload(objectKey, expiryInSeconds);
    }

    public PresignedUrl presignedDownloadUrl(
            Long projectId, UUID assayId, String sampleName, String fileName, int expiryInSeconds) {
        Sample sample = Sample.findBySampleNameInProject(sampleName, projectId).orElseThrow(NotFoundException::new);
        findAssayForSample(projectId, assayId, sample.id);
        String virtualPath = virtualPath(projectId, assayId, sampleName, fileName);
        File file = File.findByVirtualPathOptional(virtualPath).orElseThrow(NotFoundException::new);
        String url = objectStorage.presignDownload(file.objectKey, Duration.ofSeconds(expiryInSeconds));
        return new PresignedUrl(url, file.objectKey);
    }

    private PresignedUrl presignUpload(String objectKey, int expiryInSeconds) {
        String url = objectStorage.presignUpload(objectKey, Duration.ofSeconds(expiryInSeconds));
        return new PresignedUrl(url, objectKey);
    }

    static String virtualPath(Long projectId, UUID assayId, String sampleName, String fileName) {
        return projectId + "/" + assayId + "/" + sampleName + "/" + fileName;
    }

    private Assay findAssayForSample(Long projectId, UUID assayId, UUID sampleId) {
        return Assay.<Assay>find(
                        "select a from Assay a join a.samples s where a.id = ?1 and a.project.id = ?2 and s.id = ?3",
                        assayId, projectId, sampleId)
                .firstResultOptional()
                .orElseThrow(NotFoundException::new);
    }

    static String uploadObjectKey(String virtualPath) {
        return virtualPath + "/" + UUID.randomUUID();
    }

    static String objectKeyForUpload(Optional<File> existingFile, String virtualPath) {
        return existingFile.map(file -> file.objectKey).orElseGet(() -> uploadObjectKey(virtualPath));
    }
}
