package no.metatrack.server.file;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.sample.Sample;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

@ApplicationScoped
public class PresignUrlService {
    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "quarkus.minio.bucket-name")
    String bucketName;

    @Transactional
    public String presignedUploadUrl(String projectId, String sampleName, String fileName, int expiryInSeconds)
            throws Exception {
        Sample sample = Sample.findBySampleNameInProject(sampleName, Long.parseLong(projectId))
                .orElseThrow(NotFoundException::new);

        String objectKey = projectId + "/" + sampleName + "/" + fileName;

        var argsBuilder = GetPresignedObjectUrlArgs.builder();

        if (File.findByObjectKeyOptional(objectKey).isPresent()) {
            File file = File.findByObjectKeyOptional(objectKey).get();
            file.status = UploadStatus.PENDING;

            argsBuilder.method(Method.PUT).bucket(bucketName).object(objectKey).expiry(expiryInSeconds);

            return minioClient.getPresignedObjectUrl(argsBuilder.build());
        }

        UUID fileId = UUID.randomUUID();

        File file = new File();
        file.uuid = fileId;
        file.fileName = fileName;
        file.virtualPath = objectKey;
        file.objectKey = objectKey;
        file.status = UploadStatus.PENDING;
        file.sample = sample;

        sample.files.add(file);

        argsBuilder.method(Method.PUT).bucket(bucketName).object(objectKey).expiry(expiryInSeconds);

        return minioClient.getPresignedObjectUrl(argsBuilder.build());
    }

    public String presignedDownloadUrl(String projectId, String sampleName, String fileName, int expiryInSeconds)
            throws Exception {
        String objectKey = projectId + "/" + sampleName + "/" + fileName;
        var args = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucketName)
                .object(objectKey)
                .expiry(expiryInSeconds)
                .build();

        return minioClient.getPresignedObjectUrl(args);
    }
}
