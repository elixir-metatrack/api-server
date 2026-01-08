package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.file.File;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"name", "project_id"}))
public class Sample extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    public String name;

    public String alias;

    @Column(nullable = false)
    public int taxId;

    public int hostTaxId;

    public String mlst;

    public String isolationSource;

    public LocalDate collectionDate;

    public String location; // consider using location4j here!!

    public String sequencingLab;

    public String institution;

    public String hostHealthState;

    public Instant createdOn;

    public Instant modifiedOn;

    @ManyToOne(fetch = FetchType.LAZY)
    public Project project;

    @OneToMany(mappedBy = "sample", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<File> files = new HashSet<>();

    public static boolean sampleExists(Long sampleId) {
        return findByIdOptional(sampleId).isPresent();
    }

    public static Optional<Sample> findSampleById(UUID sampleId) {
        return findByIdOptional(sampleId);
    }

    public static List<Sample> getAllSamplesInProject(Long projectId) {
        return list("project.id = ?1", projectId);
    }

    public static Optional<Sample> findBySampleNameInProject(String name, Long projectId) {
        return find("project.id = ?1 and name = ?2", projectId, name).firstResultOptional();
    }
}
