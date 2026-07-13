package no.metatrack.server.sample.metadata;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "sample_metadata_field", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "field_key"}))
public class SampleMetadataField extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Project project;

    @Column(name = "field_key", nullable = false, length = 64)
    public String key;

    @Column(nullable = false)
    public String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public SampleMetadataFieldType type;

    @Column(nullable = false)
    public Instant createdOn;

    @Column(nullable = false)
    public Instant modifiedOn;

    public Instant archivedOn;

    @OneToMany(mappedBy = "field", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    public Set<SampleMetadataValue> values = new HashSet<>();
}