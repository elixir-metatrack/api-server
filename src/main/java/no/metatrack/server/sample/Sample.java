package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.assay.Assay;
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

    public static boolean sampleExistsByName(String name, Long projectId) {
        return find("project.id = ?1 and name = ?2", projectId, name)
                .firstResultOptional()
                .isPresent();
    }

    public static List<Sample> findSamplesInAssay(UUID assayId) {
        return Sample.list("select s from Sample s join s.assays a where a.id = ?1", assayId);
    }
}
