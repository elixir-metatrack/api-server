package no.metatrack.server.file;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

@ApplicationScoped
public class PresignUrlService {
    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "quarkus.minio.bucket-name")
    String bucketName;

    @Transactional
    public String presignedUploadUrl(String projectId, String sampleId, String fileName, int expiryInSeconds)
            throws Exception {

        String objectKey = projectId + "/" + sampleId + "/" + fileName;

        if (FileEntity.findByObjectKeyOptional(objectKey).isPresent()) {
            throw new WebApplicationException("File already exists!", 409);
        }

        UUID fileId = UUID.randomUUID();

        FileEntity fileEntity = new FileEntity();
        fileEntity.uuid = fileId;
        fileEntity.fileName = fileName;
        fileEntity.virtualPath = objectKey;
        fileEntity.objectKey = objectKey;
        fileEntity.status = UploadStatus.PENDING;
        fileEntity.persist();

        var args = GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(bucketName)
                .object(objectKey)
                .expiry(expiryInSeconds)
                .build();

        return minioClient.getPresignedObjectUrl(args);
    }

    public String presignedDownloadUrl(String objectKey, int expiryInSeconds) throws Exception {
        var args = GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucketName)
                .object(objectKey)
                .expiry(expiryInSeconds)
                .build();

        return minioClient.getPresignedObjectUrl(args);
    }
}
