package no.metatrack.server.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ObjectStoragePurgeTest {
    private final S3Client client = mock(S3Client.class);
    private final S3ObjectStorage storage = new S3ObjectStorage();
    private final ListObjectVersionsResponse empty = ListObjectVersionsResponse.builder().build();

    @BeforeEach
    void setUp() {
        storage.s3Client = client;
        storage.bucketName = "test-bucket";
    }

    @Test
    void collectsAllPagesBeforeDeletingOnlyExactVersionsAndMarkers() {
        var first = ListObjectVersionsResponse.builder().isTruncated(true)
                .nextKeyMarker("file").nextVersionIdMarker("v1")
                .versions(ObjectVersion.builder().key("file").versionId("v1").build(),
                        ObjectVersion.builder().key("file-neighbor").versionId("keep").build()).build();
        var second = ListObjectVersionsResponse.builder()
                .versions(ObjectVersion.builder().key("file").versionId("null").build())
                .deleteMarkers(DeleteMarkerEntry.builder().key("file").versionId("marker").build(),
                        DeleteMarkerEntry.builder().key("file-neighbor").versionId("keep-marker").build()).build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(first, second, empty);

        storage.purgeAllVersions("file");

        var order = inOrder(client);
        order.verify(client).listObjectVersions(ListObjectVersionsRequest.builder().bucket("test-bucket").prefix("file").build());
        order.verify(client).listObjectVersions(ListObjectVersionsRequest.builder().bucket("test-bucket").prefix("file")
                .keyMarker("file").versionIdMarker("v1").build());
        for (String id : new String[]{"v1", "null", "marker"}) {
            order.verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-bucket").key("file").versionId(id).build());
        }
        order.verify(client).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(client, times(3)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void absentOrUnversionedObjectUsesOrdinaryDeleteAndVerification() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(empty);
        storage.purgeAllVersions("file");
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-bucket").key("file").build());
        verify(client, times(2)).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @Test
    void removesMarkerCreatedByOrdinaryDelete() {
        var marker = ListObjectVersionsResponse.builder().deleteMarkers(
                DeleteMarkerEntry.builder().key("file").versionId("marker").build()).build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(empty, marker, empty);
        storage.purgeAllVersions("file");
        verify(client).deleteObject(DeleteObjectRequest.builder().bucket("test-bucket").key("file").versionId("marker").build());
    }

    @Test
    void unsupportedVersionApisFailWithoutFallback() {
        var failure = S3Exception.builder().statusCode(501).message("unsupported").build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenThrow(failure);
        assertSame(failure, assertThrows(S3Exception.class, () -> storage.purgeAllVersions("file")));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void accessOrRetentionFailurePropagatesAndRetryCanFinishPartialPurge() {
        var versions = ListObjectVersionsResponse.builder().versions(
                ObjectVersion.builder().key("file").versionId("v1").build(),
                ObjectVersion.builder().key("file").versionId("v2").build()).build();
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(versions, versions, empty);
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(403).build())
                .thenReturn(DeleteObjectResponse.builder().build());
        assertThrows(S3Exception.class, () -> storage.purgeAllVersions("file"));
        assertDoesNotThrow(() -> storage.purgeAllVersions("file"));
    }

    @Test
    void concurrentWritesAreBounded() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder().versions(ObjectVersion.builder().key("file").versionId("v1").build()).build());
        assertThrows(IllegalStateException.class, () -> storage.purgeAllVersions("file"));
        verify(client, times(5)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void malformedPaginationFailsClosed() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder().isTruncated(true).build());
        assertThrows(IllegalStateException.class, () -> storage.purgeAllVersions("file"));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}