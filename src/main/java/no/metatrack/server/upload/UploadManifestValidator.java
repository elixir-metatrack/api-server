package no.metatrack.server.upload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.csv.CSVImportSupport;
import no.metatrack.server.file.File;
import no.metatrack.server.file.PresignUrlService;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.file.UploadStatus;
import no.metatrack.server.sample.Sample;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class UploadManifestValidator {
    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "relativepath", "samplename", "assayid", "sizebytes", "sha256");
    private static final Set<String> ALLOWED_HEADERS = Set.of(
            "relativepath", "samplename", "assayid", "sizebytes", "sha256", "readrole");

    @Inject
    CSVImportSupport csvImportSupport;

    @Inject
    UploadBatchConfig config;

    public UploadManifestValidation validate(FileUpload upload, Long projectId, UUID batchId) {
        List<UploadManifestIssue> issues = new ArrayList<>();
        if (upload == null) {
            return invalid("", "No manifest uploaded");
        }
        try {
            if (Files.size(upload.filePath()) > config.maxManifestBytes()) {
                return invalid("", "Manifest exceeds the maximum allowed size");
            }
        } catch (IOException exception) {
            return invalid("", "Unable to read manifest");
        }

        ParsedManifest parsed;
        try {
            parsed = parse(upload);
        } catch (IOException | IllegalArgumentException exception) {
            return invalid("", "Manifest is not a valid UTF-8 CSV file");
        }

        Set<String> normalizedHeaders = new HashSet<>();
        for (String header : parsed.headers()) {
            String normalized = csvImportSupport.normalizeHeader(header);
            if (!normalizedHeaders.add(normalized)) {
                issues.add(new UploadManifestIssue(1, header, "Duplicate column"));
            }
            if (!ALLOWED_HEADERS.contains(normalized)) {
                issues.add(new UploadManifestIssue(1, header, "Unexpected column"));
            }
        }
        for (String required : REQUIRED_HEADERS) {
            if (!normalizedHeaders.contains(required)) {
                issues.add(new UploadManifestIssue(1, required, "Required column is missing"));
            }
        }
        if (!issues.isEmpty()) {
            return new UploadManifestValidation(List.of(), issues);
        }
        if (parsed.records().isEmpty()) {
            return invalid("", "Manifest contains no file entries");
        }
        int maxFiles = Math.min(config.maxFilesPerBatch(), UploadBatchLimits.MAX_FILES_PER_BATCH);
        if (parsed.records().size() > maxFiles) {
            issues.add(new UploadManifestIssue(maxFiles + 2, "relativePath",
                    "Batch exceeds the maximum number of files"));
            return new UploadManifestValidation(List.of(), issues);
        }

        List<ValidatedUploadManifestEntry> entries = new ArrayList<>();
        Set<String> relativePaths = new HashSet<>();
        Set<String> destinationPaths = new HashSet<>();
        for (int index = 0; index < parsed.records().size(); index++) {
            CSVRecord record = parsed.records().get(index);
            int row = index + 2;
            String relativePath = normalizeRelativePath(record.get("relativePath"));
            if (relativePath == null) {
                issues.add(new UploadManifestIssue(row, "relativePath", "Path must be relative and contain no empty, '.' or '..' segments"));
                continue;
            }
            if (!relativePaths.add(Normalizer.normalize(relativePath, Normalizer.Form.NFC))) {
                issues.add(new UploadManifestIssue(row, "relativePath", "Duplicate manifest path"));
                continue;
            }
            if (utf8Length("staging/" + batchId + "/" + relativePath) > 1024) {
                issues.add(new UploadManifestIssue(row, "relativePath", "Staging object key exceeds the S3 key limit"));
                continue;
            }

            String sampleName = record.get("sampleName").trim();
            if (sampleName.isBlank()) {
                issues.add(new UploadManifestIssue(row, "sampleName", "Sample name is required"));
                continue;
            }
            Sample sample = Sample.findBySampleNameInProject(sampleName, projectId).orElse(null);
            if (sample == null) {
                issues.add(new UploadManifestIssue(row, "sampleName", "Sample does not exist in this project scope"));
                continue;
            }

            UUID assayId = parseUuid(record.get("assayId"));
            if (assayId == null) {
                issues.add(new UploadManifestIssue(row, "assayId", "Assay ID must be a canonical UUID"));
                continue;
            }
            Assay assay = Assay.findByIdInProjectScope(projectId, assayId).orElse(null);
            if (assay == null || assay.samples.stream().noneMatch(assaySample -> assaySample.id.equals(sample.id))) {
                issues.add(new UploadManifestIssue(row, "assayId", "Assay is not associated with this sample in this project scope"));
                continue;
            }

            Long sizeBytes = parseSize(record.get("sizeBytes"));
            long maxFileSize = Math.min(config.maxFileSizeBytes(), UploadBatchLimits.MAX_FILE_SIZE_BYTES);
            if (sizeBytes == null || sizeBytes > maxFileSize) {
                issues.add(new UploadManifestIssue(row, "sizeBytes", "Byte size is invalid or exceeds the per-file limit"));
                continue;
            }
            String sha256 = record.get("sha256").trim();
            if (!sha256.matches("[0-9A-Fa-f]{64}")) {
                issues.add(new UploadManifestIssue(row, "sha256", "SHA-256 must contain exactly 64 hexadecimal characters"));
                continue;
            }
            String readRoleValue = record.isMapped("readRole") ? record.get("readRole") : null;
            ReadRole requestedReadRole = parseReadRole(readRoleValue);
            if (readRoleValue != null && !readRoleValue.isBlank() && requestedReadRole == null) {
                issues.add(new UploadManifestIssue(row, "readRole", "Read role is invalid"));
                continue;
            }

            String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
            if (fileName.length() > 255) {
                issues.add(new UploadManifestIssue(row, "relativePath", "File name exceeds the API limit"));
                continue;
            }
            String virtualPath = PresignUrlService.virtualPath(sample.project.id, assay.id, sample.name, fileName);
            if (virtualPath.length() > 255) {
                issues.add(new UploadManifestIssue(row, "relativePath", "Logical file path exceeds the API limit"));
                continue;
            }
            if (!destinationPaths.add(virtualPath)) {
                issues.add(new UploadManifestIssue(row, "relativePath", "Multiple entries target the same sample/assay file"));
                continue;
            }

            File existing = File.findByVirtualPathOptional(virtualPath).orElse(null);
            ReadRole effectiveReadRole = requestedReadRole;
            String expectedMd5 = null;
            String expectedUnencryptedMd5 = null;
            String previousObjectKey = null;
            if (existing != null) {
                if (existing.status() != UploadStatus.PENDING) {
                    issues.add(new UploadManifestIssue(row, "relativePath", "Destination is already uploaded"));
                    continue;
                }
                if (existing.uploadBatchEntry() != null || existing.uploadedBy() != null) {
                    issues.add(new UploadManifestIssue(row, "relativePath", "Pending destination has an active upload reservation"));
                    continue;
                }
                if (effectiveReadRole == null) effectiveReadRole = existing.readRole();
                Optional<String> pendingError = File.validateImportPending(
                        sample.project.id, assay.id, sample, assay, fileName,
                        existing.md5(), existing.unencryptedMd5(), effectiveReadRole);
                if (pendingError.isPresent()) {
                    issues.add(new UploadManifestIssue(row, "relativePath", pendingError.get()));
                    continue;
                }
                expectedMd5 = existing.md5();
                expectedUnencryptedMd5 = existing.unencryptedMd5();
                previousObjectKey = existing.objectKey();
            }
            String destinationObjectKey = PresignUrlService.uploadObjectKey(virtualPath);
            if (utf8Length(destinationObjectKey) > 1024) {
                issues.add(new UploadManifestIssue(row, "relativePath", "Destination S3 object key exceeds the S3 key limit"));
                continue;
            }

            entries.add(new ValidatedUploadManifestEntry(
                    row, relativePath, sample, assay, fileName, virtualPath, destinationObjectKey,
                    previousObjectKey, sizeBytes,
                    sha256.toLowerCase(Locale.ROOT), expectedMd5, expectedUnencryptedMd5, effectiveReadRole));
        }
        return new UploadManifestValidation(entries, issues);
    }

    private ParsedManifest parse(FileUpload upload) throws IOException {
        char delimiter = csvImportSupport.detectDelimiter(upload.filePath().toFile());
        try (BufferedReader reader = Files.newBufferedReader(upload.filePath(), StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != 0xFEFF) reader.reset();
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setDelimiter(delimiter)
                    .setTrim(true)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                    .get();
            try (CSVParser parser = format.parse(reader)) {
                return new ParsedManifest(List.copyOf(parser.getHeaderNames()), parser.getRecords());
            }
        }
    }

    private String normalizeRelativePath(String input) {
        if (input == null || input.isBlank()) return null;
        String path = input;
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("\\") || path.indexOf('\0') >= 0) {
            return null;
        }
        for (int index = 0; index < path.length(); index++) {
            if (Character.isISOControl(path.charAt(index))) return null;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith(".sftpgo-upload")) {
                return null;
            }
        }
        return path;
    }

    private UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            UUID parsed = UUID.fromString(value.trim());
            return parsed.toString().equalsIgnoreCase(value.trim()) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Long parseSize(String value) {
        if (value == null || !value.matches("[0-9]+")) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ReadRole parseReadRole(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ReadRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private UploadManifestValidation invalid(String column, String message) {
        return new UploadManifestValidation(List.of(), List.of(new UploadManifestIssue(1, column, message)));
    }

    private record ParsedManifest(List<String> headers, List<CSVRecord> records) {
    }
}