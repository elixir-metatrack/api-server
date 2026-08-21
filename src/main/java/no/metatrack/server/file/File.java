package no.metatrack.server.file;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.sample.Sample;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Entity
public class File extends PanacheEntity {
    @Column(unique = true, nullable = false)
    UUID uuid;

    @Column(nullable = false)
    String fileName;

    String md5;

    String unencryptedMd5;

    @Column(unique = true, nullable = false)
    String virtualPath;

    @Column(unique = true, nullable = false)
    String objectKey;

    @Enumerated(EnumType.STRING)
    UploadStatus status;

    UUID uploadedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    Sample sample;

    @ManyToOne(fetch = FetchType.LAZY)
    Assay assay;

    public static List<File> findInSample(Long projectId, UUID sampleId) {
        return list("sample.id = ?1 and sample.project.id = ?2 order by fileName", sampleId, projectId);
    }

    public static List<File> findInAssay(Long projectId, UUID assayId) {
        return list("assay.id = ?1 and assay.project.id = ?2 order by fileName", assayId, projectId);
    }

    public static List<File> findInSampleAndAssay(Long projectId, UUID sampleId, UUID assayId) {
        return list("sample.id = ?1 and assay.id = ?2 and sample.project.id = ?3 and assay.project.id = ?3 order by fileName",
                sampleId, assayId, projectId);
    }

    public static List<String> findUploadedObjectKeys() {
        return find("select distinct f.objectKey from File f where f.status = ?1", UploadStatus.UPLOADED)
                .project(String.class)
                .list();
    }

    public static List<String> findUploadedObjectKeysInProject(Long projectId) {
        return find("select distinct f.objectKey from File f where f.status = ?1 " +
                        "and (f.sample.project.id = ?2 or f.assay.project.id = ?2)",
                UploadStatus.UPLOADED, projectId)
                .project(String.class)
                .list();
    }

    public static Optional<File> findByObjectKeyOptional(String objectKey) {
        File file = File.find("objectKey", objectKey).firstResult();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    public static Optional<File> findByVirtualPathOptional(String virtualPath) {
        File file = File.find("virtualPath", virtualPath).firstResult();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    public static Optional<File> findByUuidOptional(UUID uuid) {
        File file = File.find("uuid", uuid).firstResult();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    public static Optional<String> validateImportPending(
            Long projectId, UUID assayId, Sample sample, Assay assay, String fileName, String md5,
            String unencryptedMd5) {
        String virtualPath = PresignUrlService.virtualPath(projectId, assayId, sample.name, fileName);
        Optional<File> existing = findByVirtualPathOptional(virtualPath);
        if (existing.isPresent()) {
            File file = existing.get();
            if (file.status != UploadStatus.PENDING || file.sample != sample || file.assay != assay
                    || !java.util.Objects.equals(file.md5, md5)
                    || !java.util.Objects.equals(file.unencryptedMd5, unencryptedMd5)) {
                return Optional.of("Conflicting file metadata for '" + fileName + "'");
            }
        }
        return Optional.empty();
    }

    public static Optional<String> importPending(
            Long projectId, UUID assayId, Sample sample, Assay assay, String fileName, String md5,
            String unencryptedMd5) {
        Optional<String> validationError = validateImportPending(
                projectId, assayId, sample, assay, fileName, md5, unencryptedMd5);
        if (validationError.isPresent()) return validationError;

        String virtualPath = PresignUrlService.virtualPath(projectId, assayId, sample.name, fileName);
        if (findByVirtualPathOptional(virtualPath).isPresent()) return Optional.empty();

        File file = new File();
        file.uuid = UUID.randomUUID();
        file.fileName = fileName;
        file.md5 = md5;
        file.unencryptedMd5 = unencryptedMd5;
        file.virtualPath = virtualPath;
        file.objectKey = PresignUrlService.uploadObjectKey(virtualPath);
        file.status = UploadStatus.PENDING;
        file.sample = sample;
        file.assay = assay;
        sample.files.add(file);
        return Optional.empty();
    }
}
