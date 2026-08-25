package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.vocabulary.SampleVocabulary;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
public class Project extends PanacheEntity {
    @Column(unique = true, nullable = false)
    public String name;

    public String description;

    @Column(nullable = false)
    public UUID owner; // keycloak ID

    public Instant createdOn;
    public Instant modifiedOn;

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<ProjectMember> projectMembers = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<Sample> samples = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<Assay> assays = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<SampleMetadataField> sampleMetadataFields = new HashSet<>();

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<SampleVocabulary> sampleVocabularies = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_project_id")
    public Project parentProject;

    @OneToMany(mappedBy = "parentProject", fetch = FetchType.LAZY)
    public Set<Project> subProjects = new HashSet<>();

    // Only meaningful for sub-projects: the subset of the parent project's samples
    // this sub-project has been given access to. Samples always physically belong
    // to their root project (see Sample.project) - this is a visibility link, not ownership.
    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "project_sample",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "sample_id"))
    public Set<Sample> linkedSamples = new HashSet<>();

    public boolean isSubProject() {
        return parentProject != null;
    }

    public static boolean projectExists(Long projectId) {
        return findByIdOptional(projectId).isPresent();
    }

    public static boolean projectExistsByName(String projectName) {
        return find("name", projectName).firstResultOptional().isPresent();
    }

    public static List<Project> findProjectsByMember(UUID userId) {
        return find("select distinct pm.project from ProjectMember pm where pm.memberId = ?1", userId)
                .list();
    }

    public static List<Project> findSubProjects(Long parentProjectId) {
        return list("parentProject.id = ?1", parentProjectId);
    }
}
