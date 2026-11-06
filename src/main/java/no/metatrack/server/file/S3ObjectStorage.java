package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class S3ObjectStorage implements ObjectStorage {
    private static final int MAX_PURGE_PASSES = 5;
    private static final long SINGLE_COPY_LIMIT_BYTES = ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES;
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
        return objectIdentity(objectKey).isPresent();
    }

    @Override
    public Optional<StorageObjectIdentity> objectIdentity(String objectKey) {
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(objectKey).build());
            return Optional.of(new StorageObjectIdentity(
                    Objects.requireNonNullElse(response.contentLength(), 0L),
                    response.eTag(),
                    response.versionId(),
                    response.lastModified(),
                    response.metadata()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    @Override
    public InputStream openObject(String objectKey, StorageObjectIdentity identity) {
        var request = GetObjectRequest.builder().bucket(bucketName).key(objectKey);
        if (identity.versionId() != null && !identity.versionId().isBlank()) {
            request.versionId(identity.versionId());
        } else if (identity.eTag() != null && !identity.eTag().isBlank()) {
            request.ifMatch(identity.eTag());
        }
        return s3Client.getObject(request.build());
    }

    @Override
    public StorageObjectIdentity copyObject(
            String sourceKey, String destinationKey, StorageObjectIdentity source, Map<String, String> metadata) {
        if (source.size() > SINGLE_COPY_LIMIT_BYTES) {
            throw new IllegalArgumentException("Objects larger than 5 GiB require multipart copy");
        }
        var request = CopyObjectRequest.builder()
                .bucket(bucketName)
                .key(destinationKey)
                .copySource(encodedCopySource(sourceKey, source.versionId()))
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(metadata);
        addSourcePrecondition(request, source);
        s3Client.copyObject(request.build());
        return objectIdentity(destinationKey)
                .orElseThrow(() -> new IllegalStateException("Copied S3 object is not visible"));
    }

    @Override
    public String initiateMultipartCopy(String destinationKey, Map<String, String> metadata) {
        return s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(destinationKey)
                        .metadata(metadata)
                        .build())
                .uploadId();
    }

    @Override
    public List<StorageObjectCopyPart> listMultipartCopyParts(String destinationKey, String uploadId) {
        List<StorageObjectCopyPart> parts = new ArrayList<>();
        Integer partNumberMarker = null;
        do {
            var response = s3Client.listParts(ListPartsRequest.builder()
                    .bucket(bucketName)
                    .key(destinationKey)
                    .uploadId(uploadId)
                    .partNumberMarker(partNumberMarker)
                    .build());
            for (var part : response.parts()) {
                parts.add(new StorageObjectCopyPart(part.partNumber(), part.eTag()));
            }
            if (!Boolean.TRUE.equals(response.isTruncated())) return List.copyOf(parts);
            Integer nextMarker = response.nextPartNumberMarker();
            if (nextMarker == null || nextMarker.equals(partNumberMarker)) {
                throw new IllegalStateException("S3 multipart parts listing did not advance");
            }
            partNumberMarker = nextMarker;
        } while (true);
    }

    @Override
    public StorageObjectCopyPart copyObjectPart(
            String sourceKey,
            String destinationKey,
            String uploadId,
            StorageObjectIdentity source,
            int partNumber,
            long firstByte,
            long lastByte) {
        var request = UploadPartCopyRequest.builder()
                .bucket(bucketName)
                .key(destinationKey)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .copySource(encodedCopySource(sourceKey, source.versionId()))
                .copySourceRange("bytes=" + firstByte + "-" + lastByte);
        addSourcePrecondition(request, source);
        var response = s3Client.uploadPartCopy(request.build());
        return new StorageObjectCopyPart(partNumber, response.copyPartResult().eTag());
    }

    @Override
    public StorageObjectIdentity completeMultipartCopy(
            String destinationKey, String uploadId, List<StorageObjectCopyPart> parts) {
        List<CompletedPart> completedParts = parts.stream()
                .sorted(Comparator.comparingInt(StorageObjectCopyPart::partNumber))
                .map(part -> CompletedPart.builder().partNumber(part.partNumber()).eTag(part.eTag()).build())
                .toList();
        var completedUpload = CompletedMultipartUpload.builder().parts(completedParts).build();
        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucketName)
                .key(destinationKey)
                .uploadId(uploadId)
                .multipartUpload(completedUpload)
                .build());
        return objectIdentity(destinationKey)
                .orElseThrow(() -> new IllegalStateException("Completed S3 object is not visible"));
    }

    @Override
    public void abortMultipartCopy(String destinationKey, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucketName).key(destinationKey).uploadId(uploadId).build());
        } catch (S3Exception exception) {
            if (!"NoSuchUpload".equals(exception.awsErrorDetails() == null
                    ? null
                    : exception.awsErrorDetails().errorCode())) {
                throw exception;
            }
        }
    }

    @Override
    public List<StorageMultipartUpload> listMultipartUploads(String prefix) {
        List<StorageMultipartUpload> uploads = listMultipartUploadsPagewise(prefix);
        if (!uploads.isEmpty() || prefix == null || prefix.isEmpty()) return uploads;
        return listMultipartUploadsPagewise(null).stream()
                .filter(upload -> upload.objectKey() != null && upload.objectKey().startsWith(prefix))
                .toList();
    }

    private List<StorageMultipartUpload> listMultipartUploadsPagewise(String prefix) {
        List<StorageMultipartUpload> uploads = new ArrayList<>();
        String keyMarker = null;
        String uploadIdMarker = null;
        do {
            var request = ListMultipartUploadsRequest.builder().bucket(bucketName);
            if (prefix != null && !prefix.isEmpty()) request.prefix(prefix);
            request.keyMarker(keyMarker).uploadIdMarker(uploadIdMarker);
            var response = s3Client.listMultipartUploads(request.build());
            response.uploads().forEach(upload -> uploads.add(
                    new StorageMultipartUpload(upload.key(), upload.uploadId())));
            if (!Boolean.TRUE.equals(response.isTruncated())) return List.copyOf(uploads);
            if ((response.nextKeyMarker() == null && response.nextUploadIdMarker() == null)
                    || (Objects.equals(keyMarker, response.nextKeyMarker())
                    && Objects.equals(uploadIdMarker, response.nextUploadIdMarker()))) {
                throw new IllegalStateException("S3 multipart uploads listing did not advance");
            }
            keyMarker = response.nextKeyMarker();
            uploadIdMarker = response.nextUploadIdMarker();
        } while (true);
    }

    private String encodedCopySource(String objectKey, String versionId) {
        String encodedKey = Arrays.stream(objectKey.split("/", -1))
                .map(this::encodePathSegment)
                .collect(Collectors.joining("/"));
        String source = bucketName + "/" + encodedKey;
        if (versionId == null || versionId.isBlank()) return source;
        return source + "?versionId=" + encodePathSegment(versionId);
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void addSourcePrecondition(CopyObjectRequest.Builder request, StorageObjectIdentity source) {
        if ((source.versionId() == null || source.versionId().isBlank())
                && source.eTag() != null && !source.eTag().isBlank()) {
            request.copySourceIfMatch(source.eTag());
        } else if ((source.versionId() == null || source.versionId().isBlank())
                && (source.eTag() == null || source.eTag().isBlank())) {
            throw new IllegalArgumentException("S3 source has no stable version or ETag identity");
        }
    }

    private void addSourcePrecondition(UploadPartCopyRequest.Builder request, StorageObjectIdentity source) {
        if ((source.versionId() == null || source.versionId().isBlank())
                && source.eTag() != null && !source.eTag().isBlank()) {
            request.copySourceIfMatch(source.eTag());
        } else if ((source.versionId() == null || source.versionId().isBlank())
                && (source.eTag() == null || source.eTag().isBlank())) {
            throw new IllegalArgumentException("S3 source has no stable version or ETag identity");
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