package no.metatrack.server.file;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import java.util.Optional;
import java.util.UUID;

@Entity
public class FileEntity extends PanacheEntity {
    @Column(unique = true, nullable = false)
    UUID uuid;

    @Column(nullable = false)
    String fileName;

    @Column(unique = true, nullable = false)
    String virtualPath;

    @Column(unique = true, nullable = false)
    String objectKey;

    UploadStatus status;

    public static Optional<FileEntity> findByObjectKeyOptional(String objectKey) {
        FileEntity file = FileEntity.find("objectKey", objectKey).firstResult();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(file);
    }
}
