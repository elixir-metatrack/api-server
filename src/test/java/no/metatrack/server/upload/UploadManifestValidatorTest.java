package no.metatrack.server.upload;

import no.metatrack.server.assay.Assay;
import no.metatrack.server.csv.CSVImportSupport;
import no.metatrack.server.file.File;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.file.UploadStatus;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class UploadManifestValidatorTest {
    private static final Long PROJECT_ID = 41L;
    private static final UUID ASSAY_ID = UUID.randomUUID();
    private static final UUID BATCH_ID = UUID.randomUUID();
    private static final String CHECKSUM = "a".repeat(64);
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    private UploadManifestValidator validator;
    private UploadBatchConfig config;
    private Sample sample;
    private Assay assay;

    @BeforeEach
    void setUp() {
        config = mock(UploadBatchConfig.class);
        when(config.maxManifestBytes()).thenReturn(1024 * 1024L);
        when(config.maxFilesPerBatch()).thenReturn(100);
        when(config.maxFileSizeBytes()).thenReturn(MAX_FILE_SIZE);
        validator = new UploadManifestValidator();
        validator.config = config;
        validator.csvImportSupport = new CSVImportSupport();

        Project project = new Project();
        project.id = PROJECT_ID;
        sample = new Sample();
        sample.id = UUID.randomUUID();
        sample.name = "sample-a";
        sample.project = project;
        sample.assays = new HashSet<>();
        assay = new Assay();
        assay.id = ASSAY_ID;
        assay.name = "assay-a";
        assay.project = project;
        assay.addSample(sample);
    }

    @Test
    void acceptsUnicodeRelativePathWithoutOptionalReadRole() throws IOException {
        String path = "reads/é.fastq";
        UploadManifestValidation validation = validate(csvRow(path, 10, CHECKSUM), sample, assay, Optional.empty());

        assertTrue(validation.isValid());
        assertEquals(path, validation.entries().getFirst().relativePath());
        assertEquals("é.fastq", validation.entries().getFirst().fileName());
        assertEquals(null, validation.entries().getFirst().readRole());
    }

    @Test
    void reportsTraversalDuplicateAndInvalidChecksumRows() throws IOException {
        String csv = header()
                + "../outside.fastq,sample-a," + ASSAY_ID + ",10," + CHECKSUM + "\n"
                + "reads/R1.fastq,sample-a," + ASSAY_ID + ",10,not-a-checksum\n"
                + "reads/R1.fastq,sample-a," + ASSAY_ID + ",10," + CHECKSUM + "\n";

        UploadManifestValidation validation = validate(csv, sample, assay, Optional.empty());

        assertFalse(validation.isValid());
        assertTrue(validation.issues().stream().anyMatch(issue -> issue.row() == 2 && issue.column().equals("relativePath")));
        assertTrue(validation.issues().stream().anyMatch(issue -> issue.row() == 3 && issue.column().equals("sha256")));
        assertTrue(validation.issues().stream().anyMatch(issue -> issue.row() == 4 && issue.column().equals("relativePath")));
    }

    @Test
    void rejectsUnknownSamplesAndUnlinkedAssays() throws IOException {
        UploadManifestValidation unknownSample = validate(
                csvRow("reads/R1.fastq", 10, CHECKSUM), null, assay, Optional.empty());
        Assay unlinkedAssay = new Assay();
        unlinkedAssay.id = ASSAY_ID;
        unlinkedAssay.project = sample.project;
        UploadManifestValidation wrongRelationship = validate(
                csvRow("reads/R1.fastq", 10, CHECKSUM), sample, unlinkedAssay, Optional.empty());

        assertTrue(unknownSample.issues().stream().anyMatch(issue -> issue.column().equals("sampleName")));
        assertTrue(wrongRelationship.issues().stream().anyMatch(issue -> issue.column().equals("assayId")));
    }

    @Test
    void rejectsUnsafeSizeAndMissingRequiredHeaders() throws IOException {
        UploadManifestValidation oversized = validate(csvRow("reads/R1.fastq", MAX_FILE_SIZE + 1, CHECKSUM),
                sample, assay, Optional.empty());
        UploadManifestValidation missingHeader = validate(
                "relativePath,sampleName,assayId,sizeBytes\nreads/R1.fastq,sample-a," + ASSAY_ID + ",10\n",
                sample, assay, Optional.empty());

        assertTrue(oversized.issues().stream().anyMatch(issue -> issue.column().equals("sizeBytes")));
        assertTrue(missingHeader.issues().stream().anyMatch(issue -> issue.message().contains("Required column")));
    }

    @Test
    void acceptsExactlyOneHundredFilesAtFiftyGiBEach() throws IOException {
        StringBuilder csv = new StringBuilder(header());
        for (int index = 0; index < 100; index++) {
            csv.append("reads/").append(index).append(".fastq,sample-a,")
                    .append(ASSAY_ID).append(',').append(MAX_FILE_SIZE).append(',').append(CHECKSUM).append('\n');
        }

        UploadManifestValidation validation = validate(csv.toString(), sample, assay, Optional.empty());

        assertTrue(validation.isValid());
        assertEquals(100, validation.entries().size());
        assertEquals(MAX_FILE_SIZE, validation.entries().getLast().expectedSizeBytes());
    }

    @Test
    void preservesMd5AndReadRoleFromMatchingPendingFile() throws IOException {
        File pending = mock(File.class);
        when(pending.status()).thenReturn(UploadStatus.PENDING);
        when(pending.uploadBatchEntry()).thenReturn(null);
        when(pending.uploadedBy()).thenReturn(null);
        when(pending.readRole()).thenReturn(ReadRole.FORWARD);
        when(pending.md5()).thenReturn("declared-md5");
        when(pending.unencryptedMd5()).thenReturn(null);
        when(pending.objectKey()).thenReturn("existing/object-key");

        UploadManifestValidation validation = validate(
                csvRow("reads/R1.fastq", 10, CHECKSUM), sample, assay, Optional.of(pending));

        assertTrue(validation.isValid());
        assertEquals("declared-md5", validation.entries().getFirst().expectedMd5());
        assertEquals(ReadRole.FORWARD, validation.entries().getFirst().readRole());
    }

    private UploadManifestValidation validate(
            String csv, Sample sampleValue, Assay assayValue, Optional<File> existingFile) throws IOException {
        Path path = temporaryDirectory.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(path, csv);
        FileUpload upload = mock(FileUpload.class);
        when(upload.filePath()).thenReturn(path);

        try (MockedStatic<Sample> samples = mockStatic(Sample.class);
             MockedStatic<Assay> assays = mockStatic(Assay.class);
             MockedStatic<File> files = mockStatic(File.class)) {
            samples.when(() -> Sample.findBySampleNameInProject(anyString(), anyLong()))
                    .thenAnswer(invocation -> sampleValue != null
                            && sampleValue.name.equals(invocation.getArgument(0))
                            ? Optional.of(sampleValue)
                            : Optional.empty());
            assays.when(() -> Assay.findByIdInProjectScope(anyLong(), any(UUID.class)))
                    .thenAnswer(invocation -> assayValue != null
                            && assayValue.id.equals(invocation.getArgument(1))
                            ? Optional.of(assayValue)
                            : Optional.empty());
            files.when(() -> File.findByVirtualPathOptional(anyString())).thenReturn(existingFile);
            files.when(() -> File.validateImportPending(
                    anyLong(), any(UUID.class), any(Sample.class), any(Assay.class), anyString(),
                    nullable(String.class), nullable(String.class), nullable(ReadRole.class)))
                    .thenReturn(Optional.empty());
            return validator.validate(upload, PROJECT_ID, BATCH_ID);
        }
    }

    private String header() {
        return "relativePath,sampleName,assayId,sizeBytes,sha256\n";
    }

    private String csvRow(String path, long size, String checksum) {
        return header() + path + ",sample-a," + ASSAY_ID + "," + size + "," + checksum + "\n";
    }
}