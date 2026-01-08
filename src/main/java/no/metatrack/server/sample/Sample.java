package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.file.File;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"name", "project_id"}))
public class Sample extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    String name;

    String alias;

    @Column(nullable = false)
    int taxId;

    int hostTaxId;

    String mlst;

    String isolationSource;

    LocalDate collectionDate;

    String location; // consider using location4j here!!

    String sequencingLab;

    String institution;

    String hostHealthState;

    Instant createdOn;

    Instant modifiedOn;

    @ManyToOne(fetch = FetchType.LAZY)
    Project project;

    @OneToMany(mappedBy = "sample", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    Set<File> files = new HashSet<>();

    public static Optional<Sample> findBySampleNameInProject(String name, Long projectId) {
        return find("project.id = ?1 and name = ?2", projectId, name).firstResultOptional();
    }
}
