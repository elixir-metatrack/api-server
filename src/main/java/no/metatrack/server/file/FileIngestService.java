package no.metatrack.server.file;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class FileIngestService {

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

        } catch (Exception e) {
            throw new WebApplicationException("Error validating object", 500);
        }
    }

    private static String decodeS3Key(String key) {
        return URLDecoder.decode(key, StandardCharsets.UTF_8);
    }
}
