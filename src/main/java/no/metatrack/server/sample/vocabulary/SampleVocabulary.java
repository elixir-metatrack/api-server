package no.metatrack.server.sample.vocabulary;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "sample_vocabulary", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "field_key"}))
public class SampleVocabulary extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Project project;

    @Column(name = "field_key", nullable = false, length = 64)
    public String fieldKey;

    @Column(nullable = false)
    public Instant createdOn;

    @Column(nullable = false)
    public Instant modifiedOn;

    @OneToMany(mappedBy = "vocabulary", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<SampleVocabularyTerm> terms = new LinkedHashSet<>();
}