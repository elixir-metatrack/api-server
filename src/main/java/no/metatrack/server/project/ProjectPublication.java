package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
public class ProjectPublication extends PanacheEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    public Project project;

    @Column(nullable = false)
    public String title;

    public String doi;

    @ElementCollection
    @CollectionTable(name = "project_publication_link", joinColumns = @JoinColumn(name = "publication_id"))
    @OrderColumn(name = "list_order")
    @Column(name = "link", nullable = false, columnDefinition = "text")
    public List<String> links = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "project_publication_pid", joinColumns = @JoinColumn(name = "publication_id"))
    @OrderColumn(name = "list_order")
    @Column(name = "pid", nullable = false, columnDefinition = "text")
    public List<String> pids = new ArrayList<>();

    public static Optional<ProjectPublication> findInProject(Long projectId, Long publicationId) {
        return find("id = ?1 and project.id = ?2", publicationId, projectId).firstResultOptional();
    }
}