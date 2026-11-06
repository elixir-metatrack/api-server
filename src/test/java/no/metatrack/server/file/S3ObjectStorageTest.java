package no.metatrack.server.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CopyPartResult;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectStorageTest {
    @Mock
    S3Client s3Client;

    @Mock
    ListObjectsV2Iterable pages;

    S3ObjectStorage objectStorage;

    @BeforeEach
    void setUp() {
        objectStorage = new S3ObjectStorage();
        objectStorage.s3Client = s3Client;
        objectStorage.bucketName = "test-bucket";
    }

    @Test
    void forwardsBucketAndPrefix() {
        when(s3Client.listObjectsV2Paginator(org.mockito.ArgumentMatchers.any(ListObjectsV2Request.class)))
                .thenReturn(pages);
        when(pages.iterator()).thenReturn(List.<ListObjectsV2Response>of().iterator());

        objectStorage.listObjects("42/");

        var requestCaptor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client).listObjectsV2Paginator(requestCaptor.capture());
        assertEquals("test-bucket", requestCaptor.getValue().bucket());
        assertEquals("42/", requestCaptor.getValue().prefix());
    }

    @Test
    void aggregatesObjectsAcrossPages() {
        var firstPage = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("42/first").size(10L).build())
                .build();
        var secondPage = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("42/second").size(20L).build())
                .build();
        when(s3Client.listObjectsV2Paginator(org.mockito.ArgumentMatchers.any(ListObjectsV2Request.class)))
                .thenReturn(pages);
        when(pages.iterator()).thenReturn(List.of(firstPage, secondPage).iterator());

        var result = objectStorage.listObjects("42/");

        assertEquals(List.of(
                new StorageObjectMetadata("42/first", 10L),
                new StorageObjectMetadata("42/second", 20L)), result);
    }

    @Test
    void returnsEmptyListForEmptyListing() {
        when(s3Client.listObjectsV2Paginator(org.mockito.ArgumentMatchers.any(ListObjectsV2Request.class)))
                .thenReturn(pages);
        when(pages.iterator()).thenReturn(List.<ListObjectsV2Response>of().iterator());

        assertEquals(List.of(), objectStorage.listObjects(null));
    }

    @Test
    void obtainsStableObjectIdentityFromHeadResponse() {
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(123L)
                .eTag("\"source-etag\"")
                .versionId("version-1")
                .lastModified(Instant.parse("2026-01-01T00:00:00Z"))
                .metadata(Map.of("batch-id", "batch-1"))
                .build());

        StorageObjectIdentity identity = objectStorage.objectIdentity("staging/object.fastq").orElseThrow();

        assertEquals(123, identity.size());
        assertEquals("\"source-etag\"", identity.eTag());
        assertEquals("version-1", identity.versionId());
        assertEquals("batch-1", identity.metadata().get("batch-id"));
    }

    @Test
    void copiesSmallObjectsWithSourcePreconditionAndPromotionMetadata() {
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(123L)
                .eTag("\"source-etag\"")
                .metadata(Map.of("batch-id", "batch-1"))
                .build());
        StorageObjectIdentity source = new StorageObjectIdentity(123, "\"source-etag\"", null,
                Instant.now(), Map.of());

        StorageObjectIdentity destination = objectStorage.copyObject(
                "staging/batch-1/reads.fastq", "42/final-uuid", source,
                Map.of("batch-id", "batch-1", "entry-id", "entry-1"));

        var request = org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(request.capture());
        assertEquals("test-bucket/staging/batch-1/reads.fastq", request.getValue().copySource());
        assertEquals("\"source-etag\"", request.getValue().copySourceIfMatch());
        assertEquals(Map.of("batch-id", "batch-1", "entry-id", "entry-1"), request.getValue().metadata());
        assertEquals(123, destination.size());
    }

    @Test
    void rejectsSingleCopyAboveFiveGibibytes() {
        StorageObjectIdentity source = new StorageObjectIdentity(
                ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES + 1, "etag", null, Instant.now(), Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> objectStorage.copyObject("staging/large", "42/final", source, Map.of()));
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void copiesMultipartPartWithStableSourceIdentityAndCompletesInPartOrder() {
        when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        when(s3Client.uploadPartCopy(any(UploadPartCopyRequest.class))).thenReturn(
                UploadPartCopyResponse.builder()
                        .copyPartResult(CopyPartResult.builder().eTag("part-etag").build())
                        .build());
        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(12L)
                .eTag("destination-etag")
                .metadata(Map.of("batch-id", "batch-1"))
                .build());
        StorageObjectIdentity source = new StorageObjectIdentity(12, "source-etag", null, Instant.now(), Map.of());

        String uploadId = objectStorage.initiateMultipartCopy("42/final", Map.of("batch-id", "batch-1"));
        StorageObjectCopyPart part = objectStorage.copyObjectPart(
                "staging/source", "42/final", uploadId, source, 1, 0, 11);
        StorageObjectIdentity destination = objectStorage.completeMultipartCopy(
                "42/final", uploadId, List.of(part));

        var request = org.mockito.ArgumentCaptor.forClass(UploadPartCopyRequest.class);
        verify(s3Client).uploadPartCopy(request.capture());
        assertEquals("bytes=0-11", request.getValue().copySourceRange());
        assertEquals("source-etag", request.getValue().copySourceIfMatch());
        assertEquals(12, destination.size());
    }
}