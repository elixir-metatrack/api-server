package no.metatrack.server.file;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import no.metatrack.server.sample.Sample;

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

    UploadStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    Sample sample;

    public static Optional<File> findByObjectKeyOptional(String objectKey) {
        File file = File.find("objectKey", objectKey).firstResult();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(file);
    }
}
