package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.metadata.SampleMetadataValue;

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

    public Integer taxId;

    public Integer hostTaxId;

    public String mlst;

    public String projectTitle;

    public String description;

    public String isolate;

    public String collectedBy;

    public Double latitude;

    public Double longitude;

    public String environmentalSample;

    public String hostAssociated;

    public String hostCommonName;

    public String hostSubjectId;

    public String collectorName;

    public String collectingInstitution;

    public String hostSex;

    public String influenzaTestMethod;

    public String influenzaTestResult;

    public String otherPathogensTested;

    public String otherPathogensTestResult;

    public String hostHabitat;

    public String isolationSourceHostAssociated;

    public String hostBehaviour;

    public String isolationSourceNonHostAssociated;

    public String influenzaVirusType;

    public String influenzaSubType;

    public String serovar;

    public String strain;

    public String hostAge;

    public String county;

    public String commune;

    public String hospitalHealthInstitution;

    public String isolationSource;

    public LocalDate collectionDate;

    public String location;

    public String sequencingLab;

    public String institution;

    public String hostHealthState;

    public Instant createdOn;

    public Instant modifiedOn;

    @ManyToOne(fetch = FetchType.LAZY)
    public Project project;

    @OneToMany(mappedBy = "sample", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<File> files = new HashSet<>();

    @ManyToMany(mappedBy = "samples")
    public Set<Assay> assays = new HashSet<>();

    @OneToMany(mappedBy = "sample", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<SampleMetadataValue> metadataValues = new HashSet<>();

    // Inverse side of Project.linkedSamples: the sub-projects (if any) this sample
    // has been made visible in, in addition to its own root project.
    @ManyToMany(mappedBy = "linkedSamples")
    public Set<Project> linkedInSubProjects = new HashSet<>();

    public static boolean sampleExists(Long sampleId) {
        return findByIdOptional(sampleId).isPresent();
    }

    public static Optional<Sample> findSampleById(UUID sampleId) {
        return findByIdOptional(sampleId);
    }

    /**
     * All of a project's visible samples. For a root project this is the samples it owns;
     * for a sub-project it's the curated subset linked in via Project.linkedSamples.
     */
    public static List<Sample> getAllSamplesInProject(Long projectId) {
        Project project = resolveProject(projectId);
        if (!project.isSubProject()) {
            return list("project.id = ?1", projectId);
        }
        return list("select s from Sample s join s.linkedInSubProjects lp where lp.id = ?1", projectId);
    }

    public static Optional<Sample> findByIdInProjectScope(UUID sampleId, Long projectId) {
        Project project = resolveProject(projectId);
        if (!project.isSubProject()) {
            return find("id = ?1 and project.id = ?2", sampleId, projectId).firstResultOptional();
        }
        return find("select s from Sample s join s.linkedInSubProjects lp where s.id = ?1 and lp.id = ?2",
                        sampleId, projectId)
                .firstResultOptional();
    }

    public static Optional<Sample> findBySampleNameInProject(String name, Long projectId) {
        Project project = resolveProject(projectId);
        if (!project.isSubProject()) {
            return find("project.id = ?1 and name = ?2", projectId, name).firstResultOptional();
        }
        return find("select s from Sample s join s.linkedInSubProjects lp where lp.id = ?1 and s.name = ?2",
                        projectId, name)
                .firstResultOptional();
    }

    public static boolean sampleExistsByName(String name, Long projectId) {
        return findBySampleNameInProject(name, projectId).isPresent();
    }

    private static Project resolveProject(Long projectId) {
        return Project.<Project>findByIdOptional(projectId).orElseThrow(NotFoundException::new);
    }

    public static List<Sample> findSamplesInAssay(UUID assayId) {
        return Sample.list("select s from Sample s join s.assays a where a.id = ?1", assayId);
    }
}
