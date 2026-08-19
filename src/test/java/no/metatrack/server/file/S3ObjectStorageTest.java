package no.metatrack.server.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}