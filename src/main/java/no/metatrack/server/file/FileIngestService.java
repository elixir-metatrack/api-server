package no.metatrack.server.file;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@ApplicationScoped
public class FileIngestService {

    private static final Logger LOG = Logger.getLogger(FileIngestService.class);

    @Inject
    MinioClient minioClient;

    @ConfigProperty(name = "quarkus.minio.bucket-name")
    String bucketName;

    @Transactional
    public void handleObjectCreated(MinioEvent.Record record) {
        String rawKey = record.s3().object().key();
        String key = decodeS3Key(rawKey);

        try {
            var statObjectArgs =
                    StatObjectArgs.builder().bucket(bucketName).object(key).build();
            minioClient.statObject(statObjectArgs);

            File file = File.findByObjectKeyOptional(key)
                    .orElseThrow(() -> new WebApplicationException("File Not Found", 404));

            file.status = UploadStatus.UPLOADED;

        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new WebApplicationException("Error validating object in MinIO: " + e.getMessage(), 500);
        }
    }

    @Transactional
    public void deleteFile(UUID fileUuid, UUID sampleId) {
        File file = File.<File>find("uuid = ?1 and sample.id = ?2", fileUuid, sampleId)
                .firstResultOptional()
                .orElseThrow(() -> new NotFoundException("File not found"));

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucketName).object(file.objectKey).build());
        } catch (Exception e) {
            LOG.warnf("Could not remove object from MinIO storage (key=%s): %s", file.objectKey, e.getMessage());
        }

        file.delete();
    }

    private static String decodeS3Key(String key) {
        return URLDecoder.decode(key, StandardCharsets.UTF_8);
    }
}
