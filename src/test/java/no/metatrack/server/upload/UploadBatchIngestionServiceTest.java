package no.metatrack.server.upload;

import no.metatrack.server.file.FileIngestService;
import no.metatrack.server.file.ObjectStorage;
import no.metatrack.server.file.StorageObjectCopyPart;
import no.metatrack.server.file.StorageObjectIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadBatchIngestionServiceTest {
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final UUID ENTRY_ID = UUID.randomUUID();
    private static final UUID LEASE_TOKEN = UUID.randomUUID();
    private static final String STAGING_KEY = "staging/" + BATCH_ID + "/reads.fastq";
    private static final String DESTINATION_KEY = "42/assay/sample/reads.fastq/physical-key";
    private static final byte[] CONTENT = "fastq-content".getBytes(StandardCharsets.UTF_8);

    private ObjectStorage objectStorage;
    private UploadBatchIngestionStateService stateService;
    private FileIngestService fileIngestService;
    private UploadBatchIngestionService ingestionService;
    private StorageObjectIdentity sourceIdentity;
    private StorageObjectIdentity destinationIdentity;

    @BeforeEach
    void setUp() throws Exception {
        objectStorage = mock(ObjectStorage.class);
        stateService = mock(UploadBatchIngestionStateService.class);
        fileIngestService = mock(FileIngestService.class);
        UploadBatchConfig config = mock(UploadBatchConfig.class);
        when(config.multipartCopyPartSizeBytes()).thenReturn(16L * 1024 * 1024);
        ingestionService = new UploadBatchIngestionService();
        ingestionService.objectStorage = objectStorage;
        ingestionService.stateService = stateService;
        ingestionService.fileIngestService = fileIngestService;
        ingestionService.config = config;

        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        sourceIdentity = new StorageObjectIdentity(CONTENT.length, "source-etag", null,
                Instant.parse("2026-01-01T00:00:00Z"), Map.of());
        destinationIdentity = new StorageObjectIdentity(CONTENT.length, "destination-etag", null,
                Instant.parse("2026-01-01T00:01:00Z"), Map.of(
                "metatrack-batch-id", BATCH_ID.toString(),
                "metatrack-entry-id", ENTRY_ID.toString(),
                "metatrack-sha256", sha256,
                "metatrack-size", Long.toString(CONTENT.length),
                "metatrack-source-etag", "source-etag"));
        when(objectStorage.objectIdentity(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals(STAGING_KEY)
                        ? Optional.of(sourceIdentity)
                        : Optional.empty());
        when(objectStorage.openObject(STAGING_KEY, sourceIdentity))
                .thenReturn(new ByteArrayInputStream(CONTENT));
        when(stateService.recordValidatedSource(ENTRY_ID, LEASE_TOKEN, sourceIdentity)).thenReturn(true);
        when(stateService.recordDestinationIdentity(ENTRY_ID, LEASE_TOKEN, destinationIdentity)).thenReturn(true);
        when(objectStorage.copyObject(anyString(), anyString(), any(StorageObjectIdentity.class), any()))
                .thenReturn(destinationIdentity);
        when(fileIngestService.commitBatchUpload(
                any(UUID.class), any(UUID.class), anyLong(), anyString(), any(StorageObjectIdentity.class)))
                .thenReturn(true);
    }

    @Test
    void verifiesAndPromotesWithStableSourceIdentity() throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        UploadBatchEntryClaim claim = claim(sha256, null);

        ingestionService.process(claim);

        verify(objectStorage).copyObject(
                STAGING_KEY,
                DESTINATION_KEY,
                sourceIdentity,
                Map.of("metatrack-batch-id", BATCH_ID.toString(),
                        "metatrack-entry-id", ENTRY_ID.toString(),
                        "metatrack-sha256", sha256,
                        "metatrack-size", Long.toString(CONTENT.length),
                        "metatrack-source-etag", "source-etag"));
        verify(fileIngestService).commitBatchUpload(
                ENTRY_ID, LEASE_TOKEN, CONTENT.length, sha256, destinationIdentity);
        verify(stateService, never()).markFailed(any(), any(), anyString());
    }

    @Test
    void rejectsHashMismatchWithoutPromoting() {
        ingestionService.process(claim("0".repeat(64), null));

        verify(stateService).markFailed(ENTRY_ID, LEASE_TOKEN,
                "SHA-256 checksum does not match the manifest");
        verify(objectStorage, never()).copyObject(
                anyString(), anyString(), any(StorageObjectIdentity.class), any());
        verify(fileIngestService, never()).commitBatchUpload(
                any(UUID.class), any(UUID.class), anyLong(), anyString(), any(StorageObjectIdentity.class));
    }

    @Test
    void rejectsDeclaredPendingMd5MismatchWithoutPromoting() throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        ingestionService.process(claim(sha256, "0".repeat(32)));

        verify(stateService).markFailed(ENTRY_ID, LEASE_TOKEN,
                "Declared MD5 checksum does not match the pending file metadata");
        verify(objectStorage, never()).copyObject(
                anyString(), anyString(), any(StorageObjectIdentity.class), any());
    }

    @Test
    void verifiesDeclaredMd5AndUnencryptedMd5InTheSameStream() throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(CONTENT));

        ingestionService.process(claim(sha256, md5, md5));

        verify(fileIngestService).commitBatchUpload(
                ENTRY_ID, LEASE_TOKEN, CONTENT.length, sha256, destinationIdentity);
    }

    @Test
    void recoversCompletedPromotionWithoutRepeatingTheCopy() throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        when(objectStorage.objectIdentity(DESTINATION_KEY)).thenReturn(Optional.of(destinationIdentity));

        ingestionService.process(claim(sha256, null));

        verify(objectStorage, never()).copyObject(
                anyString(), anyString(), any(StorageObjectIdentity.class), any());
        verify(fileIngestService).commitBatchUpload(
                ENTRY_ID, LEASE_TOKEN, CONTENT.length, sha256, destinationIdentity);
    }

    @Test
    void rejectsSourceMutationBetweenHashingAndPromotion() {
        StorageObjectIdentity changed = new StorageObjectIdentity(CONTENT.length, "changed-etag", null,
                Instant.parse("2026-01-01T00:02:00Z"), Map.of());
        when(objectStorage.objectIdentity(STAGING_KEY))
                .thenReturn(Optional.of(sourceIdentity), Optional.of(changed));
        String sha256;
        try {
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        ingestionService.process(claim(sha256, null));

        verify(stateService).markFailed(ENTRY_ID, LEASE_TOKEN,
                "S3 object identity changed during verification");
        verify(objectStorage, never()).copyObject(
                anyString(), anyString(), any(StorageObjectIdentity.class), any());
    }

    @Test
    void switchesToMultipartCopyOnlyAboveFiveGigabytes() {
        long threshold = ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES;

        assertFalse(UploadBatchIngestionService.requiresMultipartCopy(threshold));
        assertTrue(UploadBatchIngestionService.requiresMultipartCopy(threshold + 1));
    }

    @Test
    void resumesMultipartPromotionFromPartsAlreadyPresentInS3() {
        long firstPartSize = ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES;
        StorageObjectIdentity largeSource = new StorageObjectIdentity(
                firstPartSize + 1, "large-source-etag", null, Instant.now(), Map.of());
        UploadBatchEntryClaim claim = new UploadBatchEntryClaim(
                ENTRY_ID, BATCH_ID, UUID.randomUUID(), 42L, "large.fastq", STAGING_KEY, DESTINATION_KEY,
                firstPartSize + 1, "a".repeat(64), null, null, "large-source-etag", null,
                "existing-upload-id", firstPartSize, 2, LEASE_TOKEN);
        StorageObjectCopyPart firstPart = new StorageObjectCopyPart(1, "part-1-etag");
        StorageObjectCopyPart secondPart = new StorageObjectCopyPart(2, "part-2-etag");
        when(stateService.renewLease(eq(ENTRY_ID), eq(LEASE_TOKEN), anyLong())).thenReturn(true);
        when(objectStorage.listMultipartCopyParts(DESTINATION_KEY, "existing-upload-id"))
                .thenReturn(List.of(firstPart));
        when(objectStorage.copyObjectPart(
                STAGING_KEY, DESTINATION_KEY, "existing-upload-id", largeSource,
                2, firstPartSize, firstPartSize)).thenReturn(secondPart);
        when(objectStorage.completeMultipartCopy(
                eq(DESTINATION_KEY), eq("existing-upload-id"), eq(List.of(firstPart, secondPart))))
                .thenReturn(destinationIdentity);

        StorageObjectIdentity promoted = ingestionService.multipartCopy(claim, largeSource, Map.of("entry", "id"));

        assertEquals(destinationIdentity, promoted);
        verify(objectStorage).copyObjectPart(
                STAGING_KEY, DESTINATION_KEY, "existing-upload-id", largeSource,
                2, firstPartSize, firstPartSize);
        verify(stateService, never()).recordMultipartUpload(any(), any(), anyString(), anyLong());
    }

    @Test
    void leavesPersistedMultipartUploadIntactWhenLeaseIsLostAfterCopy() {
        long partSize = ObjectStorage.MULTIPART_COPY_THRESHOLD_BYTES;
        StorageObjectIdentity largeSource = new StorageObjectIdentity(
                partSize + 1, "large-source-etag", null, Instant.now(), Map.of());
        UploadBatchEntryClaim claim = new UploadBatchEntryClaim(
                ENTRY_ID, BATCH_ID, UUID.randomUUID(), 42L, "large.fastq", STAGING_KEY, DESTINATION_KEY,
                partSize + 1, "a".repeat(64), null, null, "large-source-etag", null,
                "existing-upload-id", partSize, 2, LEASE_TOKEN);
        StorageObjectCopyPart copiedPart = new StorageObjectCopyPart(1, "part-1-etag");
        when(stateService.renewLease(eq(ENTRY_ID), eq(LEASE_TOKEN), anyLong())).thenReturn(true, false);
        when(objectStorage.listMultipartCopyParts(DESTINATION_KEY, "existing-upload-id")).thenReturn(List.of());
        when(objectStorage.copyObjectPart(
                STAGING_KEY, DESTINATION_KEY, "existing-upload-id", largeSource,
                1, 0, partSize - 1)).thenReturn(copiedPart);

        assertThrows(RuntimeException.class,
                () -> ingestionService.multipartCopy(claim, largeSource, Map.of("entry", "id")));

        verify(stateService, times(2)).renewLease(eq(ENTRY_ID), eq(LEASE_TOKEN), anyLong());
        verify(objectStorage).copyObjectPart(
                STAGING_KEY, DESTINATION_KEY, "existing-upload-id", largeSource,
                1, 0, partSize - 1);
        verify(objectStorage, never()).abortMultipartCopy(DESTINATION_KEY, "existing-upload-id");
        verify(objectStorage, never()).completeMultipartCopy(anyString(), anyString(), any());
    }

    private UploadBatchEntryClaim claim(String sha256, String expectedMd5) {
        return claim(sha256, expectedMd5, null);
    }

    private UploadBatchEntryClaim claim(String sha256, String expectedMd5, String expectedUnencryptedMd5) {
        return new UploadBatchEntryClaim(
                ENTRY_ID,
                BATCH_ID,
                UUID.randomUUID(),
                42L,
                "reads.fastq",
                STAGING_KEY,
                DESTINATION_KEY,
                CONTENT.length,
                sha256,
                expectedMd5,
                expectedUnencryptedMd5,
                null,
                null,
                null,
                null,
                1,
                LEASE_TOKEN);
    }
}