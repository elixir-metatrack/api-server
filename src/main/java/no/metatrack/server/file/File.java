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
}
