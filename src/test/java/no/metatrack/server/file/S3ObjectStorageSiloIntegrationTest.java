package no.metatrack.server.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "metatrack.s3.integration", matches = "true")
class S3ObjectStorageSiloIntegrationTest {
    @Test
    void copiesSmallAndMultipartObjectsAndAbortsAnOrphanedUpload() throws Exception {
        String endpoint = requiredSetting("metatrack.s3.endpoint", "S3_ENDPOINT");
        String bucket = requiredSetting("metatrack.s3.bucket", "S3_BUCKET_NAME");
        String accessKey = requiredSetting("metatrack.s3.access-key", "S3_ACCESS_KEY");
        String secretKey = requiredSetting("metatrack.s3.secret-key", "S3_SECRET_KEY");
        String region = System.getProperty("metatrack.s3.region", System.getenv().getOrDefault("S3_REGION", "us-east-1"));

        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            S3ObjectStorage storage = new S3ObjectStorage();
            storage.s3Client = client;
            storage.bucketName = bucket;
            String prefix = "staging/s3-copy-integration/" + UUID.randomUUID();
            String smallSourceKey = prefix + "/small-source.bin";
            String smallDestinationKey = prefix + "/small-final.bin";
            String largeSourceKey = prefix + "/large-source.bin";
            String largeDestinationKey = prefix + "/large-final.bin";
            String orphanDestinationKey = prefix + "/orphan-final.bin";
            String orphanUploadId = null;

            try {
                byte[] smallContent = "small-copy-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                put(client, bucket, smallSourceKey, smallContent);
                StorageObjectIdentity smallSource = storage.objectIdentity(smallSourceKey).orElseThrow();
                StorageObjectIdentity smallCopy = storage.copyObject(
                        smallSourceKey, smallDestinationKey, smallSource,
                        Map.of("compatibility-test", "small-copy"));
                assertEquals(smallContent.length, smallCopy.size());
                assertEquals("small-copy", smallCopy.metadata().get("compatibility-test"));
                assertEquals(sha256(smallContent), sha256(storage.openObject(smallDestinationKey, smallCopy).readAllBytes()));

                byte[] largeContent = new byte[11 * 1024 * 1024];
                new java.util.Random(37).nextBytes(largeContent);
                put(client, bucket, largeSourceKey, largeContent);
                StorageObjectIdentity largeSource = storage.objectIdentity(largeSourceKey).orElseThrow();
                String createdOrphanUploadId = storage.initiateMultipartCopy(orphanDestinationKey,
                        Map.of("compatibility-test", "orphan"));
                orphanUploadId = createdOrphanUploadId;
                storage.copyObjectPart(largeSourceKey, orphanDestinationKey, orphanUploadId,
                        largeSource, 1, 0, 5L * 1024 * 1024 - 1);
                List<StorageMultipartUpload> listedUploads = storage.listMultipartUploads(prefix);
                List<StorageMultipartUpload> allListedUploads = storage.listMultipartUploads("");
                assertTrue(listedUploads.stream().anyMatch(upload -> createdOrphanUploadId.equals(upload.uploadId())),
                        "Expected upload " + createdOrphanUploadId + " under " + prefix + ", prefix result="
                                + listedUploads + ", unfiltered result=" + allListedUploads);
                storage.abortMultipartCopy(orphanDestinationKey, orphanUploadId);
                orphanUploadId = null;

                String uploadId = storage.initiateMultipartCopy(largeDestinationKey,
                        Map.of("compatibility-test", "multipart-copy"));
                long partSize = 5L * 1024 * 1024;
                List<StorageObjectCopyPart> copiedParts = new ArrayList<>();
                StorageObjectCopyPart firstPart = storage.copyObjectPart(
                        largeSourceKey, largeDestinationKey, uploadId, largeSource,
                        1, 0, partSize - 1);
                copiedParts.add(firstPart);
                List<StorageObjectCopyPart> recoveredParts = storage.listMultipartCopyParts(
                        largeDestinationKey, uploadId);
                assertEquals(List.of(firstPart), recoveredParts);
                copiedParts = new ArrayList<>(recoveredParts);
                long offset = partSize;
                int partNumber = 1;
                while (offset < largeSource.size()) {
                    partNumber++;
                    long lastByte = Math.min(largeSource.size() - 1, offset + partSize - 1);
                    copiedParts.add(storage.copyObjectPart(
                            largeSourceKey, largeDestinationKey, uploadId, largeSource,
                            partNumber, offset, lastByte));
                    offset = lastByte + 1;
                }
                assertEquals(3, storage.listMultipartCopyParts(largeDestinationKey, uploadId).size());
                StorageObjectIdentity largeCopy = storage.completeMultipartCopy(
                        largeDestinationKey, uploadId, copiedParts);
                assertEquals(largeContent.length, largeCopy.size());
                assertEquals("multipart-copy", largeCopy.metadata().get("compatibility-test"));
                assertEquals(sha256(largeContent), sha256(storage.openObject(largeDestinationKey, largeCopy).readAllBytes()));
            } finally {
                if (orphanUploadId != null) storage.abortMultipartCopy(orphanDestinationKey, orphanUploadId);
                storage.delete(smallSourceKey);
                storage.delete(smallDestinationKey);
                storage.delete(largeSourceKey);
                storage.delete(largeDestinationKey);
            }
        }
    }

    private static void put(S3Client client, String bucket, String key, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String requiredSetting(String property, String environment) {
        String value = System.getProperty(property, System.getenv(environment));
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing Silo integration setting " + property);
        return value;
    }
}