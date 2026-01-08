package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import no.metatrack.server.sample.Sample;

import java.time.Instant;
import java.util.HashSet;
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

    public static boolean projectExists(Long projectId) {
        return findByIdOptional(projectId).isPresent();
    }

    public static boolean projectExistsByName(String projectName) {
        return find("name", projectName).firstResultOptional().isPresent();
    }
}
