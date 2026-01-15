package no.metatrack.server.assay;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"name", "project_id"}))
public class Assay extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public String name;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "assay_sample",
            joinColumns = @JoinColumn(name = "assay_id"),
            inverseJoinColumns = @JoinColumn(name = "sample_id"))
    public Set<Sample> samples = new HashSet<>();

    public String studyAccession;

    public String instrumentModel;

    public String libraryName;

    public String librarySource;

    public String librarySelection;

    public String libraryLayout;

    public Integer insertSize;

    public Instant createdOn;

    public Instant modifiedOn;

    @ManyToOne(fetch = FetchType.LAZY)
    public Project project;

    public static boolean existsAssayByIdInProjectOptional(Long projectId, UUID assayId) {
        return count("id = ?1 and project.id = ?2", assayId, projectId) > 0;
    }

    public void addSample(Sample sample) {
        this.samples.add(sample);
        sample.assays.add(this);
    }

    public void removeSample(Sample sample) {
        this.samples.remove(sample);
        sample.assays.remove(this);
    }

    public static List<Assay> findAssaysInProject(Long projectId) {
        return list("project.id = ?1", projectId);
    }
}
