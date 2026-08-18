package no.metatrack.server.sample.vocabulary;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "global_sample_vocabulary_term",
        uniqueConstraints = @UniqueConstraint(columnNames = {"vocabulary_id", "value"}))
public class GlobalSampleVocabularyTerm extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public GlobalSampleVocabulary vocabulary;

    @Column(nullable = false, columnDefinition = "text")
    public String value;
}