package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class S3ObjectStorage implements ObjectStorage {
    private static final int MAX_PURGE_PASSES = 5;
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
    public List<StorageObjectMetadata> listObjects(String prefix) {
        var request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build();
        var objects = new ArrayList<StorageObjectMetadata>();
        for (var page : s3Client.listObjectsV2Paginator(request)) {
            for (var object : page.contents()) {
                objects.add(new StorageObjectMetadata(object.key(), object.size()));
            }
        }
        return List.copyOf(objects);
    }

    @Override
    public void delete(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
    }

    @Override
    public void purgeAllVersions(String objectKey) {
        var versions = listExactVersions(objectKey);
        if (versions.isEmpty()) {
            delete(objectKey);
            versions = listExactVersions(objectKey);
        }
        for (int pass = 0; pass < MAX_PURGE_PASSES; pass++) {
            if (versions.isEmpty()) return;
            for (String versionId : versions) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName).key(objectKey).versionId(versionId).build());
            }
            versions = listExactVersions(objectKey);
        }
        if (!versions.isEmpty()) {
            throw new IllegalStateException("Object version purge did not converge");
        }
    }

    private List<String> listExactVersions(String objectKey) {
        var versions = new ArrayList<String>();
        String keyMarker = null;
        String versionIdMarker = null;
        do {
            var page = s3Client.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucketName).prefix(objectKey)
                    .keyMarker(keyMarker).versionIdMarker(versionIdMarker).build());
            for (var version : page.versions()) {
                if (objectKey.equals(version.key())) versions.add(requireVersionId(version.versionId()));
            }
            for (var marker : page.deleteMarkers()) {
                if (objectKey.equals(marker.key())) versions.add(requireVersionId(marker.versionId()));
            }
            if (!Boolean.TRUE.equals(page.isTruncated())) return versions;
            if (page.nextKeyMarker() == null ||
                    (java.util.Objects.equals(keyMarker, page.nextKeyMarker()) &&
                            java.util.Objects.equals(versionIdMarker, page.nextVersionIdMarker()))) {
                throw new IllegalStateException("Object version listing did not advance");
            }
            keyMarker = page.nextKeyMarker();
            versionIdMarker = page.nextVersionIdMarker();
        } while (true);
    }

    private String requireVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalStateException("Object version listing omitted a version ID");
        }
        return versionId;
    }
}