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

        if (File.findByObjectKeyOptional(objectKey).isPresent()) {
            throw new WebApplicationException("File already exists!", 409);
        }

        UUID fileId = UUID.randomUUID();

        File file = new File();
        file.uuid = fileId;
        file.fileName = fileName;
        file.virtualPath = objectKey;
        file.objectKey = objectKey;
        file.status = UploadStatus.PENDING;
        file.persist();

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
