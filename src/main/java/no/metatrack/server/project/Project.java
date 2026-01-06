package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
public class Project extends PanacheEntity {
    @Column(unique = true, nullable = false)
    String name;

    String description;

    @Column(nullable = false)
    UUID owner; // keycloak ID

    Instant createdOn;
    Instant modifiedOn;

    @OneToMany(mappedBy = "project", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    Set<ProjectMember> projectMembers = new HashSet<>();

    public static boolean projectExists(Long projectId) {
        return findByIdOptional(projectId).isPresent();
    }

    public static boolean projectExistsByName(String projectName) {
        return find("name", projectName).firstResultOptional().isPresent();
    }
}
