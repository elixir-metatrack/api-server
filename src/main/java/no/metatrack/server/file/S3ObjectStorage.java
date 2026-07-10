package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@ApplicationScoped
public class S3ObjectStorage implements ObjectStorage {
    @Inject
    S3Client s3Client;

    @Inject
    S3Presigner s3Presigner;

    @ConfigProperty(name = "metatrack.s3.bucket-name")
    String bucketName;

    @Override
    public String presignUpload(String objectKey, Duration expiry) {
        var request = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(PutObjectRequest.builder().bucket(bucketName).key(objectKey).build())
                .build();
        return s3Presigner.presignPutObject(request).url().toString();
    }

    @Override
    public String presignDownload(String objectKey, Duration expiry) {
        var request = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucketName).key(objectKey).build())
                .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public void delete(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
    }
}